package dev.moreal.mail.testing

import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailMessage
import dev.moreal.mail.MailProvider
import dev.moreal.mail.MailTransport
import java.util.Collections
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecordingMailTransport(override val provider: MailProvider) : MailTransport {
  private val mutex = Mutex()
  private val recorded = mutableListOf<MailMessage>()

  override suspend fun send(message: MailMessage): MailDeliveryResult = mutex.withLock {
    recorded += message
    MailDeliveryResult.Accepted(provider, message.id.toString())
  }

  suspend fun messages(): List<MailMessage> = mutex.withLock {
    Collections.unmodifiableList(ArrayList(recorded))
  }
}
