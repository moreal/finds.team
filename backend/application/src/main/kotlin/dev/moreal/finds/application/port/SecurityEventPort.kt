package dev.moreal.finds.application.port

import dev.moreal.finds.application.command.CommandMetadata
import java.time.Instant
import java.util.UUID

enum class SecurityEventAction(val wireName: String, val reason: String) {
  REGISTRATION_DENIED("career_site.registration_denied", "FORBIDDEN"),
  CRAWL_DENIED("crawl.trigger_denied", "FORBIDDEN"),
  AUTHORIZATION_DENIED("identity.authorization_denied", "FORBIDDEN"),
  OTP_FAILED("identity.otp_failed", "INVALID_OTP"),
  WEBAUTHN_FAILED("identity.webauthn_failed", "AUTHENTICATION_FAILED"),
  CHALLENGE_REPLAY("identity.challenge_replay", "CHALLENGE_REPLAY"),
  AUTH_THROTTLED("identity.throttled", "THROTTLED"),
}

/** Categorical data only. No request bodies, contact addresses or authentication material. */
data class SecurityEvent(
  val id: UUID,
  val occurredAt: Instant,
  val action: SecurityEventAction,
  val actorUserId: UUID?,
  val requestId: UUID,
  val correlationId: UUID,
  val careerSiteId: Long? = null,
) {
  init { require(careerSiteId == null || careerSiteId > 0) }
  override fun toString(): String = "SecurityEvent(action=${action.wireName})"
}

/** Independent committed append, called after any business transaction exits. Failures propagate;
 * an HTTP denial boundary may preserve its denial status, but must never turn it into success. */
fun interface SecurityEventPort {
  fun append(event: SecurityEvent)
}

fun SecurityEventPort.denied(action: SecurityEventAction, now: Instant, metadata: CommandMetadata,
  actorUserId: UUID? = null, careerSiteId: Long? = null) = append(SecurityEvent(UUID.randomUUID(), now,
    action, actorUserId, metadata.requestId, metadata.correlationId, careerSiteId))
