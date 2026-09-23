package dev.moreal.mail.smtp

import dev.moreal.mail.*
import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SmtpMailTransportTest {
  private val message = MailMessage(
    MailMessageId.parse("0fd1ca40-aaaa-4bbb-8ccc-000000000001"),
    Mailbox("sender@example.test", "보내는 사람"),
    Recipients(
      to = listOf(Mailbox("to@example.test", "받는 사람")),
      cc = listOf(Mailbox("cc@example.test")),
      bcc = listOf(Mailbox("hidden@example.test")),
    ),
    "인증 코드를 확인하세요",
    MailContent(text = "인증 코드: 123456", html = "<p>인증 코드: <b>123456</b></p>"),
    replyTo = Mailbox("reply@example.test", "답장 담당자"),
  )

  @Test
  @Order(1)
  fun `verbose JUL before initialization cannot expose concurrent mail deliveries`() = runBlocking<Unit> {
    val root = Logger.getLogger("")
    val unrelated = Logger.getLogger("smtp-test.unrelated")
    val previousRootLevel = root.level
    val previousUnrelatedLevel = unrelated.level
    val records = CopyOnWriteArrayList<LogRecord>()
    val handler = object : Handler() {
      override fun publish(record: LogRecord) { records += record }
      override fun flush() = Unit
      override fun close() = Unit
    }.apply { level = Level.ALL }
    root.addHandler(handler)
    root.level = Level.FINEST
    unrelated.level = Level.FINER
    try {
      val connected = CountDownLatch(2)
      coroutineScope {
        (1..2).map { index ->
          async {
            LocalSmtpServer(connected = connected).use { server ->
              val sensitive = MailMessage(
                MailMessageId.new(), Mailbox("private-sender-$index@example.test"),
                Recipients(to = listOf(Mailbox("private-recipient-$index@example.test"))),
                "private-subject-$index", MailContent(text = "private-body-$index OTP-987654-$index"),
              )
              assertIs<MailDeliveryResult.Accepted>(SmtpMailTransport(server.settings()).send(sensitive))
              assertEquals(sensitive.content.text, server.delivery.get(3, TimeUnit.SECONDS).mime().content.toString().trimEnd())
            }
          }
        }.awaitAll()
      }
      unrelated.finer("unrelated-log-still-enabled")
      val captured = records.joinToString("\n") { record ->
        record.message + record.parameters.orEmpty().joinToString() + record.thrown?.toString().orEmpty()
      }
      assertTrue(captured.contains("unrelated-log-still-enabled"))
      assertEquals(Level.FINEST, root.level)
      assertEquals(Level.FINER, unrelated.level)
      for (marker in listOf("private-sender-", "private-recipient-", "private-subject-", "private-body-", "OTP-987654-")) {
        assertFalse(captured.contains(marker), "Sensitive SMTP marker escaped to JUL: $marker")
      }
    } finally {
      root.removeHandler(handler)
      root.level = previousRootLevel
      unrelated.level = previousUnrelatedLevel
    }
  }

  @Test
  fun `temporary greeting refusal is definitely unaccepted and retryable`() = runBlocking<Unit> {
    LocalSmtpServer(greeting = "421 Service temporarily unavailable").use { server ->
      assertEquals(
        MailDeliveryResult.Rejected(MailProvider("smtp"), MailFailure.SERVICE_UNAVAILABLE, true),
        SmtpMailTransport(server.settings()).send(message),
      )
      assertEquals(1L, server.dataStarted.count)
    }
  }

  @Test
  fun `renders Unicode alternatives and all envelope recipients without exposing Bcc`() = runBlocking<Unit> {
    LocalSmtpServer().use { server ->
      val result = SmtpMailTransport(server.settings()).send(message)
      assertIs<MailDeliveryResult.Accepted>(result)
      assertEquals(MailProvider("smtp"), result.provider)
      val delivery = server.delivery.get(3, TimeUnit.SECONDS)
      assertEquals("MAIL FROM:<sender@example.test>", delivery.sender)
      assertEquals(listOf("RCPT TO:<to@example.test>", "RCPT TO:<cc@example.test>", "RCPT TO:<hidden@example.test>"), delivery.recipients)
      val mime = delivery.mime()
      assertEquals("sender@example.test", (mime.from.single() as InternetAddress).address)
      assertEquals("보내는 사람", (mime.from.single() as InternetAddress).personal)
      assertEquals("reply@example.test", (mime.replyTo.single() as InternetAddress).address)
      assertEquals("답장 담당자", (mime.replyTo.single() as InternetAddress).personal)
      assertEquals(message.subject, mime.subject)
      assertNull(mime.getHeader("Bcc"))
      assertTrue(mime.isMimeType("multipart/alternative"))
      val alternatives = mime.content as Multipart
      assertEquals(2, alternatives.count)
      assertTrue(alternatives.getBodyPart(0).isMimeType("text/plain"))
      assertEquals(message.content.text, alternatives.getBodyPart(0).content)
      assertTrue(alternatives.getBodyPart(1).isMimeType("text/html"))
      assertEquals(message.content.html, alternatives.getBodyPart(1).content)
      assertEquals(mime.messageID, result.providerMessageId)
    }
  }

  @Test
  fun `Message-ID stays stable across repeated sends`() = runBlocking<Unit> {
    val ids = (1..2).map {
      LocalSmtpServer().use { server ->
        assertIs<MailDeliveryResult.Accepted>(SmtpMailTransport(server.settings()).send(message))
        server.delivery.get(3, TimeUnit.SECONDS).mime().messageID
      }
    }
    assertEquals(ids.first(), ids.last())
    assertTrue(ids.first().contains(message.id.toString()))
  }

  @Test
  fun `authentication refusal is permanent and never reaches DATA`() = runBlocking<Unit> {
    LocalSmtpServer(authFailure = true).use { server ->
      val result = SmtpMailTransport(server.settings(username = "user", password = "secret")).send(message)
      assertEquals(MailDeliveryResult.Rejected(MailProvider("smtp"), MailFailure.AUTHENTICATION, false), result)
      assertEquals(1L, server.dataStarted.count)
    }
  }

  @Test
  fun `lost response after DATA is indeterminate even when whole message reached server`() = runBlocking<Unit> {
    LocalSmtpServer(finalResponse = null).use { server ->
      val result = SmtpMailTransport(server.settings(readTimeout = 150.milliseconds)).send(message)
      assertEquals(MailDeliveryResult.Indeterminate(MailProvider("smtp"), MailFailure.TIMEOUT), result)
      assertNotNull(server.delivery.get(3, TimeUnit.SECONDS))
    }
  }

  @Test
  fun `explicit final temporary refusal is safe to retry`() = runBlocking<Unit> {
    LocalSmtpServer(finalResponse = "451 Temporary failure").use { server ->
      assertEquals(
        MailDeliveryResult.Rejected(MailProvider("smtp"), MailFailure.SERVICE_UNAVAILABLE, true),
        SmtpMailTransport(server.settings()).send(message),
      )
    }
  }

  @Test
  fun `partial envelope refusal sends no data and permanent recipient failures prohibit retry`() = runBlocking<Unit> {
    LocalSmtpServer(recipientResponse = "550 No mailbox").use { server ->
      assertEquals(
        MailDeliveryResult.Rejected(MailProvider("smtp"), MailFailure.INVALID_RECIPIENT, false),
        SmtpMailTransport(server.settings()).send(message),
      )
      assertEquals(1L, server.dataStarted.count)
    }
  }

  @Test
  fun `temporary recipient refusal is retryable without sending DATA`() = runBlocking<Unit> {
    LocalSmtpServer(recipientResponse = "450 Mailbox busy").use { server ->
      val result = assertIs<MailDeliveryResult.Rejected>(SmtpMailTransport(server.settings()).send(message))
      assertTrue(result.retryable)
      assertEquals(1L, server.dataStarted.count)
    }
  }

  @Test
  fun `timeout before envelope submission is definitely unaccepted`() = runBlocking<Unit> {
    LocalSmtpServer(greet = false).use { server ->
      assertEquals(
        MailDeliveryResult.Rejected(MailProvider("smtp"), MailFailure.TIMEOUT, true),
        SmtpMailTransport(server.settings(readTimeout = 150.milliseconds)).send(message),
      )
    }
  }

  @Test
  fun `required STARTTLS never falls back to plaintext delivery`() = runBlocking<Unit> {
    LocalSmtpServer().use { server ->
      assertIs<MailDeliveryResult.Rejected>(SmtpMailTransport(server.settings(tlsMode = SmtpTlsMode.STARTTLS)).send(message))
      assertEquals(1L, server.dataStarted.count)
    }
  }

  @Test
  fun `cancellation during SMTP wait propagates`() = runBlocking<Unit> {
    LocalSmtpServer(finalResponse = null).use { server ->
      val sending = async { SmtpMailTransport(server.settings(readTimeout = 300.milliseconds)).send(message) }
      withContext(Dispatchers.IO) { assertTrue(server.dataStarted.await(3, TimeUnit.SECONDS)) }
      sending.cancel()
      assertFailsWith<CancellationException> { sending.await() }
    }
  }

  @Test
  fun `settings reject invalid endpoints credentials and unbounded timeouts and redact secrets`() {
    fun settings(host: String = "localhost", port: Int = 2525, username: String? = null, password: String? = null,
                 connect: Duration = 1.seconds, read: Duration = 1.seconds) =
      SmtpSettings(host, port, SmtpTlsMode.NONE, username, password, connect, read)
    for (host in listOf("", "smtp\r\nInjected: yes", " smtp.example.test")) {
      assertFailsWith<IllegalArgumentException> { settings(host = host) }
    }
    for (port in listOf(0, -1, 65536)) assertFailsWith<IllegalArgumentException> { settings(port = port) }
    assertFailsWith<IllegalArgumentException> { settings(username = "user") }
    assertFailsWith<IllegalArgumentException> { settings(password = "secret") }
    for (timeout in listOf(Duration.ZERO, (-1).seconds, Duration.INFINITE, 1.milliseconds / 2, Int.MAX_VALUE.toLong().milliseconds + 1.milliseconds)) {
      assertFailsWith<IllegalArgumentException> { settings(connect = timeout) }
      assertFailsWith<IllegalArgumentException> { settings(read = timeout) }
    }
    val safe = settings(host = "private.example.test", username = "private-user", password = "private-secret").toString()
    for (value in listOf("private.example.test", "private-user", "private-secret")) assertFalse(safe.contains(value))
  }
}

