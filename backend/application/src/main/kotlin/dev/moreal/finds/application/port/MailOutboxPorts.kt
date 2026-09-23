package dev.moreal.finds.application.port

import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailMessageId
import dev.moreal.mail.MailProvider
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Only non-sensitive routing metadata belongs here; recipient, code and template data are payload. */
data class MailPayloadMetadata(val id: MailMessageId, val purpose: String, val expiresAt: Instant) {
  init {
    require(purpose.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) { "Invalid mail purpose" }
  }
}

class EncryptedMailPayload(ciphertext: ByteArray, nonce: ByteArray, val keyVersion: Int) {
  private val encrypted = ciphertext.copyOf()
  private val iv = nonce.copyOf()
  val ciphertext: ByteArray get() = encrypted.copyOf()
  val nonce: ByteArray get() = iv.copyOf()

  init {
    require(ciphertext.size >= 16 && nonce.size == 12 && keyVersion > 0) { "Invalid encrypted mail payload" }
  }

  override fun toString(): String = "EncryptedMailPayload(keyVersion=$keyVersion, <redacted>)"
}

/** Implementations use an already-loaded external key; these operations must not perform network I/O. */
interface MailPayloadCrypto {
  fun encrypt(metadata: MailPayloadMetadata, plaintext: ByteArray): EncryptedMailPayload
  fun decrypt(metadata: MailPayloadMetadata, payload: EncryptedMailPayload): ByteArray

  /** Irreversible keyed correlation token. Use the payload's key version across attempts/completion. */
  fun fingerprintReceipt(provider: MailProvider, receipt: String, keyVersion: Int): String
}

data class MailOutboxLease(
  val metadata: MailPayloadMetadata,
  val payload: EncryptedMailPayload,
  val owner: String,
  val token: UUID,
  val leaseExpiresAt: Instant,
  val attemptCount: Int,
  /** A prior worker may have sent this message. Apply provider idempotency policy before sending. */
  val recovered: Boolean,
)

/** Transaction-compatible enqueue only; encryption uses a loaded key and performs no external I/O. */
fun interface MailOutboxEnqueue {
  /** Duplicate message ids are rejected. Supply a transaction-bound implementation for atomic enqueue. */
  fun enqueue(metadata: MailPayloadMetadata, plaintext: ByteArray, now: Instant)
}

interface MailOutbox : MailOutboxEnqueue {
  /**
   * Returns ciphertext only. Decrypt and perform provider I/O after the leasing transaction commits.
   * Workers must recheck expiry before sending and resolve recovered leases using provider idempotency policy.
   */
  fun leaseBatch(owner: String, now: Instant, ttl: Duration, limit: Int): List<MailOutboxLease>

  /** Every mutation is fenced by token and a still-valid lease. Null/false means ownership was lost. */
  fun recordAttempt(lease: MailOutboxLease, result: MailDeliveryResult, now: Instant): Int?

  /** Accepted and permanent rejection redact immediately; indeterminate holds until expiry. */
  fun complete(lease: MailOutboxLease, result: MailDeliveryResult, now: Instant): Boolean

  /** Caller must establish definite nonacceptance or provider idempotency before requesting a retry. */
  fun reschedule(lease: MailOutboxLease, nextAttemptAt: Instant, now: Instant): Boolean

  fun redactExpired(now: Instant): Int
}
