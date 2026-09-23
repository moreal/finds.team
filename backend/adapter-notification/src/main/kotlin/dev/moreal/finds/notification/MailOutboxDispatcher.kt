package dev.moreal.finds.notification

import dev.moreal.finds.application.port.*
import dev.moreal.mail.*
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.toKotlinDuration

data class MailDispatchPolicy(
  val owner: String = "mail-worker",
  val batchSize: Int = 20,
  val leaseDuration: Duration = Duration.ofMinutes(2),
  val sendTimeout: Duration = Duration.ofSeconds(30),
  val maxAttempts: Int = 3,
  val retryDelays: List<Duration> = listOf(Duration.ofSeconds(10), Duration.ofSeconds(30)),
) {
  init {
    require(owner.isNotBlank() && owner.length <= 128 && owner.none(Char::isISOControl)) { "Invalid mail lease owner" }
    require(batchSize in 1..1000 && maxAttempts in 1..20) { "Invalid mail dispatch bounds" }
    require(sendTimeout >= Duration.ofMillis(1) && sendTimeout <= Duration.ofMinutes(5) &&
      leaseDuration >= sendTimeout.plusSeconds(5) && leaseDuration <= Duration.ofHours(1)) { "Invalid mail time bounds" }
    require(retryDelays.isNotEmpty() && retryDelays.all { it >= Duration.ofMillis(1) && it <= Duration.ofHours(1) } &&
      retryDelays.zipWithNext().all { (a, b) -> a <= b }) { "Invalid outbox retry delays" }
  }
}

enum class DispatchOutcome { ACCEPTED, RETRY_SCHEDULED, FAILED, INDETERMINATE, EXPIRED, LEASE_LOST }

fun interface MailDispatchMetrics {
  fun record(outcome: DispatchOutcome)
  fun queueAge(age: Duration) = Unit
}

class MailOutboxDispatcher(
  private val outbox: MailOutbox,
  private val crypto: MailPayloadCrypto,
  private val transport: MailTransport,
  private val sender: Mailbox,
  private val clock: ClockPort,
  private val policy: MailDispatchPolicy,
  private val metrics: MailDispatchMetrics = MailDispatchMetrics {},
) {
  suspend fun dispatch(): Int {
    outbox.redactExpired(clock.now())
    var dispatched = 0
    repeat(policy.batchSize) {
      // Acquire each lease immediately before use. Slow prior sends must not age an unsent lease
      // into recovery, where we can no longer distinguish it from a possibly accepted message.
      val lease = outbox.leaseBatch(policy.owner, clock.now(), policy.leaseDuration, 1).firstOrNull()
        ?: return dispatched
      dispatch(lease)
      dispatched++
    }
    return dispatched
  }

  private suspend fun dispatch(lease: MailOutboxLease) {
    try {
      metrics.queueAge(Duration.between(lease.createdAt, clock.now()).coerceAtLeast(Duration.ZERO))
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
    if (!canSend(lease)) return
    if (lease.recovered) {
      // Neither SMTP Message-ID nor SES tags guarantee deduplication. A crash can follow acceptance.
      finish(lease, MailDeliveryResult.Indeterminate(MailProvider("outbox-recovery"), MailFailure.UNKNOWN))
      return
    }
    val message = try {
      // leaseBatch has committed before this point; plaintext and provider I/O never enter its transaction.
      val plaintext = crypto.decrypt(lease.metadata, lease.payload)
      try { VerificationMail.render(lease.metadata, plaintext, sender) } finally { plaintext.fill(0) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      finish(lease, MailDeliveryResult.Rejected(MailProvider("outbox-payload"), MailFailure.INVALID_MESSAGE, false))
      return
    }
    if (!canSend(lease)) return
    val deadline = minOf(lease.leaseExpiresAt, lease.metadata.expiresAt, clock.now().plus(policy.sendTimeout))
    val result = try {
      withContext(MailDeliveryContext(purpose = lease.metadata.purpose,
        correlationId = lease.metadata.correlationId) + MailSendDeadline(deadline)) {
        withTimeoutOrNull(Duration.between(clock.now(), deadline).toKotlinDuration()) {
          transport.send(message)
        } ?: MailDeliveryResult.Indeterminate(transport.provider, MailFailure.TIMEOUT)
      }
    } catch (cancelled: CancellationException) {
      // Leave the lease fenced for recovery; cancellation may follow provider acceptance.
      throw cancelled
    } catch (_: Exception) {
      MailDeliveryResult.Indeterminate(transport.provider, MailFailure.UNKNOWN)
    }
    finish(lease, result)
  }

  private fun canSend(lease: MailOutboxLease): Boolean {
    val now = clock.now()
    if (now >= lease.metadata.expiresAt) {
      outbox.redactExpired(now)
      observe(DispatchOutcome.EXPIRED)
      return false
    }
    if (now >= lease.leaseExpiresAt) {
      observe(DispatchOutcome.LEASE_LOST)
      return false
    }
    return true
  }

  private fun finish(lease: MailOutboxLease, result: MailDeliveryResult) {
    if (!canSend(lease)) return
    val now = clock.now()
    val attempt = outbox.recordAttempt(lease, result, now)
    if (attempt == null) { observe(DispatchOutcome.LEASE_LOST); return }
    val outcome: DispatchOutcome
    val saved: Boolean
    if (result is MailDeliveryResult.Rejected && result.retryable) {
      val next = now.plus(policy.retryDelays[(attempt - 1).coerceAtMost(policy.retryDelays.lastIndex)])
      if (attempt < policy.maxAttempts && next < lease.metadata.expiresAt) {
        saved = outbox.reschedule(lease, next, clock.now())
        outcome = DispatchOutcome.RETRY_SCHEDULED
      } else {
        saved = outbox.complete(lease, result.copy(retryable = false), clock.now())
        outcome = DispatchOutcome.FAILED
      }
    } else {
      saved = outbox.complete(lease, result, clock.now())
      outcome = when (result) {
        is MailDeliveryResult.Accepted -> DispatchOutcome.ACCEPTED
        is MailDeliveryResult.Indeterminate -> DispatchOutcome.INDETERMINATE
        is MailDeliveryResult.Rejected -> DispatchOutcome.FAILED
      }
    }
    observe(if (saved) outcome else DispatchOutcome.LEASE_LOST)
  }

  private fun observe(outcome: DispatchOutcome) {
    try { metrics.record(outcome) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
  }
}
