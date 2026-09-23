package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.usecase.AdditionalPasskeySessionResult
import dev.moreal.finds.application.usecase.BeginAdditionalPasskeyRegistration
import dev.moreal.finds.domain.identity.UserId
import dev.moreal.finds.domain.identity.UserStatus
import dev.moreal.finds.graphql.GlobalIdCodec
import dev.moreal.finds.graphql.NodeType
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.util.WebUtils
import java.util.UUID

/** HTTP retries retain one usable scope in the same authenticated browser session. */
@RestController
class AdditionalPasskeyController(
  private val actors: ActorResolver,
  private val transactions: TransactionPort,
  private val clock: ClockPort,
  random: SecureRandomPort,
  private val securityEvents: HttpSecurityEvents,
  private val ceremonies: WebAuthnCeremonies,
) {
  private val begin = BeginAdditionalPasskeyRegistration(transactions, clock, random)

  @PostMapping("/webauthn/register/begin", produces = ["application/json"])
  fun begin(request: HttpServletRequest, authentication: Authentication?, @RequestBody(required = false) body: BeginRequest?): ResponseEntity<*> {
    val session = request.getSession(false) ?: return denied(request, HttpStatus.UNAUTHORIZED)
    // Share the registration mutex with options/completion, including their post-commit cleanup.
    return synchronized(WebUtils.getSessionMutex(session)) {
      val principal = actors.sessionPrincipal(authentication) ?: return@synchronized denied(request, HttpStatus.UNAUTHORIZED)
      if (!principal.actor.hasRecentPasskeyAuthentication(clock.now())) return@synchronized denied(request, HttpStatus.FORBIDDEN)
      val header = request.getHeader("Idempotency-Key")
      if (header == null || !UUID_PATTERN.matches(header)) return@synchronized ResponseEntity.badRequest()
        .header("Cache-Control", "no-store").body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed Passkey request"))
      val key = UUID.fromString(header)
      if (body?.expectedUserId != GlobalIdCodec.encode(NodeType.User, principal.actor.userId))
        return@synchronized denied(request, HttpStatus.FORBIDDEN)
      val canceled = session.getAttribute(CANCELED) as? CanceledHistory
      if (canceled?.commands?.containsKey(key) == true) return@synchronized denied(request, HttpStatus.FORBIDDEN)
      val previous = session.getAttribute(BEGIN) as? Begin
      val accepted = session.getAttribute(ACCEPTED) as? AcceptedHistory
      if (previous?.key != key && accepted?.keys?.contains(key) == true)
        return@synchronized denied(request, HttpStatus.FORBIDDEN)
      if (previous?.key == key) {
        val usable = previous.scopeId == session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION) && transactions.execute { tx ->
          val scope = tx.restrictedSessions.findById(previous.scopeId)
          scope != null && scope.userId.value == principal.actor.userId &&
            scope.scope == RestrictedSessionScope.ADDITIONAL_PASSKEY && scope.isUsable(clock.now())
        }
        if (!usable) return@synchronized denied(request, HttpStatus.FORBIDDEN)
      } else {
        // Never evict accepted keys: superseded, completed and canceled commands stay obsolete.
        if ((accepted?.keys?.size ?: 0) >= MAX_ACCEPTED_COMMANDS) return@synchronized denied(request, HttpStatus.FORBIDDEN)
        when (val result = begin.execute(principal)) {
          AdditionalPasskeySessionResult.Forbidden -> return@synchronized denied(request, HttpStatus.FORBIDDEN)
          is AdditionalPasskeySessionResult.Ready -> {
            session.setAttribute(WebAuthnCeremonies.RESTRICTED_SESSION, result.session.id)
            session.setAttribute(BEGIN, Begin(key, result.session.id))
            session.setAttribute(ACCEPTED, AcceptedHistory((accepted?.keys ?: emptySet()) + key))
          }
        }
      }
      ResponseEntity.ok().header("Cache-Control", "no-store").body(mapOf("ready" to true))
    }
  }

  @PostMapping("/webauthn/register/cancel", produces = ["application/json"])
  fun cancel(request: HttpServletRequest, authentication: Authentication?): ResponseEntity<*> {
    val session = request.getSession(false) ?: return denied(request, HttpStatus.UNAUTHORIZED)
    return synchronized(WebUtils.getSessionMutex(session)) {
      val principal = actors.sessionPrincipal(authentication) ?: return@synchronized denied(request, HttpStatus.UNAUTHORIZED)
      if (!principal.actor.hasRecentPasskeyAuthentication(clock.now())) return@synchronized denied(request, HttpStatus.FORBIDDEN)
      val header = request.getHeader("Idempotency-Key")
      if (header == null || !UUID_PATTERN.matches(header)) return@synchronized ResponseEntity.badRequest()
        .header("Cache-Control", "no-store").body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed Passkey request"))
      val key = UUID.fromString(header)
      val pending = session.getAttribute(BEGIN) as? Begin
      val canceled = session.getAttribute(CANCELED) as? CanceledHistory
      val replay = pending == null && session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION) == null &&
        canceled?.commands?.get(key) == principal.sessionId
      if (!replay && (pending?.key != key || pending.scopeId != session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION)))
        return@synchronized denied(request, HttpStatus.FORBIDDEN)
      val allowed = transactions.execute { tx ->
        val id = UserId(principal.actor.userId)
        val snapshot = tx.users.findById(id) ?: return@execute false
        val user = tx.users.lockByEmail(snapshot.email) ?: return@execute false
        val now = clock.now()
        val live = tx.userSessions.findById(principal.sessionId) ?: return@execute false
        if (user.status != UserStatus.ACTIVE || live.userId != id || !live.isUsable(now) ||
          live.authenticatedAt != principal.actor.authenticatedAt || !principal.actor.hasRecentPasskeyAuthentication(now)) return@execute false
        if (replay) return@execute true
        val scope = tx.restrictedSessions.findById(checkNotNull(pending).scopeId) ?: return@execute false
        if (scope.userId != id || scope.scope != RestrictedSessionScope.ADDITIONAL_PASSKEY || !scope.isUsable(now)) return@execute false
        // Begin maintains at most one usable additional scope under this same account lock.
        tx.restrictedSessions.invalidateForUser(id, RestrictedSessionScope.ADDITIONAL_PASSKEY, now)
        true
      }
      if (!allowed) return@synchronized denied(request, HttpStatus.FORBIDDEN)
      if (!replay) {
        ceremonies.clearPendingRegistration(request, checkNotNull(pending).scopeId)
        session.removeAttribute(BEGIN)
        session.setAttribute(CANCELED, CanceledHistory((canceled?.commands ?: emptyMap()) + (key to principal.sessionId)))
      }
      ResponseEntity.ok().header("Cache-Control", "no-store").body(mapOf("success" to true))
    }
  }

  private fun denied(request: HttpServletRequest, status: HttpStatus): ResponseEntity<ProblemDetail> {
    securityEvents.denied(request, SecurityEventAction.AUTHORIZATION_DENIED)
    return ResponseEntity.status(status).header("Cache-Control", "no-store")
      .body(ProblemDetail.forStatusAndDetail(status, "Passkey registration rejected"))
  }

  private class Begin(val key: UUID, val scopeId: RestrictedSessionId)
  data class BeginRequest(val expectedUserId: String?)
  private class CanceledHistory(val commands: Map<UUID, UserSessionId>)
  private class AcceptedHistory(val keys: Set<UUID>)
  private companion object {
    const val BEGIN = "finds.webauthn.additional-begin"
    const val CANCELED = "finds.webauthn.additional-canceled"
    const val ACCEPTED = "finds.webauthn.additional-accepted"
    const val MAX_ACCEPTED_COMMANDS = 64
    val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
  }
}
