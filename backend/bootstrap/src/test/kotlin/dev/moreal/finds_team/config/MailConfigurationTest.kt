package dev.moreal.finds_team.config

import dev.moreal.finds.application.port.MailPayloadCrypto
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.notification.MailDispatchPolicy
import dev.moreal.mail.MailTransport
import java.time.Duration
import java.util.Base64
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import kotlin.test.*
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.moreal.mail.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import dev.moreal.mail.testing.ScriptedMailTransport

class MailConfigurationTest {
  private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

  @Test
  fun `production retry and fallback trace every provider attempt without sensitive data`() = runBlocking {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val logs = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(logs)
    try {
      val message = MailMessage(MailMessageId.new(), Mailbox("private-sender@example.test"),
        Recipients(to = listOf(Mailbox("private-recipient@example.test"))), "private-subject",
        MailContent(text = "private-body OTP 19384217", html = "<p>private-html</p>"))
      val smtp = MailProvider("smtp")
      val ses = MailProvider("ses")
      val primary = ScriptedMailTransport(smtp, List(2) {
        MailDeliveryResult.Rejected(smtp, MailFailure.SERVICE_UNAVAILABLE, true)
      })
      val secondary = ScriptedMailTransport(ses, listOf(MailDeliveryResult.Accepted(ses, "private-provider-receipt")))
      val properties = MailProperties(retry = MailProperties.Retry(maxAttempts = 2,
        initialDelay = Duration.ZERO, maximumDelay = Duration.ZERO))
      val correlation = UUID.randomUUID()
      val registry = SimpleMeterRegistry()
      MailConfiguration().composeProviders(listOf(primary to 100, secondary to 50), properties,
        ClockPort(Instant::now), registry).use { providers ->
        withContext(MailDeliveryContext(purpose = "RECOVERY", correlationId = correlation)) {
          assertIs<MailDeliveryResult.Accepted>(providers.transport.send(message))
        }
      }
      val traces = logs.list.filter { it.message == "mail.delivery.attempt" }
      assertEquals(3, traces.size)
      val fields = traces.map { event -> event.keyValuePairs.associate { it.key to it.value } }
      assertEquals(listOf("smtp", "smtp", "ses"), fields.map { it["provider"] })
      assertEquals(listOf(1, 2, 1), fields.map { it["attempt"] })
      assertEquals(listOf("RETRYABLE_REJECTED", "RETRYABLE_REJECTED", "ACCEPTED"), fields.map { it["result"] })
      fields.forEach {
        assertEquals(message.id.toString(), it["message_id"])
        assertEquals(correlation.toString(), it["correlation_id"])
        assertEquals("RECOVERY", it["purpose"])
        assertTrue((it["latency_ns"] as Long) >= 0)
        assertEquals(setOf("message_id", "provider", "result", "latency_ns", "attempt", "purpose", "correlation_id"), it.keys)
      }
      assertEquals(listOf(message.id, message.id), primary.messages().map { it.id })
      assertEquals(listOf(message.id), secondary.messages().map { it.id })
      assertEquals(2.0, registry.get("finds.mail.attempts").tag("provider", "smtp").counter().count())
      val rawFailure = IllegalStateException("private-exception credentials private-password")
      val throwing = object : MailTransport {
        override val provider = smtp
        override suspend fun send(message: MailMessage): MailDeliveryResult = throw rawFailure
      }
      MailConfiguration().composeProviders(listOf(throwing to 100), properties, ClockPort(Instant::now), registry).use {
        assertFailsWith<IllegalStateException> { it.transport.send(message) }
      }
      assertEquals(3, logs.list.count { it.message == "mail.delivery.attempt" })
      val output = logs.list.joinToString { "${it.formattedMessage} ${it.keyValuePairs} ${it.argumentArray?.toList()} ${it.throwableProxy?.message}" }
      listOf("private-sender", "private-recipient", "private-subject", "private-body", "19384217",
        "private-html", "private-provider-receipt", "private-exception", "private-password").forEach {
        assertFalse(output.contains(it), "Sensitive marker escaped to production logs: $it")
      }
      assertTrue(traces.all { it.throwableProxy == null })
    } finally {
      root.detachAppender(logs)
      logs.stop()
    }
  }

