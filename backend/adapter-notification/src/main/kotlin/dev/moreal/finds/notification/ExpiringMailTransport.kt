package dev.moreal.finds.notification

import dev.moreal.finds.application.port.ClockPort
import dev.moreal.mail.*
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class MailSendDeadline(val at: Instant) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<MailSendDeadline>
}

/** Place inside each provider's Retry so expiry is rechecked on every retry and fallback. */
class ExpiringMailTransport(private val child: MailTransport, private val clock: ClockPort) : MailTransport {
  override val provider get() = child.provider
  override suspend fun send(message: MailMessage): MailDeliveryResult {
    val deadline = kotlin.coroutines.coroutineContext[MailSendDeadline]
    if (deadline != null && clock.now() >= deadline.at) {
      return MailDeliveryResult.Rejected(provider, MailFailure.TIMEOUT, false)
    }
    return child.send(message)
  }
}
