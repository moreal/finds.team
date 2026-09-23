package dev.moreal.finds.persistence

import dev.moreal.finds.application.audit.AuditEvent
import dev.moreal.finds.application.port.AuditLog
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.persistence.jooq.generated.tables.references.AUDIT_EVENTS
import java.time.ZoneOffset
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.JSONB

internal class JooqAuditLog(private val db: DSLContext, private val scope: TransactionScope) : AuditLog {
  override fun append(event: AuditEvent) = scope.access {
    val user = event.actor as? Actor.User
    db.insertInto(AUDIT_EVENTS)
      .set(AUDIT_EVENTS.EVENT_ID, event.id)
      .set(AUDIT_EVENTS.SCHEMA_VERSION, event.schemaVersion)
      .set(AUDIT_EVENTS.OCCURRED_AT, event.occurredAt.atOffset(ZoneOffset.UTC))
      .set(AUDIT_EVENTS.ACTOR_KIND, if (user != null) "USER" else "SYSTEM")
      .set(AUDIT_EVENTS.ACTOR_USER_ID, user?.userId)
      .set(AUDIT_EVENTS.ACTION, event.action.wireName)
      .set(AUDIT_EVENTS.TARGET_TYPE, event.targetType)
      .set(AUDIT_EVENTS.TARGET_ID, event.targetId)
      .set(AUDIT_EVENTS.REQUEST_ID, event.requestId)
      .set(AUDIT_EVENTS.CORRELATION_ID, event.correlationId)
      .set(AUDIT_EVENTS.OUTCOME, event.outcome.wireName)
      .set(AUDIT_EVENTS.DETAILS, JSONB.valueOf(buildJsonObject {
        event.details.fields.forEach { (key, value) -> put(key, value) }
      }.toString()))
      .execute()
    Unit
  }
}
