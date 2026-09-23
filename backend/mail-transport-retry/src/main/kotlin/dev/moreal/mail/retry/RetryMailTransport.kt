package dev.moreal.mail.retry

import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailDeliveryContext
import dev.moreal.mail.MailMessage
import dev.moreal.mail.MailTransport
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class RetryPolicy(
  val maxAttempts: Int,
  val initialDelay: Duration = 100.milliseconds,
  val maximumDelay: Duration = 10.seconds,
  val jitterRatio: Double = 0.2,
) {
  init {
    require(maxAttempts >= 1) { "At least one mail attempt is required" }
    require(initialDelay.isFinite() && initialDelay >= Duration.ZERO) { "Invalid initial mail retry delay" }
    require(maximumDelay.isFinite() && maximumDelay >= initialDelay) { "Invalid maximum mail retry delay" }
    require(jitterRatio in 0.0..1.0) { "Invalid mail retry jitter ratio" }
  }
}

/** Retries only explicit rejections. Provider adapters must classify their own exceptions. */
class RetryMailTransport(
  private val child: MailTransport,
  private val policy: RetryPolicy,
  private val delay: suspend (Duration) -> Unit = { kotlinx.coroutines.delay(it) },
  private val random: () -> Double = { Random.nextDouble() },
) : MailTransport {
  override val provider get() = child.provider

  override suspend fun send(message: MailMessage): MailDeliveryResult {
    val context = coroutineContext[MailDeliveryContext] ?: MailDeliveryContext()
    var backoff = policy.initialDelay
    for (attempt in 1..policy.maxAttempts) {
      val result = withContext(context.copy(attempt = attempt)) { child.send(message) }
      if (result !is MailDeliveryResult.Rejected || !result.retryable || attempt == policy.maxAttempts) {
        return result
      }
      val factor = if (policy.jitterRatio == 0.0) 1.0 else {
        val sample = random()
        require(sample in 0.0..1.0) { "Invalid mail retry random sample" }
        1.0 + (sample * 2.0 - 1.0) * policy.jitterRatio
      }
      delay((backoff * factor).coerceAtMost(policy.maximumDelay))
      backoff = (backoff * 2).coerceAtMost(policy.maximumDelay)
    }
    error("Unreachable mail retry state")
  }
}
