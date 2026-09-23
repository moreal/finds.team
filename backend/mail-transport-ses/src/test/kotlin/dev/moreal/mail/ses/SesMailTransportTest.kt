@file:OptIn(aws.smithy.kotlin.runtime.InternalApi::class)

package dev.moreal.mail.ses

import aws.sdk.kotlin.services.sesv2.model.*
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import aws.smithy.kotlin.runtime.ClientException
import aws.smithy.kotlin.runtime.http.operation.CallTimeoutException
import aws.smithy.kotlin.runtime.http.HttpException
import aws.smithy.kotlin.runtime.http.HttpErrorCode
import dev.moreal.mail.*
import jakarta.mail.internet.InternetAddress
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test

class SesMailTransportTest {
  private val provider = MailProvider("ses")
  private val settings = SesSettings("ap-northeast-2", "transactional")
  private val message = MailMessage(
    MailMessageId.parse("0fd1ca40-aaaa-4bbb-8ccc-000000000001"),
    Mailbox("sender@example.test", "보내는 사람"),
    Recipients(
      to = listOf(Mailbox("to@example.test", "받는 사람")),
      cc = listOf(Mailbox("cc@example.test", "참조 담당자")),
      bcc = listOf(Mailbox("hidden@example.test", "숨은 담당자")),
    ),
    "인증 코드를 확인하세요",
    MailContent("인증 코드: 123456", "<p>인증 코드: <b>123456</b></p>"),
    tags = setOf("verification"),
    replyTo = Mailbox("reply@example.test", "답장 담당자"),
  )

  @Test
  fun `maps Korean alternatives and all named mailboxes and preserves id on every attempt`() = runBlocking<Unit> {
    val client = CapturingClient { SendEmailResponse { messageId = "ses-receipt" } }
    val transport = SesMailTransport(settings, client)
    repeat(2) { assertEquals(MailDeliveryResult.Accepted(provider, "ses-receipt"), transport.send(message)) }
    assertEquals(2, client.requests.size)
    for (request in client.requests) {
      assertMailbox("sender@example.test", "보내는 사람", request.fromEmailAddress!!)
      assertMailbox("to@example.test", "받는 사람", request.destination!!.toAddresses!!.single())
      assertMailbox("cc@example.test", "참조 담당자", request.destination!!.ccAddresses!!.single())
      assertMailbox("hidden@example.test", "숨은 담당자", request.destination!!.bccAddresses!!.single())
      assertMailbox("reply@example.test", "답장 담당자", request.replyToAddresses!!.single())
      assertEquals("transactional", request.configurationSetName)
      val simple = request.content!!.simple!!
      assertEquals("인증 코드를 확인하세요", simple.subject!!.data)
      assertEquals("UTF-8", simple.subject!!.charset)
      assertEquals("인증 코드: 123456", simple.body!!.text!!.data)
      assertEquals("UTF-8", simple.body!!.text!!.charset)
      assertEquals("<p>인증 코드: <b>123456</b></p>", simple.body!!.html!!.data)
      assertEquals("UTF-8", simple.body!!.html!!.charset)
      assertEquals(mapOf("mail-message-id" to "0fd1ca40-aaaa-4bbb-8ccc-000000000001", "verification" to "true"), request.emailTags!!.associate { it.name to it.value })
    }
  }

  @Test
  fun `text and html only omit absent alternatives and optional headers`() = runBlocking<Unit> {
    val client = CapturingClient { SendEmailResponse { messageId = "receipt" } }
    val transport = SesMailTransport(SesSettings("ap-northeast-2"), client)
    for (content in listOf(MailContent(text = "텍스트"), MailContent(html = "<p>HTML</p>"))) {
      transport.send(MailMessage(message.id, message.from, message.recipients, "제목", content))
    }
    assertEquals(2, client.requests.size)
    assertNull(client.requests[0].content!!.simple!!.body!!.html)
    assertNull(client.requests[1].content!!.simple!!.body!!.text)
    assertNull(client.requests[0].configurationSetName)
    assertTrue(client.requests[0].replyToAddresses.isNullOrEmpty())
  }

