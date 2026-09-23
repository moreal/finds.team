package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.usecase.AdditionalPasskeySessionResult
import dev.moreal.finds.application.usecase.BeginAdditionalPasskeyRegistration
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
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
) {
  private val begin = BeginAdditionalPasskeyRegistration(transactions, clock, random)

  @PostMapping("/webauthn/register/begin", produces = ["application/json"])
  fun begin(request: HttpServletRequest, authentication: Authentication?): ResponseEntity<*> {
    val session = request.getSession(false) ?: return denied(request, HttpStatus.UNAUTHORIZED)
    // Share the registration mutex with options/completion, including their post-commit cleanup.
    return synchronized(WebUtils.getSessionMutex(session)) {
      val principal = actors.sessionPrincipal(authentication) ?: return@synchronized denied(request, HttpStatus.UNAUTHORIZED)
      if (!principal.actor.hasRecentPasskeyAuthentication(clock.now())) return@synchronized denied(request, HttpStatus.FORBIDDEN)
      val header = request.getHeader("Idempotency-Key")
      if (header == null || !UUID_PATTERN.matches(header)) return@synchronized ResponseEntity.badRequest()
        .header("Cache-Control", "no-store").body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed Passkey request"))
      val key = UUID.fromString(header)
      val previous = session.getAttribute(BEGIN) as? Begin
      if (previous?.key == key) {
        val usable = previous.scopeId == session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION) && transactions.execute { tx ->
          val scope = tx.restrictedSessions.findById(previous.scopeId)
          scope != null && scope.userId.value == principal.actor.userId &&
            scope.scope == RestrictedSessionScope.ADDITIONAL_PASSKEY && scope.isUsable(clock.now())
        }
        if (!usable) return@synchronized denied(request, HttpStatus.FORBIDDEN)
      } else {
        when (val result = begin.execute(principal)) {
          AdditionalPasskeySessionResult.Forbidden -> return@synchronized denied(request, HttpStatus.FORBIDDEN)
          is AdditionalPasskeySessionResult.Ready -> {
            session.setAttribute(WebAuthnCeremonies.RESTRICTED_SESSION, result.session.id)
            session.setAttribute(BEGIN, Begin(key, result.session.id))
          }
        }
      }
      ResponseEntity.ok().header("Cache-Control", "no-store").body(mapOf("ready" to true))
    }
  }

  private fun denied(request: HttpServletRequest, status: HttpStatus): ResponseEntity<ProblemDetail> {
    securityEvents.denied(request, SecurityEventAction.AUTHORIZATION_DENIED)
    return ResponseEntity.status(status).header("Cache-Control", "no-store")
      .body(ProblemDetail.forStatusAndDetail(status, "Passkey registration rejected"))
  }

  private class Begin(val key: UUID, val scopeId: RestrictedSessionId)
  private companion object {
    const val BEGIN = "finds.webauthn.additional-begin"
    val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
  }
}
