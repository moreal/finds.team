package dev.moreal.mail.ses

import aws.sdk.kotlin.services.sesv2.SesV2Client
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.client.LogMode
import aws.smithy.kotlin.runtime.net.url.Url
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import dev.moreal.mail.*
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class SesSdkTest {
  private val settings = SesSettings("ap-northeast-2")
  private val message = MailMessage(
    MailMessageId.parse("0fd1ca40-aaaa-4bbb-8ccc-000000000001"),
    Mailbox("unique-private-sender@example.test"),
    Recipients(to = listOf(Mailbox("unique-private-recipient@example.test"))),
    "unique-private-subject", MailContent(text = "unique-private-body OTP-987654"),
  )

  @Test
  fun `SDK performs one wire attempt for throttling unavailable and ambiguous failures`() = runBlocking<Unit> {
    for ((status, code, expected) in listOf(
      Triple(429, "TooManyRequestsException", MailDeliveryResult.Rejected(MailProvider("ses"), MailFailure.THROTTLED, true)),
      Triple(503, "ServiceUnavailable", MailDeliveryResult.Rejected(MailProvider("ses"), MailFailure.SERVICE_UNAVAILABLE, true)),
      Triple(500, "InternalFailure", MailDeliveryResult.Indeterminate(MailProvider("ses"), MailFailure.SERVICE_UNAVAILABLE)),
    )) {
      LocalSes(status, code).use { server ->
        client(server).use { sdk ->
          assertEquals(expected, SesMailTransport(settings, SesClient { sdk.sendEmail(it) }).send(message))
          assertEquals(1, server.calls.get(), "SDK must not retry $code")
          assertTrue(server.requests.single().contains("0fd1ca40-aaaa-4bbb-8ccc-000000000001"))
        }
      }
    }
  }

  @Test
  fun `HTTP engine cannot replay a send after immediate retry response or lost acknowledgment`() = runBlocking<Unit> {
    for (status in listOf(503, 408, 0)) {
      LocalSes(status, "ServiceUnavailable", immediateRetry = true).use { server ->
        client(server).use { sdk ->
          val result = SesMailTransport(settings, SesClient { sdk.sendEmail(it) }).send(message)
          if (status == 0) assertIs<MailDeliveryResult.Indeterminate>(result)
          assertEquals(1, server.calls.get(), "HTTP engine must not replay after $status")
        }
      }
    }
  }

  @Test
  fun `broad TRACE logging cannot expose requests credentials or provider response bodies`() = runBlocking<Unit> {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val originalLevel = root.level
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    root.level = Level.TRACE
    try {
      LocalSes(400, "MessageRejected").use { server ->
        client(server).use { sdk ->
          assertEquals(MailDeliveryResult.Rejected(MailProvider("ses"), MailFailure.INVALID_MESSAGE, false),
            SesMailTransport(settings, SesClient { sdk.sendEmail(it) }).send(message))
        }
        assertEquals(1, server.calls.get())
      }
      LoggerFactory.getLogger("unrelated-application").debug("unrelated logging retained")
      val output = appender.list.joinToString("\n") { it.formattedMessage + it.argumentArray.orEmpty().joinToString() + it.throwableProxy?.message.orEmpty() }
      assertTrue(output.contains("unrelated logging retained"))
      assertEquals(Level.TRACE, root.level)
      for (secret in listOf("unique-private-sender", "unique-private-recipient", "unique-private-subject", "unique-private-body", "OTP-987654", "unique-private-response", "unique-private-access", "unique-private-secret")) {
        assertFalse(output.contains(secret), "Sensitive marker escaped: $secret")
      }
    } finally {
      root.level = originalLevel
      root.detachAppender(appender)
      appender.stop()
    }
  }

  private fun client(server: LocalSes) = SesV2Client {
    endpointUrl = Url.parse("http://127.0.0.1:${server.port}")
    credentialsProvider = object : CredentialsProvider {
      override suspend fun resolve(attributes: Attributes) = Credentials("unique-private-access", "unique-private-secret")
    }
    // Deliberately unsafe initial settings: adapter configuration must suppress client-owned wire logs.
    logMode = LogMode.LogRequestWithBody + LogMode.LogResponseWithBody
    callTimeout = 10.seconds
    configureSes(settings)
  }

  private class LocalSes(status: Int, code: String, immediateRetry: Boolean = false) : AutoCloseable {
    val calls = AtomicInteger()
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/v2/email/outbound-emails") { exchange ->
        calls.incrementAndGet()
        requests += exchange.requestBody.bufferedReader().readText()
        if (status == 0) {
          exchange.close()
          return@createContext
        }
        val body = "{\"message\":\"unique-private-response OTP-987654\"}".toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("x-amzn-errortype", code)
        if (immediateRetry) exchange.responseHeaders.add("Retry-After", "0")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        exchange.close()
      }
      start()
    }
    val port get() = server.address.port
    override fun close() = server.stop(0)
  }
}
