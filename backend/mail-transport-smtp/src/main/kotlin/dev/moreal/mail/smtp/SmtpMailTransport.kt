package dev.moreal.mail.smtp

import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailFailure
import dev.moreal.mail.MailMessage
import dev.moreal.mail.MailProvider
import dev.moreal.mail.MailTransport
import dev.moreal.mail.Mailbox
import dev.moreal.mail.smtp.internal.TrackedSmtpTransport
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** NONE is an explicit opt-in for a trusted local relay. STARTTLS requires an upgrade. */
enum class SmtpTlsMode { NONE, STARTTLS, IMPLICIT }

/**
 * Username/password authentication supports SMTP AUTH LOGIN and PLAIN. Use TLS for remote relays.
 * DIGEST-MD5, NTLM, OAuth and external SASL mechanisms are not enabled by this adapter.
 */
class SmtpSettings(
  val host: String,
  val port: Int,
  val tlsMode: SmtpTlsMode,
  val username: String? = null,
  val password: String? = null,
  val connectTimeout: Duration,
  val readTimeout: Duration,
) {
  init {
    require(host.isNotBlank() && host.none { it.isWhitespace() || it.isISOControl() }) { "Invalid SMTP host" }
    require(port in 1..65535) { "Invalid SMTP port" }
    require((username == null) == (password == null)) { "SMTP credentials must be supplied together" }
    require(username == null || username.isNotBlank() && username.none { it.isISOControl() }) {
      "Invalid SMTP username"
    }
    for (timeout in listOf(connectTimeout, readTimeout)) {
      require(timeout.isFinite() && timeout >= 1.milliseconds && timeout <= Int.MAX_VALUE.toLong().milliseconds) {
        "SMTP timeouts must be between 1 and 2147483647 milliseconds"
      }
    }
  }

  override fun toString(): String = "SmtpSettings(<redacted>)"
}

class SmtpMailTransport(private val settings: SmtpSettings) : MailTransport {
  override val provider = MailProvider("smtp")

  override suspend fun send(message: MailMessage): MailDeliveryResult {
    val context = coroutineContext
    return runInterruptible(Dispatchers.IO) {
      context.ensureActive()
      var transport: TrackedSmtpTransport? = null
      try {
        val session = Session.getInstance(properties(message))
        val mime = render(session, message)
        transport = TrackedSmtpTransport(session)
        transport.connect(settings.host, settings.port, settings.username, settings.password)
        context.ensureActive()
        transport.sendMessage(mime, mime.allRecipients)
        MailDeliveryResult.Accepted(provider, mime.messageID)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        context.ensureActive()
        classify(failure, transport?.dataStarted == true, transport?.preDataReply)
      } finally {
        // QUIT is cleanup, not the acceptance boundary. Never replace a 250 receipt with a close failure.
        try { transport?.close() } catch (_: MessagingException) { }
      }
    }
  }

  private fun properties(message: MailMessage) = Properties().apply {
    setProperty("mail.smtp.connectiontimeout", settings.connectTimeout.inWholeMilliseconds.toString())
    setProperty("mail.smtp.timeout", settings.readTimeout.inWholeMilliseconds.toString())
    setProperty("mail.smtp.writetimeout", settings.readTimeout.inWholeMilliseconds.toString())
    setProperty("mail.smtp.auth", (settings.username != null).toString())
    // Pinned Angus legacy challenge helpers log credentials outside the isolated transport namespace.
    // Constrain negotiation instead of mutating those shared upstream loggers.
    setProperty("mail.smtp.auth.mechanisms", "LOGIN PLAIN")
    setProperty("mail.smtp.sasl.enable", "false")
    setProperty("mail.smtp.starttls.enable", (settings.tlsMode == SmtpTlsMode.STARTTLS).toString())
    setProperty("mail.smtp.starttls.required", (settings.tlsMode == SmtpTlsMode.STARTTLS).toString())
    setProperty("mail.smtp.ssl.enable", (settings.tlsMode == SmtpTlsMode.IMPLICIT).toString())
    setProperty("mail.smtp.ssl.checkserveridentity", "true")
    setProperty("mail.smtp.from", message.from.address)
    setProperty("mail.smtp.sendpartial", "false")
    setProperty("mail.smtp.quitwait", "false")
    setProperty("mail.smtp.chunksize", "-1")
    setProperty("mail.debug", "false")
    setProperty("mail.debug.auth", "false")
    setProperty("mail.debug.auth.username", "false")
    setProperty("mail.debug.auth.password", "false")
  }

