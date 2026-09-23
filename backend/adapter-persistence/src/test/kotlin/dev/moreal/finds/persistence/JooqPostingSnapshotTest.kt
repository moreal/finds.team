package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.model.PageRequest
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.port.CrawlLease
import dev.moreal.finds.application.port.InsertCareerSiteResult
import dev.moreal.finds.domain.career.*
import dev.moreal.finds.domain.crawl.*
import dev.moreal.finds.domain.posting.*
import dev.moreal.finds.domain.search.Filter
import dev.moreal.finds.domain.search.matches
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.SQLDialect
import org.jooq.impl.DSL

class JooqPostingSnapshotTest : PostgresIntegrationTest() {
  @Test
  fun `search returns matching posting and skills from one snapshot while crawl commits after row fetch`() {
    interleavedRead(search = true)
  }

  @Test
  fun `site read returns posting and skills from one snapshot while crawl commits after row fetch`() {
    interleavedRead(search = false)
  }

  @Test
  fun `posting reads use the caller transaction and do not leak rolled back enrichment`() {
    val (_, db) = migratedContext()
    val site = seed(db)
    assertFailsWith<RollbackRead> {
      db.transaction { configuration ->
        val transaction = DSL.using(configuration)
        crawl(transaction, site, "Rust required", NOW.plusSeconds(1))
        val repository = JooqPostingRepository(transaction)
        assertEquals(2, repository.search(Filter.HasSkill("rust"), PageRequest(10)).totalCount)
        assertTrue(repository.findByCareerSite(site).all(Filter.HasSkill("rust")::matches))
        throw RollbackRead()
      }
    }
    val repository = JooqPostingRepository(db)
    assertEquals(2, repository.search(Filter.HasSkill("kotlin"), PageRequest(10)).totalCount)
    assertEquals(0, repository.search(Filter.HasSkill("rust"), PageRequest(10)).totalCount)
  }

  private fun interleavedRead(search: Boolean) {
    val (source, db) = migratedContext()
    val site = seed(db)
    val before = JooqPostingRepository(db).findByCareerSite(site)
    val fetchedRows = CountDownLatch(1)
    val writerFinished = CountDownLatch(1)
    val crossedBoundary = AtomicBoolean(false)
    val selectCount = AtomicInteger()
    val pool = Executors.newSingleThreadExecutor()
    val writer = pool.submit {
      try {
        check(fetchedRows.await(10, TimeUnit.SECONDS)) { "Reader never reached its posting-row boundary" }
        source.connection.use { connection ->
          crawl(DSL.using(connection, SQLDialect.POSTGRES), site, "Rust required", NOW.plusSeconds(1))
        }
      } finally { writerFinished.countDown() }
    }
    try {
      var page: SearchPage? = null
      val result = source.connection.use { connection ->
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        val configuration = DSL.using(connection, SQLDialect.POSTGRES).configuration()
          .derive(object : ExecuteListener {
            override fun executeStart(ctx: ExecuteContext) {
              if (ctx.sql()?.startsWith("select", ignoreCase = true) == true) selectCount.incrementAndGet()
            }

            override fun fetchEnd(ctx: ExecuteContext) {
              // The reader has materialized posting columns. A second connection now commits
              // before a separate skill SELECT could acquire a newer READ COMMITTED snapshot.
              // With one statement, its nested skill rows were already in that same snapshot.
              if (ctx.sql()?.contains("content_hash") == true && crossedBoundary.compareAndSet(false, true)) {
                fetchedRows.countDown()
                check(writerFinished.await(10, TimeUnit.SECONDS)) { "Concurrent crawl did not complete" }
              }
            }
          })
        val repository = JooqPostingRepository(DSL.using(configuration))
        if (search) {
          repository.search(Filter.HasSkill("kotlin"), PageRequest(1)).also { page = it }.postings
        } else repository.findByCareerSite(site)
      }
      writer.get(10, TimeUnit.SECONDS)
      assertTrue(crossedBoundary.get(), "The write must interleave after the posting SELECT")
      val expected = if (search) before.takeLast(1) else before
      assertEquals(expected.map { it.raw }, result.map { it.raw })
      assertEquals(expected.map { it.classification }, result.map { it.classification },
        "Posting columns and skill associations must belong to the same snapshot")
      assertTrue(result.all(Filter.HasSkill("kotlin")::matches), "Returned objects must satisfy the SQL skill filter")
      if (search) {
        val searchPage = requireNotNull(page)
        assertEquals(2, searchPage.totalCount)
        assertEquals(before.last().id, searchPage.next!!.id)
        assertEquals(NOW, searchPage.next!!.updatedAt)
      }
      assertEquals(1, selectCount.get(), "One SQL snapshot must cover count, page and associations")
      val fresh = JooqPostingRepository(db).search(Filter.HasSkill("rust"), PageRequest(10))
      assertEquals(2, fresh.totalCount)
      assertTrue(fresh.postings.all(Filter.HasSkill("rust")::matches))
    } finally {
      fetchedRows.countDown()
      writerFinished.countDown()
      writer.cancel(true)
      pool.shutdownNow()
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Writer must release its connection")
    }
  }

  private fun seed(db: DSLContext): CareerSiteId {
    val site = assertIs<InsertCareerSiteResult.Inserted>(JooqCareerSiteRepository(db).insert(
      NewCareerSite(assertIs<SiteUrlResult.Valid>(SiteUrl.parse("https://jobs.example")).url,
        SourceProvider.FLEX, "Snapshot"))).site.id
    JooqCrawlLeasePort(db).tryAcquire(site, "snapshot", NOW.minusSeconds(1), Duration.ofHours(1))
    crawl(db, site, "Kotlin required", NOW)
    return site
  }

  private fun crawl(db: DSLContext, site: CareerSiteId, description: String, now: Instant) {
    val raws = listOf("a", "b").map { key -> RawPosting(key, "Backend $key", description,
      assertIs<PostingUrlResult.Valid>(PostingUrl.parse("https://jobs.example/$key")).url) }
    val plan = assertIs<ReconciliationResult.Success>(reconcile(JooqPostingRepository(db).findByCareerSite(site),
      Snapshot(site, SiteHost("jobs.example"), now, raws), ClosePolicy(2), now)).plan
    JooqSuccessfulCrawlAdapter(db).applyAndComplete(JooqCrawlRunRepository(db).start(site, now),
      CrawlLease(site, "snapshot", NOW.plusSeconds(3599)), plan, raws.size, now)
  }

  private class RollbackRead : RuntimeException()
  private companion object { val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z") }
}
