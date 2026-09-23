package dev.moreal.mail.observability

import dev.moreal.mail.*
import dev.moreal.mail.retry.RetryMailTransport
import dev.moreal.mail.retry.RetryPolicy
import dev.moreal.mail.testing.ScriptedMailTransport
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.test.*

class ObservedMailTransportTest {
  private val provider = MailProvider("smtp")
  private val message = MailMessage(MailMessageId.new(), Mailbox("secret-sender@example.com"),
    Recipients(to = listOf(Mailbox("secret-recipient@example.com"))), "secret-subject",
    MailContent(text = "secret-body OTP 193842", html = "<p>secret-html</p>"), setOf("secret-tag"))

  private class Capture : MailObservationSink {
    val metrics = mutableListOf<MailAttemptMetric>()
    val traces = mutableListOf<MailAttemptTrace>()
    override fun metric(metric: MailAttemptMetric) { metrics += metric }
    override fun trace(trace: MailAttemptTrace) { traces += trace }
  }

  @Test
  fun `records result classes latency and safe trace fields without content or provider receipts`() = runBlocking {
    val cases = listOf(
      MailDeliveryResult.Accepted(provider, "secret-provider-receipt") to MailResultClass.ACCEPTED,
      MailDeliveryResult.Rejected(provider, MailFailure.THROTTLED, true) to MailResultClass.RETRYABLE_REJECTED,
      MailDeliveryResult.Rejected(provider, MailFailure.INVALID_RECIPIENT, false) to MailResultClass.PERMANENT_REJECTED,
      MailDeliveryResult.Indeterminate(provider, MailFailure.TIMEOUT) to MailResultClass.INDETERMINATE,
    )
    for ((result, expectedClass) in cases) {
      val capture = Capture()
      val times = ArrayDeque(listOf(100L, 350L))
      val correlationId = UUID.randomUUID()
      val observer = ObservedMailTransport(ScriptedMailTransport(provider, listOf(result)), capture, nanoTime = { times.removeFirst() })
      withContext(MailDeliveryContext(attempt = 2, purpose = "verification", correlationId = correlationId)) {
        assertEquals(result, observer.send(message))
      }
      assertEquals(listOf(MailAttemptMetric(provider, expectedClass, 250L)), capture.metrics)
      assertEquals(listOf(MailAttemptTrace(message.id, provider, expectedClass, 250L, 2, "verification", correlationId)), capture.traces)
      val output = "${capture.metrics} ${capture.traces}"
      listOf("secret-sender", "secret-recipient", "secret-subject", "secret-body", "193842", "secret-html", "secret-tag", "secret-provider-receipt").forEach {
        assertFalse(output.contains(it), "Captured output contains $it")
      }
    }
  }

  @Test
  fun `retry attempts preserve metadata and reset numbering for each delivery`() = runBlocking {
    val retryable = MailDeliveryResult.Rejected(provider, MailFailure.THROTTLED, true)
    val accepted = MailDeliveryResult.Accepted(provider, "receipt")
    val capture = Capture()
    val retry = RetryMailTransport(ObservedMailTransport(
      ScriptedMailTransport(provider, listOf(retryable, accepted, accepted)), capture, nanoTime = { 0L },
    ), RetryPolicy(3), delay = {})
    val context = MailDeliveryContext(purpose = "verification", correlationId = UUID.randomUUID())
    withContext(context) {
      assertEquals(accepted, retry.send(message))
      assertEquals(accepted, retry.send(message))
      assertSame(context, coroutineContext[MailDeliveryContext])
    }
    assertEquals(listOf(1, 2, 1), capture.traces.map { it.attempt })
    assertEquals(List(3) { context.purpose }, capture.traces.map { it.purpose })
    assertEquals(List(3) { context.correlationId }, capture.traces.map { it.correlationId })
    assertEquals(List(3) { message.id }, capture.traces.map { it.messageId })
  }

  @Test
  fun `unclassified failure and cancellation propagate without exposing exception secrets`() = runBlocking {
    for (error in listOf(IllegalStateException("secret-provider-password"), CancellationException("secret-cancellation"))) {
      val capture = Capture()
      val child = object : MailTransport {
        override val provider = this@ObservedMailTransportTest.provider
        override suspend fun send(message: MailMessage): MailDeliveryResult = throw error
      }
      assertSame(error, assertFailsWith<Exception> { ObservedMailTransport(child, capture).send(message) })
      assertTrue(capture.metrics.isEmpty())
      assertTrue(capture.traces.isEmpty())
    }
  }

  @Test
  fun `telemetry failure cannot change an accepted delivery`() = runBlocking {
    val accepted = MailDeliveryResult.Accepted(provider, "receipt")
    val sink = object : MailObservationSink {
      override fun metric(metric: MailAttemptMetric) { error("collector unavailable") }
      override fun trace(trace: MailAttemptTrace) { error("collector unavailable") }
    }
    assertEquals(accepted, ObservedMailTransport(ScriptedMailTransport(provider, listOf(accepted)), sink).send(message))
  }
}
