package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.port.InsertCareerSiteResult
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.crawl.CrawlOutcome
import dev.moreal.finds.persistence.jooq.generated.tables.references.CRAWL_RUNS
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JooqCrawlStateTest : PostgresIntegrationTest() {
  @Test
  fun `failed runs build retry history and latest statuses`() {
    val (_, context) = migratedContext()
    val site = insertedSite(JooqCareerSiteRepository(context))
    val runs = JooqCrawlRunRepository(context)
    val first = runs.start(site.id, NOW.minusSeconds(60))
    runs.fail(first, CrawlFailure(CrawlFailureCode.TIMEOUT, "slow"), NOW.minusSeconds(50))
    val second = runs.start(site.id, NOW.minusSeconds(20))
    runs.fail(second, CrawlFailure(CrawlFailureCode.PARSE_FAILED, "shape"), NOW)

    val history = requireNotNull(runs.latestHistory(site.id))
    assertEquals(CrawlOutcome.FAILED, history.lastOutcome)
    assertEquals(2, history.consecutiveFailures)
    val status = runs.latestStatuses().single()
    assertEquals(second, status.runId)
    assertEquals(CrawlFailureCode.PARSE_FAILED, status.failure?.code)
  }

  @Test
  fun `run completion is single assignment and success resets failure count`() {
    val (_, context) = migratedContext()
    val site = insertedSite(JooqCareerSiteRepository(context))
    val runs = JooqCrawlRunRepository(context)
    val failed = runs.start(site.id, NOW.minusSeconds(30))
    runs.fail(failed, CrawlFailure(CrawlFailureCode.TIMEOUT, "slow"), NOW.minusSeconds(20))
    val success = runs.start(site.id, NOW.minusSeconds(10))
    context.update(CRAWL_RUNS)
      .set(CRAWL_RUNS.FINISHED_AT, NOW.atOffset(ZoneOffset.UTC))
      .set(CRAWL_RUNS.OUTCOME, CrawlOutcome.SUCCESS.name)
      .where(CRAWL_RUNS.ID.eq(success.value))
      .execute()

    val history = requireNotNull(runs.latestHistory(site.id))
    assertEquals(CrawlOutcome.SUCCESS, history.lastOutcome)
    assertEquals(0, history.consecutiveFailures)
    assertFailsWith<IllegalStateException> {
      runs.fail(success, CrawlFailure(CrawlFailureCode.TIMEOUT, "late"), NOW)
    }
  }

  @Test
  fun `lease acquisition is atomic expires and releases only for owner`() {
    val (_, context) = migratedContext()
    val site = insertedSite(JooqCareerSiteRepository(context))
    val leases = JooqCrawlLeasePort(context)

    assertTrue(leases.tryAcquire(site.id, "worker-1", NOW, Duration.ofMinutes(2)))
    assertFalse(leases.tryAcquire(site.id, "worker-1", NOW, Duration.ofMinutes(2)))
    assertFalse(leases.tryAcquire(site.id, "worker-2", NOW, Duration.ofMinutes(2)))
    leases.release(site.id, "wrong")
    assertFalse(leases.tryAcquire(site.id, "worker-2", NOW, Duration.ofMinutes(2)))
    assertTrue(
      leases.tryAcquire(site.id, "worker-2", NOW.plusSeconds(121), Duration.ofMinutes(2)),
    )
    leases.release(site.id, "worker-2")
    assertTrue(leases.tryAcquire(site.id, "worker-3", NOW.plusSeconds(122), Duration.ofMinutes(2)))
  }

  @Test
  fun `concurrent lease contenders have exactly one winner`() {
    val (dataSource, context) = migratedContext()
    val site = insertedSite(JooqCareerSiteRepository(context))
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val results = listOf("one", "two").map { owner ->
        pool.submit<Boolean> {
          start.await()
          JooqCrawlLeasePort(org.jooq.impl.DSL.using(dataSource, org.jooq.SQLDialect.POSTGRES))
            .tryAcquire(site.id, owner, NOW, Duration.ofMinutes(2))
        }
      }
      start.countDown()
      assertEquals(1, results.count { it.get() })
    } finally {
      pool.shutdownNow()
    }
  }

  private fun insertedSite(repository: JooqCareerSiteRepository) =
    assertIs<InsertCareerSiteResult.Inserted>(
      repository.insert(NewCareerSite(url("https://jobs.example"), SourceProvider.NINEHIRE, "Acme")),
    ).site

  private fun url(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url

  private companion object {
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
  }
}
