package dev.moreal.finds.application.testing

import dev.moreal.finds.application.port.MailOutbox
import dev.moreal.finds.application.port.EncryptedMailPayload
import dev.moreal.finds.application.port.MailOutboxLease
import dev.moreal.finds.application.port.MailPayloadCrypto
import dev.moreal.finds.application.port.MailPayloadMetadata
import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailMessageId
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class MailOutboxFake(private val crypto: MailPayloadCrypto) : MailOutbox {
  private val rows = linkedMapOf<MailMessageId, Row>()
  private val recorded = mutableListOf<RecordedMailAttempt>()
  val attempts: List<RecordedMailAttempt> get() = synchronized(this) { recorded.toList() }

  @Synchronized
  override fun enqueue(metadata: MailPayloadMetadata, plaintext: ByteArray, now: Instant) {
    val canonical = metadata.copy(expiresAt = metadata.expiresAt.truncatedTo(ChronoUnit.MICROS))
    require(canonical.expiresAt > now) { "Mail payload must expire in the future" }
    require(metadata.id !in rows) { "Duplicate mail message id" }
    val createdAt = now.truncatedTo(ChronoUnit.MICROS)
    rows[metadata.id] = Row(canonical, createdAt, crypto.encrypt(canonical, plaintext), nextAttemptAt = createdAt)
  }

  @Synchronized
  override fun leaseBatch(owner: String, now: Instant, ttl: Duration, limit: Int): List<MailOutboxLease> {
    require(owner.isNotBlank() && owner.length <= 128 && owner.none(Char::isISOControl)) { "Invalid lease owner" }
    require(!ttl.isNegative && ttl >= Duration.ofMillis(1) && limit in 1..1000) { "Invalid mail lease bounds" }
    return rows.values.filter { row ->
      row.metadata.expiresAt > now && row.nextAttemptAt <= now &&
        (row.state == "PENDING" || (row.state == "LEASED" && row.lease!!.leaseExpiresAt <= now))
    }.sortedWith(compareBy<Row>({ it.nextAttemptAt }, { it.createdAt }, { it.metadata.id.toString() }))
      .take(limit).map { row ->
        MailOutboxLease(
          row.metadata, row.payload!!, owner, UUID.randomUUID(),
          minOf(now.plus(ttl), row.metadata.expiresAt).truncatedTo(ChronoUnit.MICROS),
          row.attemptCount, row.state == "LEASED", row.createdAt,
        ).also {
          row.state = "LEASED"
          row.lease = it
        }
      }
  }

  @Synchronized
  override fun recordAttempt(lease: MailOutboxLease, result: MailDeliveryResult, now: Instant): Int? {
    val row = owned(lease, now) ?: return null
    row.attemptCount++
    recorded += RecordedMailAttempt(lease.metadata.id, row.attemptCount, result, now)
    return row.attemptCount
  }

  @Synchronized
  override fun complete(lease: MailOutboxLease, result: MailDeliveryResult, now: Instant): Boolean {
    require(result !is MailDeliveryResult.Rejected || !result.retryable) { "Retryable mail must be rescheduled" }
    val row = owned(lease, now) ?: return false
    row.state = when (result) {
      is MailDeliveryResult.Accepted -> "ACCEPTED"
      is MailDeliveryResult.Rejected -> "FAILED"
      is MailDeliveryResult.Indeterminate -> "INDETERMINATE"
    }
    if (row.state != "INDETERMINATE") row.payload = null
    row.lease = null
    return true
  }

  @Synchronized
  override fun reschedule(lease: MailOutboxLease, nextAttemptAt: Instant, now: Instant): Boolean {
    require(nextAttemptAt >= now) { "Next mail attempt must not be in the past" }
    val row = owned(lease, now) ?: return false
    row.state = "PENDING"
    row.nextAttemptAt = nextAttemptAt.truncatedTo(ChronoUnit.MICROS)
    row.lease = null
    return true
  }

  @Synchronized
  override fun redactExpired(now: Instant): Int {
    val expired = rows.values.filter { it.payload != null && it.metadata.expiresAt <= now }
    expired.forEach {
      it.state = "EXPIRED"
      it.payload = null
      it.lease = null
    }
    return expired.size
  }

  private fun owned(lease: MailOutboxLease, now: Instant): Row? = rows[lease.metadata.id]?.takeIf {
    it.state == "LEASED" && it.lease?.token == lease.token && it.lease?.owner == lease.owner &&
      it.lease!!.leaseExpiresAt > now && it.metadata.expiresAt > now
  }

  private class Row(
    val metadata: MailPayloadMetadata,
    val createdAt: Instant,
    var payload: EncryptedMailPayload?,
    var state: String = "PENDING",
    var lease: MailOutboxLease? = null,
    var attemptCount: Int = 0,
    var nextAttemptAt: Instant,
  )
}

data class RecordedMailAttempt(
  val messageId: MailMessageId,
  val number: Int,
  val result: MailDeliveryResult,
  val attemptedAt: Instant,
)
