package dev.moreal.mail.pool

import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailMessage
import dev.moreal.mail.MailProvider
import dev.moreal.mail.MailTransport

/** Wrap each entry in its provider-local retry decorator before placing it in the pool. */
class PriorityMailTransport(entries: List<Entry>) : MailTransport {
  data class Entry(val transport: MailTransport, val priority: Int)
  private val entries = entries.sortedByDescending { it.priority }

  init { require(this.entries.isNotEmpty()) { "At least one mail provider is required" } }

  override val provider = MailProvider("priority-pool")

  override suspend fun send(message: MailMessage): MailDeliveryResult {
    var last: MailDeliveryResult? = null
    for (entry in entries) {
      val result = entry.transport.send(message)
      if (result !is MailDeliveryResult.Rejected || !result.retryable) return result
      last = result
    }
    return checkNotNull(last)
  }
}
