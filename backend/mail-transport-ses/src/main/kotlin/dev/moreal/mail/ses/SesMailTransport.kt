package dev.moreal.mail.ses

import aws.sdk.kotlin.services.sesv2.SesV2Client
import aws.sdk.kotlin.services.sesv2.model.*
import aws.smithy.kotlin.runtime.http.HttpErrorCode
import aws.smithy.kotlin.runtime.http.HttpException
import aws.smithy.kotlin.runtime.http.operation.ClientTimeoutException
import aws.smithy.kotlin.runtime.client.LogMode
import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.http.interceptors.HttpInterceptor
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.request.toBuilder
import aws.smithy.kotlin.runtime.io.SdkBuffer
import aws.smithy.kotlin.runtime.telemetry.TelemetryProvider
import dev.moreal.mail.*
import jakarta.mail.internet.InternetAddress
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class SesSettings(val region: String, val configurationSet: String? = null) {
  init {
    require(Regex("[a-z0-9]+(?:-[a-z0-9]+)+").matches(region)) { "Invalid SES region" }
    require(configurationSet == null || Regex("[A-Za-z0-9_-]{1,64}").matches(configurationSet)) {
      "Invalid SES configuration set"
    }
  }
  override fun toString(): String = "SesSettings(<redacted>)"
}

internal fun interface SesClient : AutoCloseable {
  suspend fun send(request: SendEmailRequest): SendEmailResponse
  override fun close() = Unit
}

/**
 * Uses the default AWS credential chain. Close this transport when its owning application stops.
 * Tags must use SES's ASCII letter/digit/underscore/dash alphabet (1..256 characters); the
 * `mail-message-id` tag is reserved. It is correlation metadata, not an SES idempotency guarantee.
 * SDK telemetry is disabled for this client; use the provider-neutral observability decorator.
 */
class SesMailTransport internal constructor(
  private val settings: SesSettings,
  private val client: SesClient,
) : MailTransport, AutoCloseable {
  constructor(settings: SesSettings) : this(settings, AwsSesClient(settings))

  override val provider = MailProvider("ses")
  override fun close() = client.close()

  override suspend fun send(message: MailMessage): MailDeliveryResult {
    coroutineContext.ensureActive()
    // SES tags have a narrower alphabet than core metadata. Never truncate or replace the stable id.
    if (message.tags.any { it == "mail-message-id" || !TAG.matches(it) }) {
      return MailDeliveryResult.Rejected(provider, MailFailure.INVALID_MESSAGE, false)
    }
    return try {
      val response = client.send(SendEmailRequest {
        fromEmailAddress = address(message.from)
        destination = Destination {
          toAddresses = message.recipients.to.map(::address)
          ccAddresses = message.recipients.cc.map(::address)
          bccAddresses = message.recipients.bcc.map(::address)
        }
        replyToAddresses = message.replyTo?.let { listOf(address(it)) }
        configurationSetName = settings.configurationSet
        content = EmailContent {
          simple = Message {
            subject = utf8(message.subject)
            body = Body {
              text = message.content.text?.let(::utf8)
              html = message.content.html?.let(::utf8)
            }
          }
        }
        emailTags = listOf(MessageTag { name = "mail-message-id"; value = message.id.toString() }) +
          message.tags.map { tag -> MessageTag { name = tag; value = "true" } }
      })
      coroutineContext.ensureActive()
      val receipt = response.messageId
      if (receipt.isNullOrBlank() || receipt.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
        MailDeliveryResult.Indeterminate(provider, MailFailure.UNKNOWN)
      } else {
        MailDeliveryResult.Accepted(provider, receipt)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      coroutineContext.ensureActive()
      classify(failure)
    }
  }

  private fun address(mailbox: Mailbox) = InternetAddress(mailbox.address, mailbox.name, "UTF-8").toString()
  private fun utf8(value: String) = Content { data = value; charset = "UTF-8" }

  private fun classify(failure: Exception): MailDeliveryResult {
    val code = (failure as? SesV2Exception)?.sdkErrorMetadata?.errorCode
    return when {
      failure is TooManyRequestsException || code == "ThrottlingException" ->
        MailDeliveryResult.Rejected(provider, MailFailure.THROTTLED, true)
      code == "ServiceUnavailable" ->
        MailDeliveryResult.Rejected(provider, MailFailure.SERVICE_UNAVAILABLE, true)
      failure is MessageRejected || failure is BadRequestException ->
        MailDeliveryResult.Rejected(provider, MailFailure.INVALID_MESSAGE, false)
      code in setOf("AccessDeniedException", "NotAuthorized", "InvalidClientTokenId", "UnrecognizedClientException") ->
        MailDeliveryResult.Rejected(provider, MailFailure.AUTHENTICATION, false)
      else -> {
        val causes = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = failure
        while (current != null && causes.add(current)) current = current.cause
        val category = when {
          causes.any { it is ClientTimeoutException || it is SocketTimeoutException || it is HttpException && it.errorCode in TIMEOUT_CODES } -> MailFailure.TIMEOUT
          causes.any { it is IOException || it is HttpException } -> MailFailure.NETWORK
          code == "InternalFailure" -> MailFailure.SERVICE_UNAVAILABLE
          else -> MailFailure.UNKNOWN
        }
        MailDeliveryResult.Indeterminate(provider, category)
      }
    }
  }

  private companion object {
    val TAG = Regex("[A-Za-z0-9_-]{1,256}")
    val TIMEOUT_CODES = setOf(HttpErrorCode.CONNECT_TIMEOUT, HttpErrorCode.CONNECTION_ACQUIRE_TIMEOUT,
      HttpErrorCode.TLS_NEGOTIATION_TIMEOUT, HttpErrorCode.SOCKET_TIMEOUT)
  }
}

internal fun SesV2Client.Config.Builder.configureSes(settings: SesSettings) {
  region = settings.region
  retryStrategy { maxAttempts = 1 }
  logMode = LogMode.Default
  telemetryProvider = TelemetryProvider.None
  interceptors.add(SingleUseSendBody)
}

/**
 * The SDK's OkHttp engine can retry 503/408 and connection failures below the SDK retry strategy.
 * Mark the already-signed JSON as one-shot so it cannot replay an ambiguous SendEmail operation.
 * Bytes and content length are unchanged, preserving SigV4 and the HTTP framing.
 */
private object SingleUseSendBody : HttpInterceptor {
  override suspend fun modifyBeforeTransmit(
    context: ProtocolRequestInterceptorContext<Any, HttpRequest>,
  ): HttpRequest {
    val request = context.protocolRequest
    if (request.body.isOneShot) return request
    val original = request.body as? HttpBody.Bytes ?: error("Unsupported SES request body")
    val bytes = original.bytes()
    val buffer = SdkBuffer().apply { write(bytes) }
    return request.toBuilder().apply {
      body = object : HttpBody.SourceContent() {
        override val contentLength = bytes.size.toLong()
        override val isOneShot = true
        override fun readFrom() = buffer
      }
    }.build()
  }
}

private class AwsSesClient(settings: SesSettings) : SesClient {
  private val sdk = SesV2Client { configureSes(settings) }
  override suspend fun send(request: SendEmailRequest): SendEmailResponse = sdk.sendEmail(request)
  override fun close() = sdk.close()
}
