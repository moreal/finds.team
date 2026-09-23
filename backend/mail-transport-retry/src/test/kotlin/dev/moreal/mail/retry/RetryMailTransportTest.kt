package dev.moreal.mail.retry

import dev.moreal.mail.*
import dev.moreal.mail.testing.ScriptedMailTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RetryMailTransportTest {
  private val provider = MailProvider("smtp")
  private val accepted = MailDeliveryResult.Accepted(provider, "receipt")
  private val retryable = MailDeliveryResult.Rejected(provider, MailFailure.THROTTLED, true)
  private val permanent = MailDeliveryResult.Rejected(provider, MailFailure.INVALID_RECIPIENT, false)
  private val indeterminate = MailDeliveryResult.Indeterminate(provider, MailFailure.TIMEOUT)
  private val message = MailMessage(MailMessageId.new(), Mailbox("from@example.com"),
    Recipients(to = listOf(Mailbox("to@example.com"))), "Subject", MailContent(text = "Body"))

  @Test
  fun `complete result matrix preserves identity and stops at terminal outcomes`() = runBlocking {
    data class Case(val results: List<MailDeliveryResult>, val expected: MailDeliveryResult, val attempts: Int)
    val cases = listOf(
      Case(listOf(accepted), accepted, 1),
      Case(listOf(retryable, accepted), accepted, 2),
      Case(listOf(permanent, accepted), permanent, 1),
      Case(listOf(indeterminate, accepted), indeterminate, 1),
      Case(listOf(retryable, permanent, accepted), permanent, 2),
      Case(listOf(retryable, indeterminate, accepted), indeterminate, 2),
      Case(listOf(retryable, retryable, retryable, accepted), retryable, 3),
    )
    for (case in cases) {
      val child = ScriptedMailTransport(provider, case.results)
      val delays = mutableListOf<Duration>()
      val retry = RetryMailTransport(child, RetryPolicy(3, 10.milliseconds, 100.milliseconds, 0.0),
        delay = { delays += it }, random = { error("No jitter requested") })
      assertEquals(case.expected, retry.send(message))
      assertEquals(provider, retry.provider)
      assertEquals(List(case.attempts) { message }, child.messages())
      child.messages().forEach { assertSame(message, it) }
      assertEquals(listOf(10.milliseconds, 20.milliseconds).take(case.attempts - 1), delays)
    }
  }

  @Test
  fun `backoff grows exponentially with bounded jitter and maximum delay`() = runBlocking {
    val child = ScriptedMailTransport(provider, List(5) { retryable } + accepted)
    val delays = mutableListOf<Duration>()
    val random = ArrayDeque(listOf(0.0, 0.5, 1.0, 1.0, 0.0))
    val transport = RetryMailTransport(child, RetryPolicy(6, 10.milliseconds, 30.milliseconds, 0.5),
      delay = { delays += it }, random = { random.removeFirst() })
    assertEquals(accepted, transport.send(message))
    assertEquals(listOf(5, 20, 30, 30, 15).map { it.milliseconds }, delays)
    assertEquals(List(6) { message.id }, child.messages().map { it.id })
  }

  @Test
  fun `single allowed attempt never waits`() = runBlocking {
    val child = ScriptedMailTransport(provider, listOf(retryable, accepted))
    assertEquals(retryable, RetryMailTransport(child, RetryPolicy(1), delay = { error("Unexpected delay") }).send(message))
    assertEquals(1, child.messages().size)
  }

  @Test
  fun `unclassified exceptions and cancellation propagate without another attempt`() = runBlocking {
    for (failure in listOf(IOException("timeout"), CancellationException("cancelled"))) {
      var calls = 0
      val child = object : MailTransport {
        override val provider = this@RetryMailTransportTest.provider
        override suspend fun send(message: MailMessage): MailDeliveryResult { calls++; throw failure }
      }
      val caught = assertFailsWith<Exception> {
        RetryMailTransport(child, RetryPolicy(3), delay = { error("Unexpected delay") }).send(message)
      }
      // Coroutine stack-trace recovery may copy an exception crossing withContext.
      assertEquals(failure::class, caught::class)
      assertEquals(failure.message, caught.message)
      assertEquals(1, calls)
    }
  }

  @Test
  fun `cancellation during backoff stops delivery and restores caller context`() = runBlocking {
    val child = ScriptedMailTransport(provider, listOf(retryable, accepted))
    val cancellation = CancellationException("cancelled")
    val caller = MailDeliveryContext(purpose = "verification", correlationId = java.util.UUID.randomUUID())
    kotlinx.coroutines.withContext(caller) {
      assertSame(cancellation, assertFailsWith<CancellationException> {
        RetryMailTransport(child, RetryPolicy(3), delay = { throw cancellation }).send(message)
      })
      assertSame(caller, kotlin.coroutines.coroutineContext[MailDeliveryContext])
    }
    assertEquals(1, child.messages().size)
  }

  @Test
  fun `invalid retry policies are rejected`() {
    assertFailsWith<IllegalArgumentException> { RetryPolicy(0) }
    assertFailsWith<IllegalArgumentException> { RetryPolicy(2, (-1).milliseconds) }
    assertFailsWith<IllegalArgumentException> { RetryPolicy(2, 20.milliseconds, 10.milliseconds) }
    assertFailsWith<IllegalArgumentException> { RetryPolicy(2, jitterRatio = Double.NaN) }
    assertFailsWith<IllegalArgumentException> { RetryPolicy(2, jitterRatio = 1.1) }
    assertFailsWith<IllegalArgumentException> { RetryPolicy(2, maximumDelay = Duration.INFINITE) }
  }
}
