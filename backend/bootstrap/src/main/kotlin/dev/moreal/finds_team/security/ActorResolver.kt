package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.application.usecase.SessionPrincipal
import dev.moreal.finds.domain.identity.*
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.Authentication
import java.io.Serializable

/** Only server-established Passkey authentication can contain this principal. No roles are cached. */
internal class PasskeyPrincipal(val userId: UserId, val sessionId: UserSessionId) : Serializable {
  override fun toString() = "PasskeyPrincipal(<redacted>)"
}
internal class PasskeyAuthentication(private val identity: PasskeyPrincipal) : AbstractAuthenticationToken(emptyList()) {
  init { isAuthenticated = true }
  override fun getPrincipal() = identity
  override fun getCredentials(): Any? = null
}

class ActorResolver(private val transactions: TransactionPort, private val clock: ClockPort) {
  fun resolve(authentication: Authentication?): Actor.User? = sessionPrincipal(authentication)?.actor
  fun sessionPrincipal(authentication: Authentication?): SessionPrincipal? {
    if (authentication !is PasskeyAuthentication || !authentication.isAuthenticated) return null
    val principal = authentication.principal
    return transactions.execute { tx ->
      val session = tx.userSessions.findById(principal.sessionId) ?: return@execute null
      val user = tx.users.findById(principal.userId) ?: return@execute null
      val now = clock.now()
      if (user.status != UserStatus.ACTIVE || session.userId != user.id || !session.isUsable(now) || session.authenticatedAt > now)
        return@execute null
      SessionPrincipal(Actor.User(user.id.value, user.roles, session.authenticatedAt, AuthenticationStrength.PASSKEY), session.id)
    }
  }
  fun isRecentAdministrator(authentication: Authentication?): Boolean = resolve(authentication)?.let {
    UserRole.ADMIN in it.roles && it.hasRecentPasskeyAuthentication(clock.now())
  } ?: false
}
