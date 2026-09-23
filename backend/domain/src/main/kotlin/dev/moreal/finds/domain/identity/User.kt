package dev.moreal.finds.domain.identity

import java.util.Collections
import java.util.UUID

@JvmInline
value class UserId(val value: UUID)

/** Opaque adapter-supplied identifier; the domain never interprets credential cryptographic data. */
@JvmInline
value class CredentialId(val value: String) {
  init {
    require(value.isNotEmpty() && value.none { it.isWhitespace() || it.isISOControl() }) { "Invalid credential id" }
  }
}

enum class UserStatus { PENDING_PASSKEY, ACTIVE, SUSPENDED }

enum class IdentityRejection {
  SUSPENDED, NOT_ACTIVE, CREDENTIAL_ALREADY_EXISTS, CREDENTIAL_NOT_FOUND, LAST_CREDENTIAL, REQUIRED_USER_ROLE,
}

sealed interface UserChange {
  data class Updated(val user: User) : UserChange
  data object Unchanged : UserChange
  data class Rejected(val reason: IdentityRejection) : UserChange
}

/**
 * Immutable policy state, including only usable credential IDs. Persistence must serialize changes
 * to this aggregate. Caller authorization, recent authentication, and audit/session transactions
 * belong to the application boundary; these methods enforce the target account's invariants.
 */
class User(
  val id: UserId,
  val email: EmailAddress,
  val status: UserStatus = UserStatus.PENDING_PASSKEY,
  roles: Set<UserRole> = setOf(UserRole.USER),
  credentials: Set<CredentialId> = emptySet(),
) {
  val roles: Set<UserRole> = Collections.unmodifiableSet(LinkedHashSet(roles))
  val credentials: Set<CredentialId> = Collections.unmodifiableSet(LinkedHashSet(credentials))

  init {
    require(UserRole.USER in this.roles) { "An account requires the USER role" }
    require(status != UserStatus.ACTIVE || this.credentials.isNotEmpty()) { "Active account requires a credential" }
    require(status != UserStatus.PENDING_PASSKEY || this.credentials.isEmpty()) { "Pending account cannot have credentials" }
  }

  fun registerCredential(id: CredentialId): UserChange {
    if (status == UserStatus.SUSPENDED) return UserChange.Rejected(IdentityRejection.SUSPENDED)
    if (id in credentials) return UserChange.Rejected(IdentityRejection.CREDENTIAL_ALREADY_EXISTS)
    return UserChange.Updated(User(this.id, email, UserStatus.ACTIVE, roles, credentials + id))
  }

  fun removeCredential(id: CredentialId): UserChange = when (val decision = CredentialPolicy.remove(this, id)) {
    is CredentialDecision.Denied -> UserChange.Rejected(decision.reason)
    CredentialDecision.Allowed -> UserChange.Updated(User(this.id, email, status, roles, credentials - id))
  }

  /** Apply only after both recovery proofs and a new verified Passkey; never called at recovery start. */
  fun completeRecovery(newCredential: CredentialId): UserChange {
    val decision = CredentialPolicy.authenticate(this)
    if (decision is CredentialDecision.Denied) return UserChange.Rejected(decision.reason)
    if (newCredential in credentials) return UserChange.Rejected(IdentityRejection.CREDENTIAL_ALREADY_EXISTS)
    return UserChange.Updated(User(id, email, status, roles, setOf(newCredential)))
  }

  fun grantRole(role: UserRole): UserChange {
    if (status == UserStatus.SUSPENDED) return UserChange.Rejected(IdentityRejection.SUSPENDED)
    if (role in roles) return UserChange.Unchanged
    return UserChange.Updated(User(id, email, status, roles + role, credentials))
  }

  fun revokeRole(role: UserRole): UserChange {
    if (status == UserStatus.SUSPENDED) return UserChange.Rejected(IdentityRejection.SUSPENDED)
    if (role == UserRole.USER) return UserChange.Rejected(IdentityRejection.REQUIRED_USER_ROLE)
    if (role !in roles) return UserChange.Unchanged
    return UserChange.Updated(User(id, email, status, roles - role, credentials))
  }
}