  private fun render(session: Session, message: MailMessage): MimeMessage =
    object : MimeMessage(session) {
      override fun updateMessageID() {
        setHeader("Message-ID", "<${message.id}@mail.invalid>")
      }
    }.apply {
      // Core values reject header controls; structured address APIs handle quoting and UTF-8 encoding.
      setFrom(address(message.from))
      message.replyTo?.let { replyTo = arrayOf(address(it)) }
      setRecipients(Message.RecipientType.TO, message.recipients.to.map(::address).toTypedArray())
      setRecipients(Message.RecipientType.CC, message.recipients.cc.map(::address).toTypedArray())
      setRecipients(Message.RecipientType.BCC, message.recipients.bcc.map(::address).toTypedArray())
      setSubject(message.subject, "UTF-8")
      val text = message.content.text
      val html = message.content.html
      if (text != null && html != null) {
        setContent(MimeMultipart("alternative").apply {
          addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
          addBodyPart(MimeBodyPart().apply { setText(html, "UTF-8", "html") })
        })
      } else if (text != null) {
        setText(text, "UTF-8")
      } else {
        setText(html, "UTF-8", "html")
      }
      saveChanges()
    }

  private fun address(mailbox: Mailbox) = InternetAddress(mailbox.address, mailbox.name, "UTF-8")

  private fun classify(failure: Exception, dataStarted: Boolean, preDataReply: Int?): MailDeliveryResult {
    val causes = causes(failure)
    val authentication = causes.any { it is AuthenticationFailedException }
    val timeout = causes.any { it is SocketTimeoutException }
    val sendFailure = causes.filterIsInstance<SMTPSendFailedException>()
      .firstOrNull { it.returnCode in 400..599 }
    val recipientFailures = causes.filterIsInstance<SMTPAddressFailedException>()
    // Only a negative reply to DATA or its terminator proves refusal after submission began.
    val explicitRefusal = sendFailure?.takeIf { it.command == "DATA" || it.command == "." }
    if (dataStarted && explicitRefusal == null) {
      return MailDeliveryResult.Indeterminate(provider, when {
        timeout -> MailFailure.TIMEOUT
        causes.any { it is IOException } -> MailFailure.NETWORK
        else -> MailFailure.UNKNOWN
      })
    }
    val codes = recipientFailures.map { it.returnCode } + listOfNotNull(
      sendFailure?.returnCode,
      preDataReply?.takeIf { !dataStarted && it in 400..599 },
    )
    val permanent = codes.any { it in 500..599 }
    val category = when {
      authentication -> MailFailure.AUTHENTICATION
      recipientFailures.isNotEmpty() -> MailFailure.INVALID_RECIPIENT
      timeout -> MailFailure.TIMEOUT
      codes.any { it in 400..499 } -> MailFailure.SERVICE_UNAVAILABLE
      permanent -> MailFailure.INVALID_MESSAGE
      causes.any { it is IOException } -> MailFailure.NETWORK
      else -> MailFailure.UNKNOWN
    }
    return MailDeliveryResult.Rejected(
      provider, category,
      !authentication && !permanent && category != MailFailure.UNKNOWN,
    )
  }

  private fun causes(failure: Throwable): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>().apply { add(failure) }
    while (pending.isNotEmpty()) {
      val next = pending.removeFirst()
      if (!seen.add(next)) continue
      next.cause?.let(pending::add)
      (next as? MessagingException)?.nextException?.let(pending::add)
    }
    return seen.toList()
  }
}