  @Test
  fun `only definite service refusals permit retries`() = runBlocking<Unit> {
    val cases = listOf(
      TooManyRequestsException { message = "private provider response" } to MailDeliveryResult.Rejected(provider, MailFailure.THROTTLED, true),
      serviceFailure("ServiceUnavailable") to MailDeliveryResult.Rejected(provider, MailFailure.SERVICE_UNAVAILABLE, true),
      serviceFailure("InternalFailure") to MailDeliveryResult.Indeterminate(provider, MailFailure.SERVICE_UNAVAILABLE),
      serviceFailure("UnknownFailure") to MailDeliveryResult.Indeterminate(provider, MailFailure.UNKNOWN),
      MessageRejected { message = "private provider response" } to MailDeliveryResult.Rejected(provider, MailFailure.INVALID_MESSAGE, false),
      serviceFailure("AccessDeniedException") to MailDeliveryResult.Rejected(provider, MailFailure.AUTHENTICATION, false),
      BadRequestException { message = "private provider response" } to MailDeliveryResult.Rejected(provider, MailFailure.INVALID_MESSAGE, false),
      ClientException("private provider response", SocketTimeoutException("OTP-123456")) to MailDeliveryResult.Indeterminate(provider, MailFailure.TIMEOUT),
      CallTimeoutException("OTP-123456", IllegalStateException("timer elapsed")) to MailDeliveryResult.Indeterminate(provider, MailFailure.TIMEOUT),
      HttpException("OTP-123456", HttpErrorCode.SOCKET_TIMEOUT) to MailDeliveryResult.Indeterminate(provider, MailFailure.TIMEOUT),
      ClientException("private provider response", IOException("OTP-123456")) to MailDeliveryResult.Indeterminate(provider, MailFailure.NETWORK),
      IllegalStateException("sender@example.test OTP-123456") to MailDeliveryResult.Indeterminate(provider, MailFailure.UNKNOWN),
    )
    for ((failure, expected) in cases) {
      val client = CapturingClient { throw failure }
      val result = SesMailTransport(settings, client).send(message)
      assertEquals(expected, result, failure.javaClass.simpleName)
      assertEquals(1, client.requests.size)
      for (secret in listOf("OTP-123456", "example.test", "private provider response")) assertFalse(result.toString().contains(secret))
    }
  }

  @Test
  fun `missing acceptance receipt stays indeterminate`() = runBlocking<Unit> {
    for (receipt in listOf(null, "", "private\nresponse")) {
      val client = CapturingClient { SendEmailResponse { messageId = receipt } }
      assertEquals(MailDeliveryResult.Indeterminate(provider, MailFailure.UNKNOWN), SesMailTransport(settings, client).send(message))
    }
  }

  @Test
  fun `caller cancellation cancels pending request without blocking coroutine dispatcher`() = runBlocking<Unit> {
    val started = CompletableDeferred<Unit>()
    var cancelled = false
    val client = CapturingClient {
      started.complete(Unit)
      try { awaitCancellation() } finally { cancelled = true }
    }
    val pending = async { SesMailTransport(settings, client).send(message) }
    withTimeout(2000) { started.await() }
    pending.cancelAndJoin()
    assertTrue(cancelled)
    assertFailsWith<CancellationException> { pending.await() }
  }

  @Test
  fun `SDK cancellation propagates without classification`() = runBlocking<Unit> {
    val client = CapturingClient { throw CancellationException("cancelled") }
    assertFailsWith<CancellationException> { SesMailTransport(settings, client).send(message) }
  }

  private fun serviceFailure(code: String) = SesV2Exception("private provider response OTP-123456").apply {
    sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = code
  }

  @Test
  fun `invalid SES tags are refused locally and cannot overwrite stable id`() = runBlocking<Unit> {
    val client = CapturingClient { SendEmailResponse { messageId = "receipt" } }
    for (tag in listOf("mail-message-id", "한글", "a".repeat(257), "invalid.tag")) {
      val invalid = MailMessage(message.id, message.from, message.recipients, message.subject, message.content, setOf(tag))
      assertEquals(MailDeliveryResult.Rejected(provider, MailFailure.INVALID_MESSAGE, false), SesMailTransport(settings, client).send(invalid))
    }
    assertTrue(client.requests.isEmpty())
  }

  @Test
  fun `invalid settings are rejected without reflecting sensitive values`() {
    for (region in listOf("", "secret region", "private\nregion")) {
      val error = assertFailsWith<IllegalArgumentException> { SesSettings(region) }
      assertFalse(error.toString().contains("private"))
    }
    for (configuration in listOf("", "private\nconfiguration", "a".repeat(65))) {
      assertFailsWith<IllegalArgumentException> { SesSettings("ap-northeast-2", configuration) }
    }
    assertFalse(SesSettings("ap-northeast-2", "private-config").toString().contains("private-config"))
  }

  private fun assertMailbox(address: String, name: String, encoded: String) {
    val parsed = InternetAddress(encoded)
    assertEquals(address, parsed.address)
    assertEquals(name, parsed.personal)
    assertTrue(encoded.all { it.code < 128 })
  }

  private class CapturingClient(val response: suspend () -> SendEmailResponse) : SesClient {
    val requests = mutableListOf<SendEmailRequest>()
    override suspend fun send(request: SendEmailRequest): SendEmailResponse {
      requests += request
      return response()
    }
  }
}
