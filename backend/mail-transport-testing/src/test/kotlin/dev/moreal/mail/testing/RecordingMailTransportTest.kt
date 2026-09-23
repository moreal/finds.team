package dev.moreal.mail.testing

import dev.moreal.mail.MailContent
import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailFailure
import dev.moreal.mail.MailMessage
import dev.moreal.mail.MailMessageId
import dev.moreal.mail.MailProvider
import dev.moreal.mail.Mailbox
import dev.moreal.mail.Recipients
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecordingMailTransportTest {
  private val provider = MailProvider("test")

  @Test
  fun `recording transport retains every concurrent message exactly once with original id`() = runBlocking {
    val transport = RecordingMailTransport(provider)
    val sent = (1..100).map(::message)

    val results = coroutineScope {
      sent.map { mail -> async(Dispatchers.Default) { transport.send(mail) } }.awaitAll()
    }

    assertEquals(100, results.size)
    results.forEachIndexed { index, result ->
      val accepted = assertIs<MailDeliveryResult.Accepted>(result)
      assertEquals(provider, accepted.provider)
      assertEquals(sent[index].id.toString(), accepted.providerMessageId)
    }
    val recorded = transport.messages()
    assertEquals(100, recorded.size)
    assertEquals(sent.map { it.id }.toSet(), recorded.map { it.id }.toSet())
    assertEquals(100, recorded.map { it.id }.distinct().size)
  }

  @Test
  fun `recording snapshot cannot modify stored messages and stays unchanged after later sends`() = runBlocking {
    val transport = RecordingMailTransport(provider)
    transport.send(message(1))
    val snapshot = transport.messages()

    assertFailsWith<UnsupportedOperationException> {
      (snapshot as MutableList<MailMessage>).clear()
    }
    transport.send(message(2))

    assertEquals(listOf(message(1)), snapshot)
    assertEquals(listOf(message(1), message(2)), transport.messages())
  }

  @Test
  fun `scripted transport returns outcomes in order and preserves message identity`() = runBlocking {
    val first = MailDeliveryResult.Rejected(provider, MailFailure.THROTTLED, retryable = true)
    val second = MailDeliveryResult.Accepted(provider, "provider-receipt")
    val script = mutableListOf<MailDeliveryResult>(first, second)
    val transport = ScriptedMailTransport(provider, script)
    script.clear()
    val firstMessage = message(1)
    val secondMessage = message(2)

    assertEquals(first, transport.send(firstMessage))
    assertEquals(second, transport.send(secondMessage))
    assertEquals(listOf(firstMessage, secondMessage), transport.messages())
  }

  @Test
  fun `scripted transport has deterministic exhaustion and does not record unsent message`() = runBlocking {
    val transport = ScriptedMailTransport(provider, listOf(MailDeliveryResult.Accepted(provider, "receipt")))
    transport.send(message(1))

    val error = assertFailsWith<IllegalStateException> { transport.send(message(2)) }

    assertTrue(error.message.orEmpty().contains("exhausted"))
    assertEquals(listOf(message(1)), transport.messages())
  }

  @Test
  fun `scripted snapshot cannot modify stored messages`() = runBlocking {
    val outcomes = listOf(
      MailDeliveryResult.Accepted(provider, "first"),
      MailDeliveryResult.Accepted(provider, "second"),
    )
    val transport = ScriptedMailTransport(provider, outcomes)
    transport.send(message(1))
    val snapshot = transport.messages()

    assertFailsWith<UnsupportedOperationException> {
      (snapshot as MutableList<MailMessage>).clear()
    }
    transport.send(message(2))

    assertEquals(listOf(message(1)), snapshot)
    assertEquals(listOf(message(1), message(2)), transport.messages())
  }

  private fun message(number: Int): MailMessage = MailMessage(
    id = MailMessageId(UUID(0, number.toLong())),
    from = Mailbox("sender@example.com"),
    recipients = Recipients(to = listOf(Mailbox("recipient@example.com"))),
    subject = "Message $number",
    content = MailContent(text = "Body $number"),
  )
}
