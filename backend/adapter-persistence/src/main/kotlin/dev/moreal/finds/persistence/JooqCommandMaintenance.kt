package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.CommandMaintenancePort
import java.time.Instant
import java.time.ZoneOffset
import org.jooq.DSLContext

class JooqCommandMaintenance(private val context: DSLContext) : CommandMaintenancePort {
  override fun purgeExpired(now: Instant, limit: Int): Int {
    require(limit in 1..1000)
    // One atomic statement; skip records locked by an in-flight command or another maintainer.
    return context.execute("""
      WITH expired AS (
        SELECT scope, operation, idempotency_key FROM command_requests
        WHERE retention = 'ORDINARY' AND expires_at <= CAST(? AS timestamptz)
        ORDER BY expires_at, scope, operation, idempotency_key
        LIMIT ? FOR UPDATE SKIP LOCKED
      )
      DELETE FROM command_requests c USING expired e
      WHERE (c.scope, c.operation, c.idempotency_key) = (e.scope, e.operation, e.idempotency_key)
        AND c.retention = 'ORDINARY' AND c.expires_at <= CAST(? AS timestamptz)
    """.trimIndent(), now.atOffset(ZoneOffset.UTC), limit, now.atOffset(ZoneOffset.UTC))
  }
}
