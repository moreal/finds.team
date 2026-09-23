package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.command.CanonicalCommandEncoder
import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.domain.identity.*
import java.time.Instant

/** Trusted adapter principal plus the server-side session's non-bearer management reference. */
data class SessionPrincipal(val actor: Actor.User, val sessionId: UserSessionId)
sealed interface SecurityChangeResult {
  data object Changed : SecurityChangeResult
  data object Unchanged : SecurityChangeResult
  data object SignedOut : SecurityChangeResult
  data object Forbidden : SecurityChangeResult
  data object NotFound : SecurityChangeResult
  data object LastCredential : SecurityChangeResult
  data object RequiredUserRole : SecurityChangeResult
  data object IdempotencyConflict : SecurityChangeResult
  data object InvalidLabel : SecurityChangeResult
  data object CredentialAlreadyExists : SecurityChangeResult
}
data class PasskeySummary(val id: CredentialId, val label: String, val createdAt: Instant, val lastUsedAt: Instant?)
sealed interface PasskeyListResult {
  data class Listed(val passkeys: List<PasskeySummary>) : PasskeyListResult
  data object Forbidden : PasskeyListResult
}
class ManagePasskeys(private val transactions: TransactionPort, private val clock: ClockPort, private val random: SecureRandomPort) {
  fun list(principal: SessionPrincipal): PasskeyListResult = transactions.execute { tx ->
    val userId = UserId(principal.actor.userId)
    val user = tx.lockUsers(setOf(userId))[userId]
    if (!tx.authorize(principal, user, clock.now(), recent = false)) return@execute PasskeyListResult.Forbidden
    PasskeyListResult.Listed(tx.credentials.findByUserId(userId).map { PasskeySummary(it.material.id, it.label, it.createdAt, it.lastUsedAt) })
  }
  fun remove(principal: SessionPrincipal, credentialId: CredentialId, metadata: CommandMetadata): SecurityChangeResult = transactions.execute { tx ->
    val userId = UserId(principal.actor.userId)
    val user = tx.lockUsers(setOf(userId))[userId]
    val now = clock.now()
    if (!tx.authorize(principal, user, now, recent = true)) return@execute SecurityChangeResult.Forbidden
    tx.securityCommand(userId, "passkey.remove", metadata, now, mapOf("credential" to credentialId.value)) {
      when (val change = checkNotNull(user).removeCredential(credentialId)) {
        is UserChange.Updated -> {
          tx.credentials.remove(credentialId)
          tx.users.save(change.user)
          tx.auditSecurity(random, now, principal.actor, AuditAction.PASSKEY_REMOVED, userId, metadata)
          SecurityChangeResult.Changed
        }
        is UserChange.Rejected -> if (change.reason == IdentityRejection.LAST_CREDENTIAL) SecurityChangeResult.LastCredential else SecurityChangeResult.NotFound
        UserChange.Unchanged -> SecurityChangeResult.Unchanged
      }
    }
  }
}
sealed interface RotateRecoveryCodeResult {
  data class Rotated(val recoveryCode: RecoveryCode) : RotateRecoveryCodeResult
  data object AlreadyRotated : RotateRecoveryCodeResult
  data object Forbidden : RotateRecoveryCodeResult
  data object IdempotencyConflict : RotateRecoveryCodeResult
}
class RotateRecoveryCode(private val transactions: TransactionPort, private val clock: ClockPort,
  private val random: SecureRandomPort, private val hashes: KeyedIdentityHashPort) {
  fun execute(principal: SessionPrincipal, metadata: CommandMetadata): RotateRecoveryCodeResult = transactions.execute { tx ->
    val userId = UserId(principal.actor.userId)
    val user = tx.lockUsers(setOf(userId))[userId]
    val now = clock.now()
    if (!tx.authorize(principal, user, now, recent = true)) return@execute RotateRecoveryCodeResult.Forbidden
    val operation = "recovery_code.rotate"
    val key = CommandRequestKey(userId.value.toString(), operation, requireNotNull(metadata.idempotencyKey) { "Recovery rotation requires an idempotency key" })
    when (val reserved = tx.commandRequests.reserve(CommandRequest(key, CanonicalCommandEncoder.hash(mapOf("user" to userId.value.toString())), now, CommandRetention.AUDIT))) {
      CommandReservation.Conflict -> return@execute RotateRecoveryCodeResult.IdempotencyConflict
      is CommandReservation.Replay -> {
        reserved.result.requireSupported(operation, 1)
        check(reserved.result.outcome == "ROTATED" && reserved.result.resourceIds.isEmpty()) { "Invalid recovery rotation result" }
        return@execute RotateRecoveryCodeResult.AlreadyRotated
      }
      CommandReservation.Reserved -> Unit
    }
    val recovery = freshRecoveryCode(random)
    tx.recoveryCodes.save(RecoveryCodeHash(userId, hashes.hash(IdentityHashPurpose.RECOVERY_CODE, userId.value.toString(), recovery.format()), now))
    tx.restrictedSessions.invalidateForUser(userId, RestrictedSessionScope.RECOVERY, now)
    tx.auditSecurity(random, now, principal.actor, AuditAction.RECOVERY_CODE_ROTATED, userId, metadata)
    tx.commandRequests.complete(key, StoredCommandResult(1, operation, "ROTATED"))
    RotateRecoveryCodeResult.Rotated(recovery)
  }
}
