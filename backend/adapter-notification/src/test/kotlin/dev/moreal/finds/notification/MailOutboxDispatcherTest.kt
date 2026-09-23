package dev.moreal.finds.notification

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.testing.FakeTransaction
import dev.moreal.finds.application.testing.MailOutboxFake
import dev.moreal.finds.domain.identity.EmailAddress
import dev.moreal.finds.persistence.AesGcmMailPayloadCrypto
import dev.moreal.mail.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import dev.moreal.mail.pool.PriorityMailTransport
import dev.moreal.mail.retry.RetryMailTransport
import dev.moreal.mail.retry.RetryPolicy
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.*

class MailOutboxDispatcherTest {
  private val start = Instant.parse("2026-09-23T00:00:00Z")
  private var now = start
  private val clock = ClockPort { now }
  private val crypto = AesGcmMailPayloadCrypto(1, mapOf(1 to ByteArray(32) { 7 }))
  private val outbox = MailOutboxFake(crypto)
  private val provider = MailProvider("scripted")
  private val sent = mutableListOf<MailMessage>()
  private val outcomes = mutableListOf<DispatchOutcome>()
  private val accepted = MailDeliveryResult.Accepted(provider, "receipt")
  private val retryable = MailDeliveryResult.Rejected(provider, MailFailure.NETWORK, true)
  private val permanent = MailDeliveryResult.Rejected(provider, MailFailure.INVALID_RECIPIENT, false)
  private val unknown = MailDeliveryResult.Indeterminate(provider, MailFailure.TIMEOUT)

  @Test
  fun `acceptance records stable id redacts and never sends twice`() = runTest {
    val id = enqueue()
    val dispatcher = worker { accepted }
    assertEquals(1, dispatcher.dispatch())
    assertEquals(id, sent.single().id)
    assertEquals(accepted, outbox.attempts.single().result)
    assertEquals(listOf(DispatchOutcome.ACCEPTED), outcomes)
    assertEquals(0, dispatcher.dispatch())
    now = start.plusSeconds(600)
    assertEquals(0, outbox.redactExpired(now))
  }

  @Test
  fun `definite temporary rejection schedules bounded retry with original id`() = runTest {
    val id = enqueue()
    var result: MailDeliveryResult = retryable
    val dispatcher = worker { result }
    dispatcher.dispatch()
    now = start.plusSeconds(9)
    assertEquals(0, dispatcher.dispatch())
    now = start.plusSeconds(10)
    result = accepted
    assertEquals(1, dispatcher.dispatch())
    assertEquals(listOf(id, id), sent.map { it.id })
    assertEquals(listOf(1, 2), outbox.attempts.map { it.number })
  }

  @Test
  fun `permanent failure redacts while indeterminate holds payload until expiry`() = runTest {
    for (result in listOf(permanent, unknown)) {
      enqueue()
      val dispatcher = worker { result }
      dispatcher.dispatch()
      assertEquals(0, dispatcher.dispatch())
    }
    assertEquals(listOf(DispatchOutcome.FAILED, DispatchOutcome.INDETERMINATE), outcomes)
    now = start.plusSeconds(600)
    assertEquals(1, outbox.redactExpired(now))
  }

  @Test
  fun `exhausted definite retries become terminal and redact`() = runTest {
    enqueue()
    val dispatcher = worker { retryable }
    dispatcher.dispatch()
    now = start.plusSeconds(10)
    dispatcher.dispatch()
    now = start.plusSeconds(40)
    dispatcher.dispatch()
    now = start.plusSeconds(80)
    assertEquals(0, dispatcher.dispatch())
    assertEquals(3, sent.size)
    assertEquals(DispatchOutcome.FAILED, outcomes.last())
    now = start.plusSeconds(600)
    assertEquals(0, outbox.redactExpired(now))
  }

  @Test
  fun `recovered lease after acceptance before local completion holds without decrypt or resend`() = runTest {
    enqueue()
    outbox.leaseBatch("crashed", now, Duration.ofSeconds(1), 1)
    now = start.plusSeconds(2)
    val noDecrypt = object : MailPayloadCrypto by crypto {
      override fun decrypt(metadata: MailPayloadMetadata, payload: EncryptedMailPayload): ByteArray = error("must not decrypt")
    }
    assertEquals(1, worker(noDecrypt) { accepted }.dispatch())
    assertTrue(sent.isEmpty())
    assertIs<MailDeliveryResult.Indeterminate>(outbox.attempts.single().result)
    assertEquals(listOf(DispatchOutcome.INDETERMINATE), outcomes)
  }

  @Test
  fun `expiry is checked again after decrypt and before provider call`() = runTest {
    enqueue()
    val advancing = object : MailPayloadCrypto by crypto {
      override fun decrypt(metadata: MailPayloadMetadata, payload: EncryptedMailPayload): ByteArray =
        crypto.decrypt(metadata, payload).also { now = start.plusSeconds(600) }
    }
    worker(advancing) { accepted }.dispatch()
    assertTrue(sent.isEmpty())
    assertEquals(listOf(DispatchOutcome.EXPIRED), outcomes)
    assertEquals(0, outbox.redactExpired(now))
  }

