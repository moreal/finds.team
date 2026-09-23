package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.EncryptedMailPayload
import dev.moreal.finds.application.port.MailOutbox
import dev.moreal.finds.application.port.MailOutboxLease
import dev.moreal.finds.application.port.MailPayloadCrypto
import dev.moreal.finds.application.port.MailPayloadMetadata
import dev.moreal.finds.persistence.jooq.generated.tables.references.MAIL_DELIVERY_ATTEMPTS
import dev.moreal.finds.persistence.jooq.generated.tables.references.MAIL_OUTBOX
import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailMessageId
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

class JooqMailOutbox(private val context: DSLContext, private val crypto: MailPayloadCrypto) : MailOutbox {
  override fun enqueue(metadata: MailPayloadMetadata, plaintext: ByteArray, now: Instant) {
    val canonical = metadata.copy(expiresAt = metadata.expiresAt.truncatedTo(ChronoUnit.MICROS))
    require(canonical.expiresAt > now) { "Mail payload must expire in the future" }
    val encrypted = crypto.encrypt(canonical, plaintext)
    context.insertInto(MAIL_OUTBOX)
      .set(MAIL_OUTBOX.MESSAGE_ID, metadata.id.value)
      .set(MAIL_OUTBOX.CORRELATION_ID, canonical.correlationId)
      .set(MAIL_OUTBOX.PURPOSE, canonical.purpose)
      .set(MAIL_OUTBOX.EXPIRES_AT, canonical.expiresAt.sql())
      .set(MAIL_OUTBOX.CREATED_AT, now.sql())
      .set(MAIL_OUTBOX.PAYLOAD_CIPHERTEXT, encrypted.ciphertext)
      .set(MAIL_OUTBOX.PAYLOAD_NONCE, encrypted.nonce)
      .set(MAIL_OUTBOX.KEY_VERSION, encrypted.keyVersion)
      .set(MAIL_OUTBOX.NEXT_ATTEMPT_AT, now.sql())
      .execute()
  }

  override fun leaseBatch(owner: String, now: Instant, ttl: Duration, limit: Int): List<MailOutboxLease> {
    require(owner.isNotBlank() && owner.length <= 128 && owner.none(Char::isISOControl)) { "Invalid lease owner" }
    require(!ttl.isNegative && ttl >= Duration.ofMillis(1) && limit in 1..1000) { "Invalid mail lease bounds" }
    return context.transactionResult { configuration ->
      val tx = DSL.using(configuration)
      val rows = tx.selectFrom(MAIL_OUTBOX)
        .where(MAIL_OUTBOX.STATE.eq("PENDING").or(
          MAIL_OUTBOX.STATE.eq("LEASED").and(MAIL_OUTBOX.LEASE_EXPIRES_AT.le(now.sql())),
        ))
        .and(MAIL_OUTBOX.NEXT_ATTEMPT_AT.le(now.sql()))
        .and(MAIL_OUTBOX.EXPIRES_AT.gt(now.sql()))
        .orderBy(MAIL_OUTBOX.NEXT_ATTEMPT_AT, MAIL_OUTBOX.CREATED_AT, MAIL_OUTBOX.MESSAGE_ID)
        .limit(limit)
        .forUpdate()
        .skipLocked()
        .fetch()
      rows.map { row ->
        val token = UUID.randomUUID()
        val leaseExpiresAt = minOf(now.plus(ttl), row.expiresAt!!.toInstant()).truncatedTo(ChronoUnit.MICROS)
        tx.update(MAIL_OUTBOX)
          .set(MAIL_OUTBOX.STATE, "LEASED")
          .set(MAIL_OUTBOX.LEASE_OWNER, owner)
          .set(MAIL_OUTBOX.LEASE_TOKEN, token)
          .set(MAIL_OUTBOX.LEASE_EXPIRES_AT, leaseExpiresAt.sql())
          .where(MAIL_OUTBOX.MESSAGE_ID.eq(row.messageId))
          .execute()
        MailOutboxLease(
          metadata = MailPayloadMetadata(MailMessageId(row.messageId!!), row.purpose!!,
            row.expiresAt!!.toInstant(), row.correlationId!!),
          payload = EncryptedMailPayload(row.payloadCiphertext!!, row.payloadNonce!!, row.keyVersion!!),
          owner = owner,
          token = token,
          leaseExpiresAt = leaseExpiresAt,
          attemptCount = row.attemptCount!!,
          recovered = row.state == "LEASED",
          createdAt = row.createdAt!!.toInstant(),
        )
      }
    }
  }

