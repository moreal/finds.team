package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.port.InsertCareerSiteResult
import dev.moreal.finds.application.port.CrawlLease
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.crawl.ClosePolicy
import dev.moreal.finds.domain.crawl.NewPosting
import dev.moreal.finds.domain.crawl.PostingRef
import dev.moreal.finds.domain.crawl.PostingTouch
import dev.moreal.finds.domain.crawl.ReconciliationResult
import dev.moreal.finds.domain.crawl.Snapshot
import dev.moreal.finds.domain.crawl.SyncPlan
import dev.moreal.finds.domain.crawl.reconcile
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.posting.PostingUrl
import dev.moreal.finds.domain.posting.PostingUrlResult
import dev.moreal.finds.domain.posting.RawPosting
import dev.moreal.finds.persistence.jooq.generated.tables.references.CRAWL_RUNS
import dev.moreal.finds.persistence.jooq.generated.tables.references.JOB_POSTINGS
import java.time.Instant
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JooqSuccessfulCrawlAdapterTest : PostgresIntegrationTest() {
  @Test
  fun `concurrent completion serializes on the run and applies once`() {
    val (dataSource, context) = migratedContext()
    val siteId = insertSite(context)
    val run = JooqCrawlRunRepository(context).start(siteId, NOW)
    val lease = lease(context, siteId)
    val raw = raw("once")
    val plan = SyncPlan(insert = listOf(NewPosting(raw, raw.contentHash(), NOW)))
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val results = List(2) {
        pool.submit {
          check(start.await(5, TimeUnit.SECONDS))
          JooqSuccessfulCrawlAdapter(
            org.jooq.impl.DSL.using(dataSource, org.jooq.SQLDialect.POSTGRES),
          ).applyAndComplete(run, lease, plan, 1, NOW)
        }
      }
      start.countDown()
      assertEquals(results[0].get(10, TimeUnit.SECONDS), results[1].get(10, TimeUnit.SECONDS))
      assertEquals(1, context.fetchCount(JOB_POSTINGS))
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `completion requires its own unexpired lease before any posting write`() {
    for (case in listOf("expired", "replaced", "absent", "wrong-site")) {
      val (_, context) = migratedContext()
      val siteId = insertSite(context)
      val run = JooqCrawlRunRepository(context).start(siteId, NOW)
      val lease = lease(context, siteId)
      when (case) {
        "replaced" -> context.execute("UPDATE crawl_leases SET owner = 'new-reservation'")
        "absent" -> JooqCrawlLeasePort(context).release(siteId, lease.owner)
      }
      val completionLease = if (case == "wrong-site") lease.copy(siteId = CareerSiteId(999)) else lease
      val finishedAt = if (case == "expired") lease.expiresAt else NOW
      val raw = raw("must-not-write")
      assertFailsWith<IllegalStateException>(case) {
        JooqSuccessfulCrawlAdapter(context).applyAndComplete(run, completionLease,
          SyncPlan(insert = listOf(NewPosting(raw, raw.contentHash(), NOW))), 1, finishedAt)
      }
      assertEquals(0, context.fetchCount(JOB_POSTINGS), case)
      assertEquals(null, context.select(CRAWL_RUNS.OUTCOME).from(CRAWL_RUNS)
        .where(CRAWL_RUNS.ID.eq(run.value)).fetchOne(CRAWL_RUNS.OUTCOME), case)
    }
  }

  @Test
  fun `mixed sync plan is atomic complete and idempotent`() {
    val (_, context) = migratedContext()
    val siteId = insertSite(context)
    val runs = JooqCrawlRunRepository(context)
    val completion = JooqSuccessfulCrawlAdapter(context)
    val lease = lease(context, siteId)
    val seedRun = runs.start(siteId, NOW.minusSeconds(20))
    val seedRaw = listOf(raw("update", "Old"), raw("touch"), raw("missing"), raw("close"), raw("reopen"))
    val seedPlan = SyncPlan(insert = seedRaw.map { NewPosting(it, it.contentHash(), NOW.minusSeconds(10)) })
    completion.applyAndComplete(seedRun, lease, seedPlan, seedRaw.size, NOW.minusSeconds(9))

    val repository = JooqPostingRepository(context)
    val seeded = repository.findByCareerSite(siteId).associateBy { it.raw.externalKey }
    context.update(JOB_POSTINGS)
      .set(JOB_POSTINGS.CONSECUTIVE_MISSES, 1)
      .where(JOB_POSTINGS.ID.eq(seeded.getValue("close").id.value))
      .execute()
    context.update(JOB_POSTINGS)
      .set(JOB_POSTINGS.STATUS, PostingStatus.CLOSED.name)
      .set(JOB_POSTINGS.CLOSED_AT, NOW.minusSeconds(5).atOffset(ZoneOffset.UTC))
      .where(JOB_POSTINGS.ID.eq(seeded.getValue("reopen").id.value))
      .execute()

    val existing = repository.findByCareerSite(siteId)
    val snapshot = Snapshot(
      siteId,
      SiteHost("jobs.example"),
      NOW,
      listOf(raw("update", "New"), raw("touch"), raw("reopen", "Reopened"), raw("insert")),
    )
    val plan = assertIs<ReconciliationResult.Success>(
      reconcile(existing, snapshot, ClosePolicy(2), NOW),
    ).plan
    val run = runs.start(siteId, NOW.minusSeconds(1))

    val counts = completion.applyAndComplete(run, lease, plan, snapshot.postings.size, NOW)
    val retryCounts = completion.applyAndComplete(run, lease, plan, snapshot.postings.size, NOW.plusSeconds(1))

    assertEquals(counts, retryCounts)
    assertEquals(listOf(1, 1, 1, 1, 1, 1, 4), listOf(
      counts.inserted, counts.updated, counts.touched, counts.missing,
      counts.closed, counts.reopened, counts.fetched,
    ))
    val stored = repository.findByCareerSite(siteId).associateBy { it.raw.externalKey }
    assertEquals(6, stored.size)
    assertEquals("New", stored.getValue("update").raw.title)
    assertEquals(1, stored.getValue("missing").consecutiveMisses)
    assertEquals(PostingStatus.CLOSED, stored.getValue("close").status)
    assertEquals(PostingStatus.OPEN, stored.getValue("reopen").status)
    assertEquals("SUCCESS", context.select(CRAWL_RUNS.OUTCOME).from(CRAWL_RUNS)
      .where(CRAWL_RUNS.ID.eq(run.value)).fetchOne(CRAWL_RUNS.OUTCOME))
  }

  @Test
  fun `late ownership failure rolls back postings and run completion`() {
    val (_, context) = migratedContext()
    val siteId = insertSite(context)
    val run = JooqCrawlRunRepository(context).start(siteId, NOW)
    val lease = lease(context, siteId)
    val inserted = raw("new")
    val invalidPlan = SyncPlan(
      insert = listOf(NewPosting(inserted, inserted.contentHash(), NOW)),
      touch = listOf(PostingTouch(PostingRef(dev.moreal.finds.domain.posting.JobPostingId(999), "missing"), NOW)),
    )

    assertFailsWith<IllegalStateException> {
      JooqSuccessfulCrawlAdapter(context).applyAndComplete(run, lease, invalidPlan, 1, NOW)
    }

    assertEquals(0, context.fetchCount(JOB_POSTINGS))
    assertEquals(null, context.select(CRAWL_RUNS.OUTCOME).from(CRAWL_RUNS)
      .where(CRAWL_RUNS.ID.eq(run.value)).fetchOne(CRAWL_RUNS.OUTCOME))
  }

  private fun lease(context: org.jooq.DSLContext, siteId: CareerSiteId): CrawlLease {
    val acquired = NOW.minusSeconds(30)
    JooqCrawlLeasePort(context).tryAcquire(siteId, "test-lease", acquired, Duration.ofMinutes(5))
    return CrawlLease(siteId, "test-lease", acquired.plusSeconds(300))
  }

  private fun insertSite(context: org.jooq.DSLContext): CareerSiteId =
    assertIs<InsertCareerSiteResult.Inserted>(
      JooqCareerSiteRepository(context).insert(
        NewCareerSite(url("https://jobs.example"), SourceProvider.NINEHIRE, "Acme"),
      ),
    ).site.id

  private fun raw(key: String, title: String = "Title $key") = RawPosting(
    key,
    title,
    "Description $key",
    postingUrl("https://jobs.example/job/$key"),
  )

  private fun url(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url
  private fun postingUrl(value: String): PostingUrl =
    assertIs<PostingUrlResult.Valid>(PostingUrl.parse(value)).url

  private companion object {
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
  }
}
