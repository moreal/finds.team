package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*

class ManageRoles(private val transactions: TransactionPort, private val clock: ClockPort, private val random: SecureRandomPort) {
  fun grant(principal: SessionPrincipal, target: UserId, role: UserRole, metadata: CommandMetadata): SecurityChangeResult = change(principal, target, role, metadata, grant = true)
  fun revoke(principal: SessionPrincipal, target: UserId, role: UserRole, metadata: CommandMetadata): SecurityChangeResult = change(principal, target, role, metadata, grant = false)

  private fun change(principal: SessionPrincipal, target: UserId, role: UserRole, metadata: CommandMetadata, grant: Boolean): SecurityChangeResult = transactions.execute { tx ->
    val actorId = UserId(principal.actor.userId)
    val users = tx.lockUsers(setOf(actorId, target))
    val actor = users[actorId]
    val now = clock.now()
    if (!tx.authorize(principal, actor, now, recent = true) || UserRole.ADMIN !in checkNotNull(actor).roles) return@execute SecurityChangeResult.Forbidden
    val operation = if (grant) "role.grant" else "role.revoke"
    tx.securityCommand(actorId, operation, metadata, now, mapOf("target" to target.value.toString(), "role" to role.name)) {
      val user = users[target] ?: return@securityCommand SecurityChangeResult.NotFound
      when (val change = if (grant) user.grantRole(role) else user.revokeRole(role)) {
        is UserChange.Updated -> {
          tx.users.save(change.user)
          tx.auditSecurity(random, now, principal.actor, if (grant) AuditAction.ROLE_GRANTED else AuditAction.ROLE_REVOKED,
            target, metadata, mapOf("role" to role.name))
          SecurityChangeResult.Changed
        }
        UserChange.Unchanged -> SecurityChangeResult.Unchanged
        is UserChange.Rejected -> if (change.reason == IdentityRejection.REQUIRED_USER_ROLE) SecurityChangeResult.RequiredUserRole else SecurityChangeResult.Forbidden
      }
    }
  }
}