  override fun recordAttempt(lease: MailOutboxLease, result: MailDeliveryResult, now: Instant): Int? {
    val fingerprint = receiptFingerprint(lease, result)
    return context.transactionResult { configuration ->
      val tx = DSL.using(configuration)
      val number = tx.update(MAIL_OUTBOX)
        .set(MAIL_OUTBOX.ATTEMPT_COUNT, MAIL_OUTBOX.ATTEMPT_COUNT.plus(1))
        .where(owned(lease, now))
        .returning(MAIL_OUTBOX.ATTEMPT_COUNT)
        .fetchOne()?.attemptCount ?: return@transactionResult null
      tx.insertInto(MAIL_DELIVERY_ATTEMPTS)
        .set(MAIL_DELIVERY_ATTEMPTS.MESSAGE_ID, lease.metadata.id.value)
        .set(MAIL_DELIVERY_ATTEMPTS.ATTEMPT_NUMBER, number)
        .set(MAIL_DELIVERY_ATTEMPTS.ATTEMPTED_AT, now.sql())
        .set(MAIL_DELIVERY_ATTEMPTS.PROVIDER, result.provider.value)
        .set(MAIL_DELIVERY_ATTEMPTS.OUTCOME, when (result) {
          is MailDeliveryResult.Accepted -> "ACCEPTED"
          is MailDeliveryResult.Rejected -> "REJECTED"
          is MailDeliveryResult.Indeterminate -> "INDETERMINATE"
        })
        .set(MAIL_DELIVERY_ATTEMPTS.FAILURE, when (result) {
          is MailDeliveryResult.Accepted -> null
          is MailDeliveryResult.Rejected -> result.failure.name
          is MailDeliveryResult.Indeterminate -> result.failure.name
        })
        .set(MAIL_DELIVERY_ATTEMPTS.RETRYABLE, (result as? MailDeliveryResult.Rejected)?.retryable)
        .set(MAIL_DELIVERY_ATTEMPTS.PROVIDER_RECEIPT_FINGERPRINT, fingerprint)
        .execute()
      number
    }
  }

  override fun complete(lease: MailOutboxLease, result: MailDeliveryResult, now: Instant): Boolean {
    require(result !is MailDeliveryResult.Rejected || !result.retryable) { "Retryable mail must be rescheduled" }
    val fingerprint = receiptFingerprint(lease, result)
    val state = when (result) {
      is MailDeliveryResult.Accepted -> "ACCEPTED"
      is MailDeliveryResult.Rejected -> "FAILED"
      is MailDeliveryResult.Indeterminate -> "INDETERMINATE"
    }
    return context.update(MAIL_OUTBOX)
      .set(MAIL_OUTBOX.STATE, state)
      .set(MAIL_OUTBOX.COMPLETED_AT, now.sql())
      .set(MAIL_OUTBOX.PROVIDER, result.provider.value)
      .set(MAIL_OUTBOX.PROVIDER_RECEIPT_FINGERPRINT, fingerprint)
      .set(
        MAIL_OUTBOX.PAYLOAD_CIPHERTEXT,
        if (state == "INDETERMINATE") MAIL_OUTBOX.PAYLOAD_CIPHERTEXT else DSL.inline(null as ByteArray?),
      )
      .set(
        MAIL_OUTBOX.PAYLOAD_NONCE,
        if (state == "INDETERMINATE") MAIL_OUTBOX.PAYLOAD_NONCE else DSL.inline(null as ByteArray?),
      )
      .setNull(MAIL_OUTBOX.LEASE_OWNER)
      .setNull(MAIL_OUTBOX.LEASE_TOKEN)
      .setNull(MAIL_OUTBOX.LEASE_EXPIRES_AT)
      .where(owned(lease, now))
      .execute() == 1
  }

  override fun reschedule(lease: MailOutboxLease, nextAttemptAt: Instant, now: Instant): Boolean {
    require(nextAttemptAt >= now) { "Next mail attempt must not be in the past" }
    return context.update(MAIL_OUTBOX)
      .set(MAIL_OUTBOX.STATE, "PENDING")
      .set(MAIL_OUTBOX.NEXT_ATTEMPT_AT, nextAttemptAt.sql())
      .setNull(MAIL_OUTBOX.LEASE_OWNER)
      .setNull(MAIL_OUTBOX.LEASE_TOKEN)
      .setNull(MAIL_OUTBOX.LEASE_EXPIRES_AT)
      .where(owned(lease, now))
      .execute() == 1
  }

  override fun redactExpired(now: Instant): Int = context.update(MAIL_OUTBOX)
    .set(MAIL_OUTBOX.STATE, "EXPIRED")
    .set(MAIL_OUTBOX.COMPLETED_AT, now.sql())
    .setNull(MAIL_OUTBOX.PAYLOAD_CIPHERTEXT)
    .setNull(MAIL_OUTBOX.PAYLOAD_NONCE)
    .setNull(MAIL_OUTBOX.LEASE_OWNER)
    .setNull(MAIL_OUTBOX.LEASE_TOKEN)
    .setNull(MAIL_OUTBOX.LEASE_EXPIRES_AT)
    .where(MAIL_OUTBOX.EXPIRES_AT.le(now.sql()))
    .and(MAIL_OUTBOX.PAYLOAD_CIPHERTEXT.isNotNull)
    .execute()

  private fun owned(lease: MailOutboxLease, now: Instant): Condition =
    MAIL_OUTBOX.MESSAGE_ID.eq(lease.metadata.id.value)
      .and(MAIL_OUTBOX.STATE.eq("LEASED"))
      .and(MAIL_OUTBOX.LEASE_TOKEN.eq(lease.token))
      .and(MAIL_OUTBOX.LEASE_OWNER.eq(lease.owner))
      .and(MAIL_OUTBOX.LEASE_EXPIRES_AT.gt(now.sql()))
      .and(MAIL_OUTBOX.EXPIRES_AT.gt(now.sql()))

  private fun receiptFingerprint(lease: MailOutboxLease, result: MailDeliveryResult): String? =
    (result as? MailDeliveryResult.Accepted)?.let {
      crypto.fingerprintReceipt(it.provider, it.providerMessageId, lease.payload.keyVersion)
    }

  private fun Instant.sql() = truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)
}
