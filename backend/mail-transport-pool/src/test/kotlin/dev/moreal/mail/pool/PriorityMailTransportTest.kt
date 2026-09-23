package dev.moreal.mail.pool

import dev.moreal.mail.*
import dev.moreal.mail.retry.RetryMailTransport
import dev.moreal.mail.retry.RetryPolicy
import dev.moreal.mail.testing.ScriptedMailTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PriorityMailTransportTest {
  private val smtp = MailProvider("smtp")
  private val ses = MailProvider("ses")
  private val message = MailMessage(MailMessageId.new(), Mailbox("from@example.com"),
    Recipients(to = listOf(Mailbox("to@example.com"))), "Subject", MailContent(text = "Body"))

  @Test
  fun `priority fallback waits for local retry exhaustion and preserves identity`() = runBlocking {
    val rejected = MailDeliveryResult.Rejected(smtp, MailFailure.NETWORK, true)
    val first = ScriptedMailTransport(smtp, listOf(rejected, rejected, rejected))
    val accepted = MailDeliveryResult.Accepted(ses, "receipt")
    val second = ScriptedMailTransport(ses, listOf(accepted))
    val pool = PriorityMailTransport(listOf(
      PriorityMailTransport.Entry(second, 1),
      PriorityMailTransport.Entry(RetryMailTransport(first, RetryPolicy(3), delay = {
        assertTrue(second.messages().isEmpty())
      }), 10),
    ))
    assertEquals(accepted, pool.send(message))
    assertEquals(3, first.messages().size)
    assertEquals(1, second.messages().size)
    (first.messages() + second.messages()).forEach { assertSame(message, it) }
  }

  @Test
  fun `accepted permanent and indeterminate results forbid fallback`() = runBlocking {
    for (result in listOf(
      MailDeliveryResult.Accepted(smtp, "receipt"),
      MailDeliveryResult.Rejected(smtp, MailFailure.INVALID_RECIPIENT, false),
      MailDeliveryResult.Indeterminate(smtp, MailFailure.TIMEOUT),
    )) {
      val first = ScriptedMailTransport(smtp, listOf(result))
      val second = ScriptedMailTransport(ses, emptyList())
      assertEquals(result, PriorityMailTransport(listOf(
        PriorityMailTransport.Entry(second, 0), PriorityMailTransport.Entry(first, 1),
      )).send(message))
      assertTrue(second.messages().isEmpty())
    }
  }

  @Test
  fun `all exhausted providers return final rejection and ties preserve declaration order`() = runBlocking {
    val firstResult = MailDeliveryResult.Rejected(smtp, MailFailure.THROTTLED, true)
    val lastResult = MailDeliveryResult.Rejected(ses, MailFailure.SERVICE_UNAVAILABLE, true)
    val first = ScriptedMailTransport(smtp, listOf(firstResult))
    val second = ScriptedMailTransport(ses, listOf(lastResult))
    val entries = mutableListOf(PriorityMailTransport.Entry(first, 1), PriorityMailTransport.Entry(second, 1))
    val pool = PriorityMailTransport(entries)
    entries.clear()
    assertEquals(lastResult, pool.send(message))
    assertEquals(listOf(message), first.messages())
    assertEquals(listOf(message), second.messages())
  }

  @Test
  fun `unclassified exceptions and cancellation never trigger fallback`() = runBlocking {
    for (failure in listOf(IOException("timeout"), CancellationException("cancelled"))) {
      val first = object : MailTransport {
        override val provider = smtp
        override suspend fun send(message: MailMessage): MailDeliveryResult = throw failure
      }
      val second = ScriptedMailTransport(ses, emptyList())
      val pool = PriorityMailTransport(listOf(PriorityMailTransport.Entry(first, 1), PriorityMailTransport.Entry(second, 0)))
      assertSame(failure, assertFailsWith<Exception> { pool.send(message) })
      assertTrue(second.messages().isEmpty())
    }
  }

  @Test
  fun `empty pool is rejected`() {
    assertFailsWith<IllegalArgumentException> { PriorityMailTransport(emptyList()) }
  }
}
