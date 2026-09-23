package dev.moreal.mail.testing

import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailMessage
import dev.moreal.mail.MailProvider
import dev.moreal.mail.MailTransport
import java.util.Collections
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ScriptedMailTransport(
  override val provider: MailProvider,
  results: List<MailDeliveryResult>,
) : MailTransport {
  private val mutex = Mutex()
  private val queuedResults = ArrayDeque(results)
  private val recorded = mutableListOf<MailMessage>()

  override suspend fun send(message: MailMessage): MailDeliveryResult = mutex.withLock {
    check(queuedResults.isNotEmpty()) { "Scripted mail transport result queue exhausted" }
    val result = queuedResults.removeFirst()
    recorded += message
    result
  }

  suspend fun messages(): List<MailMessage> = mutex.withLock {
    Collections.unmodifiableList(ArrayList(recorded))
  }
}
