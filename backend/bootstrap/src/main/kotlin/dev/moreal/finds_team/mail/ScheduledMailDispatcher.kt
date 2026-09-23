package dev.moreal.finds_team.mail

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.atomic.AtomicBoolean

class ScheduledMailDispatcher(
  private val dispatch: suspend () -> Int,
  private val scope: CoroutineScope,
  private val registry: MeterRegistry,
) {
  private val scanning = AtomicBoolean(false)

  @Scheduled(initialDelayString = "\${finds.mail.scan-interval:5s}", fixedDelayString = "\${finds.mail.scan-interval:5s}")
  fun scan(): Job? {
    if (!scanning.compareAndSet(false, true)) return null
    return scope.launch {
      try {
        dispatch()
        registry.counter("finds.mail.scans", "outcome", "completed").increment()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // Do not log exceptions: provider and payload errors can carry recipients or OTPs.
        registry.counter("finds.mail.scans", "outcome", "failed").increment()
      }
    }.also { job -> job.invokeOnCompletion { scanning.set(false) } }
  }
}
