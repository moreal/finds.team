package dev.moreal.finds_team.config

import dev.moreal.finds.notification.MailDispatchPolicy
import dev.moreal.mail.smtp.SmtpTlsMode
import dev.moreal.mail.smtp.SmtpSettings
import dev.moreal.mail.ses.SesSettings
import dev.moreal.mail.Mailbox
import dev.moreal.mail.retry.RetryPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.toKotlinDuration

@ConfigurationProperties("finds.mail")
class MailProperties(
  val sender: String = "verify@finds.team",
  val senderName: String = "finds.team",
  val recording: Boolean = false,
  val encryptionKey: String = "",
  val activeKeyVersion: Int = 1,
  val retainedKeys: Map<Int, String> = emptyMap(),
  val smtp: Smtp = Smtp(),
  val ses: Ses = Ses(),
  val retry: Retry = Retry(),
  val dispatch: MailDispatchPolicy = MailDispatchPolicy(),
  val scanInterval: Duration = Duration.ofSeconds(5),
) {
  class Smtp(val enabled: Boolean = false, val priority: Int = 100, val host: String = "", val port: Int = 587,
    val tls: SmtpTlsMode = SmtpTlsMode.STARTTLS, val username: String? = null, val password: String? = null,
    val connectTimeout: Duration = Duration.ofSeconds(5), val readTimeout: Duration = Duration.ofSeconds(10))
  class Ses(val enabled: Boolean = false, val priority: Int = 50, val region: String = "",
    val configurationSet: String? = null)
  class Retry(val maxAttempts: Int = 3, val initialDelay: Duration = Duration.ofMillis(100),
    val maximumDelay: Duration = Duration.ofSeconds(1), val jitterRatio: Double = 0.2)

  fun validate(profiles: Set<String>) {
    Mailbox(sender, senderName)
    require(scanInterval >= Duration.ofMillis(100) && scanInterval <= Duration.ofHours(1)) { "Invalid mail scan interval" }
    require(!recording || development(profiles) && !smtp.enabled && !ses.enabled) {
      "Recording mail requires an exclusive explicit dev or test configuration"
    }
    require(recording || smtp.enabled || ses.enabled) { "A production mail transport must be enabled" }
    require(retry.maxAttempts in 1..10 && retry.maximumDelay <= Duration.ofMinutes(1)) { "Invalid provider retry bounds" }
    retryPolicy()
    if (smtp.enabled) smtpSettings()
    if (ses.enabled) SesSettings(ses.region, ses.configurationSet)
    if (smtp.enabled && ses.enabled) require(smtp.priority != ses.priority) { "Mail provider priorities must differ" }
  }

  fun loadedKeys(profiles: Set<String>): Map<Int, ByteArray> {
    require(activeKeyVersion > 0 && activeKeyVersion !in retainedKeys) { "Invalid active mail key version" }
    val keys = retainedKeys.mapValues { (version, value) ->
      require(version > 0) { "Invalid retained mail key version" }
      decodeKey(value)
    }.toMutableMap()
    keys[activeKeyVersion] = if (encryptionKey.isBlank()) {
      require(recording && development(profiles)) { "An external 256-bit mail encryption key is required" }
      // Explicit recording profiles only. Persist a key for dev queues that must survive restarts.
      ByteArray(32).also(SecureRandom()::nextBytes)
    } else decodeKey(encryptionKey)
    return keys
  }

  fun smtpSettings() = SmtpSettings(smtp.host, smtp.port, smtp.tls, smtp.username, smtp.password,
    smtp.connectTimeout.toKotlinDuration(), smtp.readTimeout.toKotlinDuration())

  fun retryPolicy() = RetryPolicy(retry.maxAttempts, retry.initialDelay.toKotlinDuration(),
    retry.maximumDelay.toKotlinDuration(), retry.jitterRatio)

  private fun development(profiles: Set<String>) =
    profiles.none { it == "production" || it == "prod" } && profiles.any { it == "dev" || it == "test" }

  private fun decodeKey(value: String): ByteArray {
    val bytes = try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) {
      throw IllegalArgumentException("Invalid mail encryption key encoding")
    }
    require(bytes.size == 32) { "Mail encryption keys must contain 256 bits" }
    return bytes
  }
}
