package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.SecurityEvent
import dev.moreal.finds.application.port.SecurityEventPort
import org.jooq.DSLContext
import org.jooq.JSONB
import dev.moreal.finds.persistence.jooq.generated.tables.references.SECURITY_EVENTS
import java.time.ZoneOffset

class JooqSecurityEventLog(private val context: DSLContext) : SecurityEventPort {
  override fun append(event: SecurityEvent) {
    JooqTransactionAdapter.requireOutsideTransaction()
    val settings = (context.settings().clone() as org.jooq.conf.Settings).withExecuteLogging(false)
    val db = org.jooq.impl.DSL.using(context.configuration().derive(settings))
    db.insertInto(SECURITY_EVENTS)
      .set(SECURITY_EVENTS.EVENT_ID, event.id)
      .set(SECURITY_EVENTS.SCHEMA_VERSION, 1)
      .set(SECURITY_EVENTS.OCCURRED_AT, event.occurredAt.atOffset(ZoneOffset.UTC))
      .set(SECURITY_EVENTS.ACTOR_USER_ID, event.actorUserId)
      .set(SECURITY_EVENTS.ACTION, event.action.wireName)
      .set(SECURITY_EVENTS.TARGET_TYPE, event.careerSiteId?.let { "career_site" })
      .set(SECURITY_EVENTS.TARGET_ID, event.careerSiteId?.toString())
      .set(SECURITY_EVENTS.REQUEST_ID, event.requestId)
      .set(SECURITY_EVENTS.CORRELATION_ID, event.correlationId)
      .set(SECURITY_EVENTS.DETAILS, JSONB.valueOf("""{"reason":"${event.action.reason}"}"""))
      .execute()
  }
}
