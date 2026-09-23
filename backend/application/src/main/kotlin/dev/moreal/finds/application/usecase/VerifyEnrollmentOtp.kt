package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*

data class VerifyEnrollmentOtpCommand(val email: EmailAddress, val code: VerificationCode)

sealed interface VerifyEnrollmentOtpResult {
  data class Verified(val session: RestrictedSession) : VerifyEnrollmentOtpResult
  data object Rejected : VerifyEnrollmentOtpResult
}

class VerifyEnrollmentOtp(
  private val transactions: TransactionPort,
  private val clock: ClockPort,
  private val random: SecureRandomPort,
  private val hashes: KeyedIdentityHashPort,
) {
  fun execute(command: VerifyEnrollmentOtpCommand): VerifyEnrollmentOtpResult = transactions.execute { tx ->
    val existing = tx.users.lockByEmail(command.email)
    val now = clock.now()
    if (existing != null && existing.status != UserStatus.PENDING_PASSKEY) return@execute VerifyEnrollmentOtpResult.Rejected
    val state = tx.otpChallenges.find(command.email, VerificationPurpose.ENROLLMENT)
      ?: return@execute VerifyEnrollmentOtpResult.Rejected
    val challenge = state.challenge ?: return@execute VerifyEnrollmentOtpResult.Rejected
    if (state.lockedUntil?.let { now < it } == true || challenge.consumedAt != null || now >= challenge.expiresAt)
      return@execute VerifyEnrollmentOtpResult.Rejected
    if (!hashes.matches(challenge.hash, IdentityHashPurpose.ENROLLMENT_OTP, command.email.normalized, command.code.value)) {
      val failures = if (state.consecutiveFailures == Int.MAX_VALUE) Int.MAX_VALUE else state.consecutiveFailures + 1
      tx.otpChallenges.save(state.copy(consecutiveFailures = failures,
        lockedUntil = if (failures >= 5) now.plusSeconds(900) else state.lockedUntil))
      return@execute VerifyEnrollmentOtpResult.Rejected
    }

    tx.otpChallenges.save(state.copy(consecutiveFailures = 0, lockedUntil = null, challenge = challenge.copy(consumedAt = now)))
    val user = existing ?: User(UserId(random.uuid()), command.email).also(tx.users::save)
    tx.restrictedSessions.invalidateForUser(user.id, RestrictedSessionScope.ENROLLMENT, now)
    val session = RestrictedSession(RestrictedSessionId(random.uuid()), user.id, RestrictedSessionScope.ENROLLMENT,
      now, now.plusSeconds(600))
    tx.restrictedSessions.save(session)
    VerifyEnrollmentOtpResult.Verified(session)
  }
}
