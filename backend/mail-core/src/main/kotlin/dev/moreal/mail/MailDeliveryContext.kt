package dev.moreal.mail

import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Optional metadata. Purpose must be a non-sensitive application operation name, never message content. */
data class MailDeliveryContext(
  val attempt: Int = 1,
  val purpose: String? = null,
  val correlationId: UUID? = null,
) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<MailDeliveryContext>
}
