package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.domain.identity.UserId
import java.util.UUID

/** The GraphQL-facing query port returns metadata only; commands reuse transactional identity policy. */
class AccountManagement(
  val queries: AccountQueryPort,
  private val passkeys: ManagePasskeys,
  private val rename: RenamePasskey,
  private val recovery: RotateRecoveryCode,
  private val sessions: ManageSessions,
) {
  fun rename(principal: SessionPrincipal, id: UUID, label: String, metadata: CommandMetadata) =
    queries.credentialId(UserId(principal.actor.userId), id)?.let { rename.execute(principal, it, label, metadata) }
      ?: SecurityChangeResult.NotFound
  fun remove(principal: SessionPrincipal, id: UUID, metadata: CommandMetadata) =
    queries.credentialId(UserId(principal.actor.userId), id)?.let { passkeys.remove(principal, it, metadata) }
      ?: SecurityChangeResult.NotFound
  fun rotate(principal: SessionPrincipal, metadata: CommandMetadata) = recovery.execute(principal, metadata)
  fun revoke(principal: SessionPrincipal, id: UserSessionId, metadata: CommandMetadata) = sessions.revoke(principal, id, metadata)
  fun revokeOthers(principal: SessionPrincipal, metadata: CommandMetadata) = sessions.revokeOthers(principal, metadata)
}
