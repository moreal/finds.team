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

class MailConfigurationTest {
  private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

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
