package dev.moreal.finds.application.port

import dev.moreal.finds.application.audit.*
import java.time.Instant
import java.util.UUID

enum class AuditActorKind { USER, SYSTEM }

/** Historical identity only; a ledger row cannot reconstruct live roles or session credentials. */
data class AuditRecord(
  val id: UUID, val schemaVersion: Int, val occurredAt: Instant,
  val actorKind: AuditActorKind, val actorUserId: UUID?, val action: AuditAction,
  val targetType: String, val targetId: String, val requestId: UUID, val correlationId: UUID,
  val outcome: AuditOutcome, val details: AuditDetails,
)

data class AuditCursor(val occurredAt: Instant, val eventId: UUID)

data class AuditSearch(
  val actorUserId: UUID? = null,
  val actorKind: AuditActorKind? = null,
  val action: AuditAction? = null,
  val targetType: String? = null,
  val targetId: String? = null,
  val from: Instant? = null,
  val until: Instant? = null,
  val after: AuditCursor? = null,
  val limit: Int = 50,
) {
  init {
    require(limit in 1..100)
    require(from == null || until == null || from < until)
    require(targetId == null || targetType != null)
    require(targetType == null || targetType.matches(Regex("[a-z][a-z0-9_]*")))
    require(targetId == null || targetId.matches(Regex("[A-Za-z0-9:_-]{1,128}")))
  }
}

/** Descending (occurredAt, eventId); from is inclusive and until is exclusive. */
data class AuditPage(val events: List<AuditRecord>, val nextCursor: AuditCursor?)

fun interface AuditQueryPort { fun search(query: AuditSearch): AuditPage }
