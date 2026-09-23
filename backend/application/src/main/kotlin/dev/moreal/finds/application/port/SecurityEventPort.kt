package dev.moreal.finds.application.port

import dev.moreal.finds.application.command.CommandMetadata
import java.time.Instant
import java.util.UUID

enum class SecurityEventAction(val wireName: String) {
  REGISTRATION_DENIED("career_site.registration_denied"),
  CRAWL_DENIED("crawl.trigger_denied"),
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

/** Independent committed append, called after any business transaction exits. Failures propagate:
 * callers must never turn a denied operation into a success when security storage is unavailable. */
fun interface SecurityEventPort {
  fun append(event: SecurityEvent)
}

fun SecurityEventPort.denied(action: SecurityEventAction, now: Instant, metadata: CommandMetadata,
  actorUserId: UUID? = null, careerSiteId: Long? = null) = append(SecurityEvent(UUID.randomUUID(), now,
    action, actorUserId, metadata.requestId, metadata.correlationId, careerSiteId))
