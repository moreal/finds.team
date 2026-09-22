package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.CrawlLeasePort
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.persistence.jooq.generated.tables.references.CRAWL_LEASES
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.jooq.DSLContext

class JooqCrawlLeasePort(
  private val context: DSLContext,
) : CrawlLeasePort {
  override fun tryAcquire(
    siteId: CareerSiteId,
    owner: String,
    now: Instant,
    ttl: Duration,
  ): Boolean {
    val acquiredAt = now.atOffset(ZoneOffset.UTC)
    val expiresAt = now.plus(ttl).atOffset(ZoneOffset.UTC)
    return context.insertInto(CRAWL_LEASES)
      .set(CRAWL_LEASES.CAREER_SITE_ID, siteId.value)
      .set(CRAWL_LEASES.OWNER, owner)
      .set(CRAWL_LEASES.ACQUIRED_AT, acquiredAt)
      .set(CRAWL_LEASES.EXPIRES_AT, expiresAt)
      .onConflict(CRAWL_LEASES.CAREER_SITE_ID)
      .doUpdate()
      .set(CRAWL_LEASES.OWNER, owner)
      .set(CRAWL_LEASES.ACQUIRED_AT, acquiredAt)
      .set(CRAWL_LEASES.EXPIRES_AT, expiresAt)
      .where(CRAWL_LEASES.EXPIRES_AT.le(acquiredAt))
      .execute() == 1
  }

  override fun release(siteId: CareerSiteId, owner: String) {
    context.deleteFrom(CRAWL_LEASES)
      .where(CRAWL_LEASES.CAREER_SITE_ID.eq(siteId.value))
      .and(CRAWL_LEASES.OWNER.eq(owner))
      .execute()
  }
}
