package dev.moreal.mail.observability

import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailDeliveryContext
import dev.moreal.mail.MailMessage
import dev.moreal.mail.MailMessageId
import dev.moreal.mail.MailProvider
import dev.moreal.mail.MailTransport
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext

enum class MailResultClass { ACCEPTED, RETRYABLE_REJECTED, PERMANENT_REJECTED, INDETERMINATE }

data class MailAttemptMetric(val provider: MailProvider, val result: MailResultClass, val latencyNanos: Long)

data class MailAttemptTrace(
  val messageId: MailMessageId,
  val provider: MailProvider,
  val result: MailResultClass,
  val latencyNanos: Long,
  val attempt: Int,
  val purpose: String?,
  val correlationId: UUID?,
)

/** Connect to metrics and structured logs/spans at composition time; payloads contain no mail content. */
interface MailObservationSink {
  fun metric(metric: MailAttemptMetric)
  fun trace(trace: MailAttemptTrace)
}

/** Place inside provider-local RetryMailTransport to observe each attempt independently. */
class ObservedMailTransport(
  private val child: MailTransport,
  private val sink: MailObservationSink,
  private val nanoTime: () -> Long = System::nanoTime,
) : MailTransport {
  override val provider get() = child.provider

  override suspend fun send(message: MailMessage): MailDeliveryResult {
    val context = coroutineContext[MailDeliveryContext] ?: MailDeliveryContext()
    val started = nanoTime()
    // Exceptions remain unclassified and propagate; never log their messages or provider responses.
    val result = child.send(message)
    val latency = (nanoTime() - started).coerceAtLeast(0L)
    val resultClass = when (result) {
      is MailDeliveryResult.Accepted -> MailResultClass.ACCEPTED
      is MailDeliveryResult.Indeterminate -> MailResultClass.INDETERMINATE
      is MailDeliveryResult.Rejected -> if (result.retryable) MailResultClass.RETRYABLE_REJECTED else MailResultClass.PERMANENT_REJECTED
    }
    safelyRecord { sink.metric(MailAttemptMetric(result.provider, resultClass, latency)) }
    safelyRecord {
      sink.trace(MailAttemptTrace(message.id, result.provider, resultClass, latency,
        context.attempt, context.purpose, context.correlationId))
    }
    return result
  }

  private inline fun safelyRecord(record: () -> Unit) {
    try {
      record()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      // A telemetry outage must not turn provider acceptance into a failed send and duplicate delivery.
    }
  }
}
