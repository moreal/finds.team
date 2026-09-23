package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.MailPayloadMetadata
import dev.moreal.finds.application.testing.MailOutboxFake
import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailFailure
import dev.moreal.mail.MailMessageId
import dev.moreal.mail.MailProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailOutboxFakeTest {
  @Test
  fun `fake retains id attempts retry schedule and completion`() {
    val box = MailOutboxFake(crypto)
    val metadata = metadata()
    box.enqueue(metadata, payload, now)
    assertFailsWith<IllegalArgumentException> { box.enqueue(metadata, payload, now) }
    val leases = box.leaseBatch("one", now, ttl, 1)
    assertEquals(1, leases.size)
    val first = leases.single()
    assertContentEquals(payload, crypto.decrypt(first.metadata, first.payload))
    assertEquals(1, box.recordAttempt(first, retryable, now.plusSeconds(1)))
    assertTrue(box.reschedule(first, now.plusSeconds(60), now.plusSeconds(1)))
    assertTrue(box.leaseBatch("two", now.plusSeconds(59), ttl, 1).isEmpty())
    val next = box.leaseBatch("two", now.plusSeconds(60), ttl, 1).single()
    assertEquals(metadata.id, next.metadata.id)
    assertEquals(1, next.attemptCount)
    assertFalse(next.recovered)
    assertEquals(2, box.recordAttempt(next, accepted, now.plusSeconds(61)))
    assertEquals(listOf(retryable, accepted), box.attempts.map { it.result })
    assertTrue(box.complete(next, accepted, now.plusSeconds(61)))
    assertTrue(box.leaseBatch("three", now.plusSeconds(90), ttl, 1).isEmpty())
    assertEquals(0, box.redactExpired(now.plusSeconds(300)))
  }

  @Test
  fun `fake recovers crashed lease fences stale tokens and holds indeterminate until expiry`() {
    val box = MailOutboxFake(crypto)
    box.enqueue(metadata(), payload, now)
    val leases = box.leaseBatch("same", now, ttl, 1)
    assertEquals(1, leases.size)
    val stale = leases.single()
    val next = box.leaseBatch("same", now.plusSeconds(30), ttl, 1).single()
    assertTrue(next.recovered)
    assertTrue(next.token != stale.token)
    assertNull(box.recordAttempt(stale, accepted, now.plusSeconds(31)))
    assertFalse(box.complete(stale, accepted, now.plusSeconds(31)))
    assertFalse(box.reschedule(stale, now.plusSeconds(40), now.plusSeconds(31)))
    assertTrue(box.complete(next, MailDeliveryResult.Indeterminate(provider, MailFailure.TIMEOUT), now.plusSeconds(31)))
    assertTrue(box.leaseBatch("three", now.plusSeconds(90), ttl, 1).isEmpty())
    assertEquals(1, box.redactExpired(now.plusSeconds(300)))
    assertEquals(0, box.redactExpired(now.plusSeconds(300)))
  }

  @Test
  fun `fake expires pending payloads and rejects invalid schedule or retryable completion`() {
    val box = MailOutboxFake(crypto)
    box.enqueue(metadata(), payload, now)
    val leases = box.leaseBatch("one", now, ttl, 1)
    assertEquals(1, leases.size)
    assertFailsWith<IllegalArgumentException> { box.complete(leases.single(), retryable, now) }
    assertFailsWith<IllegalArgumentException> { box.reschedule(leases.single(), now.minusSeconds(1), now) }
    assertFalse(box.complete(leases.single(), accepted, now.plusSeconds(30)))
    box.enqueue(metadata(), payload, now)
    assertTrue(box.leaseBatch("two", now.plusSeconds(300), ttl, 10).isEmpty())
    assertEquals(2, box.redactExpired(now.plusSeconds(300)))
  }

  private fun metadata() = MailPayloadMetadata(MailMessageId.new(), "VERIFY_EMAIL", now.plusSeconds(300))
  private val now = Instant.parse("2026-09-23T00:00:00Z")
  private val ttl = Duration.ofSeconds(30)
  private val payload = "recipient@example.com 419573".encodeToByteArray()
  private val crypto = AesGcmMailPayloadCrypto(1, mapOf(1 to ByteArray(32) { it.toByte() }))
  private val provider = MailProvider("test")
  private val accepted = MailDeliveryResult.Accepted(provider, "receipt")
  private val retryable = MailDeliveryResult.Rejected(provider, MailFailure.THROTTLED, true)
}
