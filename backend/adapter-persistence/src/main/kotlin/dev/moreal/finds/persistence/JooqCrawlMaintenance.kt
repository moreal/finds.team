package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.CrawlMaintenancePort
import java.time.Instant
import java.time.ZoneOffset
import org.jooq.DSLContext

class JooqCrawlMaintenance(private val context: DSLContext) : CrawlMaintenancePort {
  override fun expireAbandoned(now: Instant, startedBefore: Instant, limit: Int): Int {
    require(limit in 1..1000)
    require(startedBefore <= now)
    // The run lock also serializes completion. A late worker cannot apply postings after this
    // terminal state. A newer lease belongs to another run and must not keep the old one pending.
    return context.execute("""
      WITH abandoned AS (
        SELECT r.id FROM crawl_runs r
        WHERE r.finished_at IS NULL AND r.started_at <= CAST(? AS timestamptz)
          AND NOT EXISTS (SELECT 1 FROM crawl_leases l WHERE l.career_site_id = r.career_site_id
            AND l.acquired_at <= r.started_at AND l.expires_at > CAST(? AS timestamptz))
        ORDER BY r.started_at, r.id LIMIT ? FOR UPDATE OF r SKIP LOCKED
      )
      UPDATE crawl_runs r SET finished_at = CAST(? AS timestamptz), outcome = 'FAILED',
        failure_code = 'LEASE_EXPIRED', failure_message = 'Crawl lease expired before completion'
      FROM abandoned a WHERE r.id = a.id AND r.finished_at IS NULL
    """.trimIndent(), startedBefore.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), limit, now.atOffset(ZoneOffset.UTC))
  }
}
