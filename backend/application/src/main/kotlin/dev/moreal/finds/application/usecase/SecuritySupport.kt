package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.audit.*
import dev.moreal.finds.application.command.CanonicalCommandEncoder
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.domain.identity.*
import java.time.Instant

/** Multi-account commands acquire normalized email locks in the same order to avoid deadlocks. */
internal fun TransactionContext.lockUsers(ids: Set<UserId>): Map<UserId, User> = ids.mapNotNull(users::findById)
  .sortedBy { it.email.normalized }.mapNotNull { users.lockByEmail(it.email) }.associateBy { it.id }

internal fun TransactionContext.authorize(principal: SessionPrincipal, user: User?, now: Instant, recent: Boolean): Boolean {
  if (user?.status != UserStatus.ACTIVE || user.id.value != principal.actor.userId ||
    principal.actor.authenticationStrength != AuthenticationStrength.PASSKEY) return false
  val session = userSessions.findById(principal.sessionId) ?: return false
  return session.userId == user.id && session.isUsable(now) && session.authenticatedAt == principal.actor.authenticatedAt &&
    principal.actor.authenticatedAt <= now && (!recent || principal.actor.hasRecentPasskeyAuthentication(now))
}

internal fun TransactionContext.auditSecurity(random: SecureRandomPort, now: Instant, actor: Actor.User,
  action: AuditAction, target: UserId, metadata: CommandMetadata, details: Map<String, String> = emptyMap()) {
  auditLog.append(AuditEvent(random.uuid(), 1, now, actor, action, "user", target.value.toString(),
    metadata.requestId, metadata.correlationId, AuditOutcome.SUCCEEDED, AuditDetails.from(action, details)))
}

internal fun freshRecoveryCode(random: SecureRandomPort): RecoveryCode {
  val entropy = random.bytes(16)
  return try { RecoveryCode.fromBytes(entropy) } finally { entropy.fill(0) }
}

/** Authorization is checked before this helper, including retries after roles/session state changed. */
internal fun TransactionContext.securityCommand(userId: UserId, operation: String, metadata: CommandMetadata,
  now: Instant, semantics: Map<String, Any?>, change: () -> SecurityChangeResult): SecurityChangeResult {
  val key = CommandRequestKey(userId.value.toString(), operation,
    requireNotNull(metadata.idempotencyKey) { "Security changes require an idempotency key" })
  when (val reservation = commandRequests.reserve(CommandRequest(key, CanonicalCommandEncoder.hash(semantics), now, CommandRetention.AUDIT))) {
    CommandReservation.Conflict -> return SecurityChangeResult.IdempotencyConflict
    is CommandReservation.Replay -> {
      reservation.result.requireSupported(operation, 1)
      check(reservation.result.resourceIds.isEmpty()) { "Invalid security command result" }
      return decodeSecurityResult(operation, reservation.result.outcome)
    }
    CommandReservation.Reserved -> Unit
  }
  val result = change()
  commandRequests.complete(key, StoredCommandResult(1, operation, encodeSecurityResult(result)))
  return result
}

private fun encodeSecurityResult(result: SecurityChangeResult): String = when (result) {
  SecurityChangeResult.Changed -> "CHANGED"
  SecurityChangeResult.Unchanged -> "UNCHANGED"
  SecurityChangeResult.SignedOut -> "SIGNED_OUT"
  SecurityChangeResult.Forbidden -> "FORBIDDEN"
  SecurityChangeResult.NotFound -> "NOT_FOUND"
  SecurityChangeResult.LastCredential -> "LAST_CREDENTIAL"
  SecurityChangeResult.RequiredUserRole -> "REQUIRED_USER_ROLE"
  SecurityChangeResult.InvalidLabel -> "INVALID_LABEL"
  SecurityChangeResult.CredentialAlreadyExists -> "CREDENTIAL_ALREADY_EXISTS"
  SecurityChangeResult.IdempotencyConflict -> error("Cannot store reservation conflict")
}

private fun decodeSecurityResult(operation: String, outcome: String): SecurityChangeResult {
  val allowed = when (operation) {
    "role.grant", "role.revoke" -> setOf("CHANGED", "UNCHANGED", "FORBIDDEN", "NOT_FOUND", "REQUIRED_USER_ROLE")
    "passkey.remove" -> setOf("CHANGED", "NOT_FOUND", "LAST_CREDENTIAL")
    "passkey.rename" -> setOf("CHANGED", "UNCHANGED", "NOT_FOUND")
    "passkey.register" -> setOf("CHANGED", "FORBIDDEN", "CREDENTIAL_ALREADY_EXISTS")
    "session.revoke" -> setOf("CHANGED", "UNCHANGED", "SIGNED_OUT", "NOT_FOUND")
    "session.logout" -> setOf("SIGNED_OUT")
    "session.revoke_others" -> setOf("CHANGED", "UNCHANGED")
    else -> throw UnsupportedCommandResultException()
  }
  if (outcome !in allowed) throw UnsupportedCommandResultException()
  return when (outcome) {
  "CHANGED" -> SecurityChangeResult.Changed
  "UNCHANGED" -> SecurityChangeResult.Unchanged
  "SIGNED_OUT" -> SecurityChangeResult.SignedOut
  "FORBIDDEN" -> SecurityChangeResult.Forbidden
  "NOT_FOUND" -> SecurityChangeResult.NotFound
  "LAST_CREDENTIAL" -> SecurityChangeResult.LastCredential
  "REQUIRED_USER_ROLE" -> SecurityChangeResult.RequiredUserRole
  "INVALID_LABEL" -> SecurityChangeResult.InvalidLabel
  "CREDENTIAL_ALREADY_EXISTS" -> SecurityChangeResult.CredentialAlreadyExists
  else -> throw UnsupportedCommandResultException()
  }
}
