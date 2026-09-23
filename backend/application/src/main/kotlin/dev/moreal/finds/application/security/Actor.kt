package dev.moreal.finds.application.security

import dev.moreal.finds.domain.identity.UserRole
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID

enum class AuthenticationStrength {
  EMAIL_OTP,
  RECOVERY_PROOFS,
  PASSKEY,
}

sealed interface Actor {
  class User(
    val userId: UUID,
    roles: Set<UserRole>,
    val authenticatedAt: Instant,
    val authenticationStrength: AuthenticationStrength,
  ) : Actor {
    val roles: Set<UserRole> = Collections.unmodifiableSet(LinkedHashSet(roles))

    fun hasRecentPasskeyAuthentication(now: Instant): Boolean {
      if (authenticationStrength != AuthenticationStrength.PASSKEY) return false
      val age = Duration.between(authenticatedAt, now)
      return !age.isNegative && age <= RECENT_AUTH_WINDOW
    }

    companion object {
      val RECENT_AUTH_WINDOW: Duration = Duration.ofMinutes(5)
    }
  }

  data object System : Actor
}
