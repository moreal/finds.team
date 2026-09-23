package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.command.CanonicalCommandEncoder
import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.domain.identity.*
import java.util.Base64

data class CompletePasskeyRecoveryCommand(val sessionId: RestrictedSessionId,
  val registration: VerifiedPasskeyRegistration, val metadata: CommandMetadata) {
  init { require(metadata.idempotencyKey != null) { "Recovery completion requires an idempotency key" } }
}
sealed interface CompletePasskeyRecoveryResult {
  data class Completed(val userId: UserId, val recoveryCode: RecoveryCode) : CompletePasskeyRecoveryResult
  data class AlreadyCompleted(val userId: UserId) : CompletePasskeyRecoveryResult
  data object Rejected : CompletePasskeyRecoveryResult
  data object IdempotencyConflict : CompletePasskeyRecoveryResult
}
class CompletePasskeyRecovery(private val transactions: TransactionPort, private val clock: ClockPort,
  private val random: SecureRandomPort, private val hashes: KeyedIdentityHashPort) {
  fun execute(command: CompletePasskeyRecoveryCommand): CompletePasskeyRecoveryResult = transactions.execute { tx ->
    val proof = command.registration
    if (proof.sessionId != command.sessionId) return@execute CompletePasskeyRecoveryResult.Rejected
    val initial = tx.restrictedSessions.findById(command.sessionId) ?: return@execute CompletePasskeyRecoveryResult.Rejected
    if (initial.userId != proof.userId) return@execute CompletePasskeyRecoveryResult.Rejected
    val user = tx.lockUsers(setOf(initial.userId))[initial.userId] ?: return@execute CompletePasskeyRecoveryResult.Rejected
    val session = tx.restrictedSessions.findById(command.sessionId) ?: return@execute CompletePasskeyRecoveryResult.Rejected
    val now = clock.now()
    if (session.userId != user.id || session.scope != RestrictedSessionScope.RECOVERY)
      return@execute CompletePasskeyRecoveryResult.Rejected
    val material = proof.credential
    val operation = "recovery.complete"
    val key = CommandRequestKey(user.id.value.toString(), operation, checkNotNull(command.metadata.idempotencyKey))
    // Only the fingerprint retains the original session binding, never a raw bearer/session value.
    val semantics = mapOf("user" to user.id.value.toString(), "session" to session.id.value.toString(), "credential" to mapOf(
      "id" to material.id.value, "publicKeyCose" to Base64.getEncoder().encodeToString(material.publicKeyCose),
      "signatureCount" to material.signatureCount, "transports" to material.transports.sorted(),
      "backupEligible" to material.backupEligible, "backedUp" to material.backedUp))
    when (val reserved = tx.commandRequests.reserve(CommandRequest(key, CanonicalCommandEncoder.hash(semantics), now, CommandRetention.AUDIT))) {
      CommandReservation.Conflict -> return@execute CompletePasskeyRecoveryResult.IdempotencyConflict
      is CommandReservation.Replay -> {
        reserved.result.requireSupported(operation, 1)
        check(reserved.result.outcome in setOf("COMPLETED", "REJECTED") && reserved.result.resourceIds.isEmpty()) { "Invalid recovery result" }
        return@execute if (reserved.result.outcome == "COMPLETED") CompletePasskeyRecoveryResult.AlreadyCompleted(user.id)
          else CompletePasskeyRecoveryResult.Rejected
      }
      CommandReservation.Reserved -> Unit
    }
    fun reject(): CompletePasskeyRecoveryResult {
      tx.commandRequests.complete(key, StoredCommandResult(1, operation, "REJECTED"))
      return CompletePasskeyRecoveryResult.Rejected
    }
    // Consumed/expired sessions may resolve an existing result above, but never authorize a new
    // credential, code or session mutation. The adapter must preserve trusted ceremony binding on
    // a retry without restoring this restricted session as active or treating it as normal login.
    if (!session.isUsable(now) || user.status != UserStatus.ACTIVE || tx.recoveryCodes.findByUserId(user.id) == null)
      return@execute reject()
    val updated = (user.completeRecovery(material.id) as? UserChange.Updated)?.user
      ?: return@execute reject()
    // Insert first to enforce global uniqueness without removing anything on a collision.
    if (!tx.credentials.insert(PasskeyCredential(user.id, material, now))) return@execute reject()
    user.credentials.forEach(tx.credentials::remove)
    tx.users.save(updated)
    tx.userSessions.revokeForUser(user.id, now)
    RestrictedSessionScope.entries.forEach { tx.restrictedSessions.invalidateForUser(user.id, it, now) }
    val recovery = freshRecoveryCode(random)
    tx.recoveryCodes.save(RecoveryCodeHash(user.id, hashes.hash(IdentityHashPurpose.RECOVERY_CODE, user.id.value.toString(), recovery.format()), now))
    tx.auditSecurity(random, now, Actor.User(user.id.value, user.roles, now, AuthenticationStrength.PASSKEY),
      AuditAction.ACCOUNT_RECOVERY_COMPLETED, user.id, command.metadata)
    tx.commandRequests.complete(key, StoredCommandResult(1, operation, "COMPLETED"))
    CompletePasskeyRecoveryResult.Completed(user.id, recovery)
  }
}