private data class CapturedDelivery(val sender: String, val recipients: List<String>, val wire: String) {
  fun mime() = MimeMessage(Session.getInstance(Properties()), wire.byteInputStream(Charsets.US_ASCII))
}

/** Real wire peer; controls acknowledgment boundaries without substituting the transport under test. */
private class LocalSmtpServer(
  private val authFailure: Boolean = false,
  private val finalResponse: String? = "250 queued",
  private val recipientResponse: String = "250 recipient ok",
  private val greet: Boolean = true,
  private val greeting: String = "220 localhost test SMTP",
  private val connected: CountDownLatch? = null,
) : AutoCloseable {
  private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
  private val stop = CountDownLatch(1)
  @Volatile private var socket: Socket? = null
  val delivery = CompletableFuture<CapturedDelivery>()
  val dataStarted = CountDownLatch(1)
  private val worker = thread(isDaemon = true, name = "smtp-test-peer") {
    try {
      server.accept().use { client ->
        socket = client
        connected?.countDown()
        check(connected?.await(3, TimeUnit.SECONDS) != false) { "Concurrent SMTP connections did not start" }
        val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
        val output = client.getOutputStream().bufferedWriter(Charsets.US_ASCII)
        fun reply(value: String) { output.write("$value\r\n"); output.flush() }
        if (!greet) { stop.await(5, TimeUnit.SECONDS); return@thread }
        reply(greeting)
        if (!greeting.startsWith("220 ")) return@thread
        var sender = ""
        val recipients = mutableListOf<String>()
        while (true) {
          val line = input.readLine() ?: break
          when {
            line.startsWith("EHLO") -> {
              reply("250-localhost")
              reply(if (authFailure) "250 AUTH PLAIN" else "250 OK")
            }
            line.startsWith("AUTH") -> reply("535 Authentication failed")
            line.startsWith("MAIL FROM:") -> { sender = line; reply("250 sender ok") }
            line.startsWith("RCPT TO:") -> {
              recipients += line
              reply(if (recipients.size == 2) recipientResponse else "250 recipient ok")
            }
            line == "DATA" -> {
              dataStarted.countDown()
              reply("354 Send message")
              val wire = StringBuilder()
              while (true) {
                val data = input.readLine() ?: error("SMTP data ended without terminator")
                if (data == ".") break
                wire.append(data.removePrefix(".")).append("\r\n")
              }
              delivery.complete(CapturedDelivery(sender, recipients.toList(), wire.toString()))
              if (finalResponse == null) { stop.await(5, TimeUnit.SECONDS); break }
              reply(finalResponse)
            }
            line == "RSET" -> reply("250 reset")
            line == "QUIT" -> { reply("221 bye"); break }
            else -> reply("500 unsupported")
          }
        }
      }
    } catch (failure: Exception) {
      if (stop.count != 0L) delivery.completeExceptionally(failure)
    }
  }

  fun settings(
    username: String? = null,
    password: String? = null,
    readTimeout: Duration = 2.seconds,
    tlsMode: SmtpTlsMode = SmtpTlsMode.NONE,
  ) = SmtpSettings(server.inetAddress.hostAddress, server.localPort, tlsMode, username, password, 2.seconds, readTimeout)

  override fun close() {
    stop.countDown()
    socket?.close()
    server.close()
    worker.join(3000)
    check(!worker.isAlive) { "SMTP test peer did not stop" }
  }
}
