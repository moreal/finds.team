package dev.moreal.finds.domain.identity

sealed interface CredentialDecision {
  data object Allowed : CredentialDecision
  data class Denied(val reason: IdentityRejection) : CredentialDecision
}

object CredentialPolicy {
  fun authenticate(user: User): CredentialDecision = when (user.status) {
    UserStatus.SUSPENDED -> CredentialDecision.Denied(IdentityRejection.SUSPENDED)
    UserStatus.PENDING_PASSKEY -> CredentialDecision.Denied(IdentityRejection.NOT_ACTIVE)
    UserStatus.ACTIVE -> CredentialDecision.Allowed
  }

  fun remove(user: User, credential: CredentialId): CredentialDecision {
    val decision = authenticate(user)
    if (decision is CredentialDecision.Denied) return decision
    if (credential !in user.credentials) return CredentialDecision.Denied(IdentityRejection.CREDENTIAL_NOT_FOUND)
    if (user.credentials.size == 1) return CredentialDecision.Denied(IdentityRejection.LAST_CREDENTIAL)
    return CredentialDecision.Allowed
  }
}