  @Test
  fun `expired lease never sends and stale completion cannot overwrite recovery`() = runTest {
    enqueue()
    val advancing = object : MailPayloadCrypto by crypto {
      override fun decrypt(metadata: MailPayloadMetadata, payload: EncryptedMailPayload): ByteArray =
        crypto.decrypt(metadata, payload).also { now = start.plusSeconds(121) }
    }
    worker(advancing) { accepted }.dispatch()
    assertTrue(sent.isEmpty())
    // The bounded loop may recover the expired lease in the same scan; it still never sends it.
    assertEquals(listOf(DispatchOutcome.LEASE_LOST, DispatchOutcome.INDETERMINATE), outcomes)
    worker { accepted }.dispatch()
    assertTrue(sent.isEmpty())
    assertIs<MailDeliveryResult.Indeterminate>(outbox.attempts.single().result)
  }

  @Test
  fun `bounded batch and telemetry failure do not duplicate accepted deliveries`() = runTest {
    repeat(3) { enqueue() }
    val transport = transport { accepted }
    val dispatcher = MailOutboxDispatcher(outbox, crypto, transport, Mailbox("verify@finds.team"), clock,
      MailDispatchPolicy(batchSize = 2), MailDispatchMetrics { error("telemetry offline") })
    assertEquals(2, dispatcher.dispatch())
    assertEquals(1, dispatcher.dispatch())
    assertEquals(3, sent.size)
  }

  @Test
  fun `unexpected provider exception is held without exposing its message`() = runTest {
    enqueue()
    val dispatcher = worker { error("private@example.test OTP 01234567") }
    dispatcher.dispatch()
    assertEquals(0, dispatcher.dispatch())
    assertIs<MailDeliveryResult.Indeterminate>(outbox.attempts.single().result)
    assertFalse(outbox.attempts.toString().contains("01234567"))
  }

  @Test
  fun `timeout is indeterminate and cancellation preserves a lease for safe recovery`() = runTest {
    enqueue()
    worker { delay(31_000); accepted }.dispatch()
    assertIs<MailDeliveryResult.Indeterminate>(outbox.attempts.single().result)
    assertEquals(MailFailure.TIMEOUT, (outbox.attempts.single().result as MailDeliveryResult.Indeterminate).failure)
    enqueue()
    assertFailsWith<CancellationException> { worker { throw CancellationException() }.dispatch() }
    now = start.plusSeconds(121)
    worker { accepted }.dispatch()
    assertEquals(2, sent.size)
    assertTrue(outbox.attempts.all { it.result is MailDeliveryResult.Indeterminate })
  }

  @Test
  fun `provider retry checks expiry again and suppresses fallback after deadline`() = runTest {
    enqueue()
    val first = ExpiringMailTransport(transport { retryable }, clock)
    val retry = RetryMailTransport(first, RetryPolicy(3, 1.milliseconds, 1.milliseconds, 0.0),
      delay = { now = start.plusSeconds(600) })
    val second = ExpiringMailTransport(transport { accepted }, clock)
    val pool = PriorityMailTransport(listOf(PriorityMailTransport.Entry(retry, 100), PriorityMailTransport.Entry(second, 50)))
    MailOutboxDispatcher(outbox, crypto, pool, Mailbox("verify@finds.team"), clock, MailDispatchPolicy()).dispatch()
    assertEquals(1, sent.size)
    assertTrue(outbox.attempts.isEmpty())
    assertEquals(0, outbox.redactExpired(now))
  }

  @Test
  fun `queue age reports duration without message identifiers or payload labels`() = runTest {
    enqueue()
    now = start.plusSeconds(15)
    val ages = mutableListOf<Duration>()
    val metrics = object : MailDispatchMetrics {
      override fun record(outcome: DispatchOutcome) = Unit
      override fun queueAge(age: Duration) { ages += age }
    }
    MailOutboxDispatcher(outbox, crypto, transport { accepted }, Mailbox("verify@finds.team"), clock,
      MailDispatchPolicy(), metrics).dispatch()
    assertEquals(listOf(Duration.ofSeconds(15)), ages)
  }

  @Test
  fun `slow earlier deliveries do not consume the leases of unsent later messages`() = runTest {
    repeat(3) { enqueue() }
    val dispatcher = MailOutboxDispatcher(outbox, crypto, transport {
      now = now.plusSeconds(20)
      accepted
    }, Mailbox("verify@finds.team"), clock, MailDispatchPolicy(batchSize = 3,
      leaseDuration = Duration.ofSeconds(35)), MailDispatchMetrics(outcomes::add))
    assertEquals(3, dispatcher.dispatch())
    assertEquals(3, sent.size)
    assertEquals(List(3) { DispatchOutcome.ACCEPTED }, outcomes)
  }

  private fun enqueue(): MailMessageId {
    val tx = FakeTransaction()
    tx.execute { MailVerificationCodeNotifier(clock).deliver(it, EmailAddress("private@example.test"),
      VerificationPurpose.ENROLLMENT, VerificationCode("01234567"), start.plusSeconds(600), DeliveryRequestId(UUID.randomUUID())) }
    val row = tx.outboxMessages.single()
    outbox.enqueue(row.metadata, row.plaintext, now)
    return row.metadata.id
  }

  private fun transport(result: suspend () -> MailDeliveryResult) = object : MailTransport {
    override val provider = this@MailOutboxDispatcherTest.provider
    override suspend fun send(message: MailMessage): MailDeliveryResult { sent += message; return result() }
  }

  private fun worker(payloadCrypto: MailPayloadCrypto = crypto, result: suspend () -> MailDeliveryResult) =
    MailOutboxDispatcher(outbox, payloadCrypto, transport(result), Mailbox("verify@finds.team"), clock,
      MailDispatchPolicy(), MailDispatchMetrics(outcomes::add))
}
