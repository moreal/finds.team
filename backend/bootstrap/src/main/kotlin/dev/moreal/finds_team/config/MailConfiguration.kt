package dev.moreal.finds_team.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.MailPayloadCrypto
import dev.moreal.finds.persistence.AesGcmMailPayloadCrypto
import dev.moreal.finds.notification.ExpiringMailTransport
import dev.moreal.mail.*
import dev.moreal.mail.smtp.SmtpMailTransport
import dev.moreal.mail.ses.SesMailTransport
import dev.moreal.mail.ses.SesSettings
import dev.moreal.mail.testing.RecordingMailTransport
import dev.moreal.mail.retry.RetryMailTransport
import dev.moreal.mail.pool.PriorityMailTransport
import dev.moreal.mail.observability.*
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailProperties::class)
class MailConfiguration {
  @Bean
  fun mailPayloadCrypto(properties: MailProperties, environment: Environment): MailPayloadCrypto {
    val profiles = environment.activeProfiles.toSet()
    properties.validate(profiles)
    val keys = properties.loadedKeys(profiles)
    return try { AesGcmMailPayloadCrypto(properties.activeKeyVersion, keys) } finally { keys.values.forEach { it.fill(0) } }
  }

  @Bean(destroyMethod = "close")
  fun mailProviders(properties: MailProperties, crypto: MailPayloadCrypto,
    clock: ClockPort, registry: MeterRegistry): MailProviders {
    // Crypto is a required dependency so keys/configuration are validated before opening a provider.
    val entries = mutableListOf<Pair<MailTransport, Int>>()
    if (properties.smtp.enabled) entries += SmtpMailTransport(properties.smtpSettings()) to properties.smtp.priority
    if (properties.ses.enabled) entries += SesMailTransport(SesSettings(properties.ses.region,
      properties.ses.configurationSet)) to properties.ses.priority
    if (properties.recording) entries += RecordingMailTransport(MailProvider("recording")) to 0
    val sink = object : MailObservationSink {
      override fun metric(metric: MailAttemptMetric) {
        registry.counter("finds.mail.attempts", "provider", metric.provider.value, "result", metric.result.name).increment()
        registry.timer("finds.mail.attempt.latency", "provider", metric.provider.value)
          .record(metric.latencyNanos, TimeUnit.NANOSECONDS)
      }
      override fun trace(trace: MailAttemptTrace) = Unit // No recipient, OTP, rendered body or provider exception logging.
    }
    val pool = PriorityMailTransport(entries.map { (provider, priority) ->
      PriorityMailTransport.Entry(RetryMailTransport(ObservedMailTransport(
        ExpiringMailTransport(provider, clock), sink), properties.retryPolicy()), priority)
    })
    return MailProviders(pool, entries.map { it.first })
  }

  @Bean
  fun mailTransport(providers: MailProviders): MailTransport = providers.transport
}

class MailProviders(val transport: MailTransport, private val providers: List<MailTransport>) : AutoCloseable {
  override fun close() { providers.filterIsInstance<AutoCloseable>().forEach { it.close() } }
}
