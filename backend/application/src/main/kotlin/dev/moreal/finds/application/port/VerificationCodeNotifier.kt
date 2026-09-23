package dev.moreal.finds.application.port

import dev.moreal.finds.domain.identity.EmailAddress
import java.time.Instant
import java.util.UUID

enum class VerificationPurpose { ENROLLMENT, RECOVERY }

class VerificationCode(val value: String) {
  init { require(value.matches(Regex("[0-9]{8}"))) { "Verification code must contain eight digits" } }
  override fun toString(): String = "VerificationCode(<redacted>)"
}

@JvmInline
value class DeliveryRequestId(val value: UUID)

/** Enqueues within the caller's atomic unit; no suspension or provider I/O is allowed here. */
interface VerificationCodeNotifier {
  fun deliver(
    transaction: TransactionContext,
    recipient: EmailAddress,
    purpose: VerificationPurpose,
    code: VerificationCode,
    expiresAt: Instant,
    idempotencyKey: DeliveryRequestId,
    /** Use command/request metadata when available; the legacy default identifies delivery, not an HTTP request. */
    correlationId: UUID = idempotencyKey.value,
  ): DeliveryRequestId
}
