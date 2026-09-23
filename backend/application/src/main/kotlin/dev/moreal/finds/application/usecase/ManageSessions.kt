package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.UserId
import java.time.Instant

data class SessionSummary(val id: UserSessionId, val createdAt: Instant, val expiresAt: Instant, val current: Boolean)
sealed interface SessionListResult {
  data class Listed(val sessions: List<SessionSummary>) : SessionListResult
  data object Forbidden : SessionListResult
}
class ManageSessions(private val transactions: TransactionPort, private val clock: ClockPort, private val random: SecureRandomPort) {
  fun list(principal: SessionPrincipal): SessionListResult = transactions.execute { tx ->
    val id = UserId(principal.actor.userId)
    val user = tx.lockUsers(setOf(id))[id]
    val now = clock.now()
    if (!tx.authorize(principal, user, now, recent = false)) return@execute SessionListResult.Forbidden
    SessionListResult.Listed(tx.userSessions.findByUserId(id).filter { it.isUsable(now) }
      .map { SessionSummary(it.id, it.createdAt, it.expiresAt, it.id == principal.sessionId) })
  }
  fun revoke(principal: SessionPrincipal, sessionId: UserSessionId, metadata: CommandMetadata): SecurityChangeResult = transactions.execute { tx ->
    val id = UserId(principal.actor.userId)
    val user = tx.lockUsers(setOf(id))[id]
    val now = clock.now()
    val current = sessionId == principal.sessionId
    if (!tx.authorize(principal, user, now, recent = !current)) return@execute SecurityChangeResult.Forbidden
    tx.securityCommand(id, "session.revoke", metadata, now, mapOf("session" to sessionId.value.toString())) {
      val target = tx.userSessions.findById(sessionId)
      when {
        target?.userId != id -> SecurityChangeResult.NotFound
        target.revokedAt != null -> SecurityChangeResult.Unchanged
        else -> {
          tx.userSessions.revoke(sessionId, now)
          tx.auditSecurity(random, now, principal.actor, AuditAction.SESSION_REVOKED, id, metadata)
          if (current) SecurityChangeResult.SignedOut else SecurityChangeResult.Changed
        }
      }
    }
  }
  fun revokeOthers(principal: SessionPrincipal, metadata: CommandMetadata): SecurityChangeResult = transactions.execute { tx ->
    val id = UserId(principal.actor.userId)
    val user = tx.lockUsers(setOf(id))[id]
    val now = clock.now()
    if (!tx.authorize(principal, user, now, recent = true)) return@execute SecurityChangeResult.Forbidden
    tx.securityCommand(id, "session.revoke_others", metadata, now, mapOf("except" to principal.sessionId.value.toString())) {
      if (tx.userSessions.findByUserId(id).none { it.id != principal.sessionId && it.revokedAt == null }) SecurityChangeResult.Unchanged
      else {
        tx.userSessions.revokeForUser(id, now, except = principal.sessionId)
        tx.auditSecurity(random, now, principal.actor, AuditAction.SESSION_REVOKED, id, metadata)
        SecurityChangeResult.Changed
      }
    }
  }
}