  @Test
  fun `production composition emits safe structured trace fields`() {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val logs = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(logs)
    try {
      runner().withPropertyValues("spring.profiles.active=test", "finds.mail.recording=true").run { context ->
        assertNull(context.startupFailure)
        val message = MailMessage(MailMessageId.new(), Mailbox("private-sender@example.test"),
          Recipients(to = listOf(Mailbox("private-recipient@example.test"))), "private-subject",
          MailContent(text = "private-body OTP 19384217"))
        val correlation = UUID.randomUUID()
        runBlocking {
          withContext(MailDeliveryContext(purpose = "ENROLLMENT", correlationId = correlation)) {
            assertIs<MailDeliveryResult.Accepted>(context.getBean(MailTransport::class.java).send(message))
          }
        }
        val trace = logs.list.single { it.message == "mail.delivery.attempt" }
        val fields = trace.keyValuePairs.associate { it.key to it.value }
        assertEquals(message.id.toString(), fields["message_id"])
        assertEquals(correlation.toString(), fields["correlation_id"])
        assertEquals("ENROLLMENT", fields["purpose"])
        assertEquals(1, fields["attempt"])
        assertEquals("recording", fields["provider"])
        assertEquals("ACCEPTED", fields["result"])
        assertNull(trace.throwableProxy)
        val output = logs.list.joinToString { "${it.formattedMessage} ${it.keyValuePairs} ${it.argumentArray?.toList()}" }
        listOf("private-sender", "private-recipient", "private-subject", "private-body", "19384217").forEach {
          assertFalse(output.contains(it))
        }
      }
    } finally {
      root.detachAppender(logs)
      logs.stop()
    }
  }

  @Test
  fun `startup fails closed without production transport and key`() {
    for (properties in listOf(emptyArray(), arrayOf("finds.mail.encryption-key=$key"),
      arrayOf("finds.mail.smtp.enabled=true", "finds.mail.smtp.host=localhost"))) {
      runner().withPropertyValues(*properties).run { context ->
        assertNotNull(context.startupFailure)
      }
    }
  }

  @Test
  fun `recording requires explicit development or test profile and cannot override production`() {
    for (profile in listOf("production", "dev,production", "prod", "dev,prod", "")) {
      runner().withPropertyValues("spring.profiles.active=$profile", "finds.mail.recording=true")
        .run { context -> assertNotNull(context.startupFailure) }
    }
    runner().withPropertyValues("spring.profiles.active=dev", "finds.mail.recording=true").run { context ->
      assertNull(context.startupFailure)
      assertNotNull(context.getBean(MailPayloadCrypto::class.java))
      assertNotNull(context.getBean(MailTransport::class.java))
    }
  }

  @Test
  fun `production starts with loaded active and retained key versions and real transport`() {
    runner().withPropertyValues("spring.profiles.active=production", "finds.mail.smtp.enabled=true",
      "finds.mail.smtp.host=smtp.example.test", "finds.mail.encryption-key=$key",
      "finds.mail.active-key-version=2", "finds.mail.retained-keys[1]=$key").run { context ->
      assertNull(context.startupFailure)
      assertNotNull(context.getBean(MailTransport::class.java))
      assertNotNull(context.getBean(MailPayloadCrypto::class.java))
    }
  }

  @Test
  fun `startup rejects bad keys sender provider configuration and retry bounds`() {
    val invalid = listOf("finds.mail.encryption-key=private-secret-value", "finds.mail.active-key-version=0",
      "finds.mail.sender=bad-address", "finds.mail.smtp.port=0", "finds.mail.smtp.host=",
      "finds.mail.retry.max-attempts=0", "finds.mail.retry.max-attempts=1000",
      "finds.mail.retry.initial-delay=-1s", "finds.mail.retry.maximum-delay=0s",
      "finds.mail.retry.jitter-ratio=2", "finds.mail.scan-interval=0s",
      "finds.mail.dispatch.batch-size=0", "finds.mail.dispatch.send-timeout=0s",
      "finds.mail.dispatch.lease-duration=1s", "finds.mail.dispatch.max-attempts=0",
      "finds.mail.smtp.username=only-user", "finds.mail.ses.enabled=true")
    for (bad in invalid) {
      runner().withPropertyValues("spring.profiles.active=production", "finds.mail.smtp.enabled=true",
        "finds.mail.smtp.host=smtp.example.test", "finds.mail.encryption-key=$key", bad).run { context ->
        assertNotNull(context.startupFailure, bad)
      }
    }
  }

  @Test
  fun `key diagnostics redact values and rotation cannot shadow active version`() {
    val properties = MailProperties(encryptionKey = "private-secret-value", retainedKeys = mapOf(1 to key))
    assertFalse(properties.toString().contains("private-secret-value"))
    val failure = assertFailsWith<IllegalArgumentException> { properties.loadedKeys(setOf("production")) }
    assertFalse(failure.toString().contains("private-secret-value"))
    assertFailsWith<IllegalArgumentException> { MailProperties(encryptionKey = key,
      retainedKeys = mapOf(1 to key)).loadedKeys(setOf("production")) }
  }

  private fun runner() = ApplicationContextRunner().withUserConfiguration(MailConfiguration::class.java, Dependencies::class.java)

  @TestConfiguration(proxyBeanMethods = false)
  class Dependencies {
    @Bean fun clock() = ClockPort(Instant::now)
    @Bean fun registry() = SimpleMeterRegistry()
  }
}
