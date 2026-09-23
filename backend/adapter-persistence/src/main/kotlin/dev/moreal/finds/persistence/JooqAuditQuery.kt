package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.audit.*
import dev.moreal.finds.persistence.jooq.generated.tables.references.AUDIT_EVENTS
import java.time.ZoneOffset
import kotlinx.serialization.json.*
import org.jooq.DSLContext
import org.jooq.impl.DSL

class JooqAuditQuery(private val context: DSLContext) : AuditQueryPort {
  override fun search(query: AuditSearch): AuditPage {
    val a = AUDIT_EVENTS
    var condition = DSL.noCondition()
    query.actorUserId?.let { condition = condition.and(a.ACTOR_USER_ID.eq(it)) }
    query.actorKind?.let { condition = condition.and(a.ACTOR_KIND.eq(it.name)) }
    query.action?.let { condition = condition.and(a.ACTION.eq(it.wireName)) }
    query.targetType?.let { condition = condition.and(a.TARGET_TYPE.eq(it)) }
    query.targetId?.let { condition = condition.and(a.TARGET_ID.eq(it)) }
    query.from?.let { condition = condition.and(a.OCCURRED_AT.ge(it.atOffset(ZoneOffset.UTC))) }
    query.until?.let { condition = condition.and(a.OCCURRED_AT.lt(it.atOffset(ZoneOffset.UTC))) }
    query.after?.let { condition = condition.and(DSL.row(a.OCCURRED_AT, a.EVENT_ID)
      .lt(DSL.row(it.occurredAt.atOffset(ZoneOffset.UTC), it.eventId))) }
    val records = context.selectFrom(a).where(condition).orderBy(a.OCCURRED_AT.desc(), a.EVENT_ID.desc())
      .limit(query.limit + 1).fetch()
    val events = records.take(query.limit).map { r ->
      val action = AuditAction.fromWireName(requireNotNull(r.action))
      val fields = Json.parseToJsonElement(requireNotNull(r.details).data()).jsonObject
        .mapValues { (_, value) -> value.jsonPrimitive.also { require(it.isString) }.content }
      AuditRecord(requireNotNull(r.eventId), requireNotNull(r.schemaVersion), requireNotNull(r.occurredAt).toInstant(),
        AuditActorKind.valueOf(requireNotNull(r.actorKind)), r.actorUserId, action, requireNotNull(r.targetType),
        requireNotNull(r.targetId), requireNotNull(r.requestId), requireNotNull(r.correlationId),
        AuditOutcome.entries.single { it.wireName == r.outcome }, AuditDetails.from(action, fields))
    }
    val cursor = if (records.size > query.limit) events.last().let { AuditCursor(it.occurredAt, it.id) } else null
    return AuditPage(events, cursor)
  }
}
