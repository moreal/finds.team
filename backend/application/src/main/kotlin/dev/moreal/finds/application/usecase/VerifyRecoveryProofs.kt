package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*

class VerifyRecoveryProofsCommand(val email: EmailAddress, val otp: VerificationCode?, val recoveryCode: String?) {
  override fun toString(): String = "VerifyRecoveryProofsCommand(<redacted>)"
}
sealed interface VerifyRecoveryProofsResult {
  data class Verified(val session: RestrictedSession) : VerifyRecoveryProofsResult
  data object Rejected : VerifyRecoveryProofsResult
}
class VerifyRecoveryProofs(private val transactions: TransactionPort, private val clock: ClockPort,
  private val random: SecureRandomPort, private val hashes: KeyedIdentityHashPort) {
  fun execute(command: VerifyRecoveryProofsCommand): VerifyRecoveryProofsResult = transactions.execute { tx ->
    val user = tx.users.lockByEmail(command.email) ?: return@execute VerifyRecoveryProofsResult.Rejected
    if (user.status != UserStatus.ACTIVE) return@execute VerifyRecoveryProofsResult.Rejected
    val now = clock.now()
    val state = tx.otpChallenges.find(command.email, VerificationPurpose.RECOVERY)
      ?: return@execute VerifyRecoveryProofsResult.Rejected
    val challenge = state.challenge ?: return@execute VerifyRecoveryProofsResult.Rejected
    if (state.lockedUntil?.let { now < it } == true || challenge.consumedAt != null || now >= challenge.expiresAt)
      return@execute VerifyRecoveryProofsResult.Rejected
    val recovery = tx.recoveryCodes.findByUserId(user.id) ?: return@execute VerifyRecoveryProofsResult.Rejected
    val parsed = command.recoveryCode?.let(RecoveryCode::parse) as? RecoveryCodeResult.Valid
    // Always check both digests, including missing/malformed proof inputs. Public timing normalization
    // and independent IP/device abuse budgets still belong at the HTTP adapter boundary.
    val otpMatches = hashes.matches(challenge.hash, IdentityHashPurpose.RECOVERY_OTP, command.email.normalized, command.otp?.value ?: "")
    val codeMatches = hashes.matches(recovery.hash, IdentityHashPurpose.RECOVERY_CODE, user.id.value.toString(), parsed?.code?.format() ?: "")
    if (command.otp == null || parsed == null || !otpMatches || !codeMatches) {
      val failures = if (state.consecutiveFailures == Int.MAX_VALUE) Int.MAX_VALUE else state.consecutiveFailures + 1
      tx.otpChallenges.save(state.copy(consecutiveFailures = failures,
        lockedUntil = if (failures >= 5) now.plusSeconds(900) else state.lockedUntil))
      return@execute VerifyRecoveryProofsResult.Rejected
    }
    tx.otpChallenges.save(state.copy(consecutiveFailures = 0, lockedUntil = null, challenge = challenge.copy(consumedAt = now)))
    // The saved code remains valid until completion so an expired ceremony cannot destroy recovery.
    // Every completion or code rotation invalidates all these sessions under this same account lock.
    val session = RestrictedSession(RestrictedSessionId(random.uuid()), user.id, RestrictedSessionScope.RECOVERY, now, now.plusSeconds(600))
    tx.restrictedSessions.save(session)
    VerifyRecoveryProofsResult.Verified(session)
  }
}
