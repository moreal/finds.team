package dev.moreal.finds.application.port

import dev.moreal.finds.application.model.*
import dev.moreal.finds.domain.identity.*
import java.time.Instant
import java.util.UUID

/** Separate random management identity: never the WebAuthn credential ID or its bytes. */
data class ManagedPasskey(val id: UUID, val label: String, val createdAt: Instant, val lastUsedAt: Instant?)
data class PasskeyPageKey(val userId: UserId, val page: ConnectionRequest)
data class SessionPageKey(val userId: UserId, val currentSessionId: UserSessionId, val now: Instant, val page: ConnectionRequest)
interface AccountQueryPort {
  fun passkeys(keys: List<PasskeyPageKey>): List<Result<ConnectionPage<ManagedPasskey>>>
  fun sessions(keys: List<SessionPageKey>): List<Result<ConnectionPage<dev.moreal.finds.application.usecase.SessionSummary>>>
  fun credentialId(userId: UserId, managementId: UUID): CredentialId?
}
