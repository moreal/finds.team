package dev.moreal.finds.persistence

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.*
import dev.moreal.finds.application.usecase.*
import dev.moreal.finds.domain.career.*
import dev.moreal.finds.domain.crawl.*
import dev.moreal.finds.domain.identity.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.*

class JooqCrawlCommandTest : PostgresIntegrationTest() {
  @Test fun `crash after committed reservation replays after restart and expires before later scheduler window`() {
    val (_, db) = migratedContext()
    var time = NOW
    var fetches = 0
    val clock = ClockPort { time }
    val tx = JooqTransactionAdapter(db) { null }
    val crashAfterCommit = object : TransactionPort {
      override fun <T> execute(block: (TransactionContext) -> T): T {
        tx.execute(block)
        throw SimulatedCrash()
      }
    }
    val f = fixture(db) { error("fixture must not fetch") }
    val source = SourceFetchPort { site ->
      fetches++
      SourceFetchResult.Success(Snapshot(site.id, site.canonicalBaseUrl.host, time, emptyList()))
    }
    assertFailsWith<SimulatedCrash> { runBlocking { service(db, source, clock, crashAfterCommit).execute(f.command) } }
    assertCounts(db, 1)
    assertEquals(0, fetches)
    val runId = db.fetchValue("SELECT id FROM crawl_runs") as Long
    assertNull(JooqCrawlRunRepository(db).latestStatuses().single().outcome)
    // The restarted process has fresh adapter instances, but no in-memory continuation.
    val restarted = service(db, source, clock, JooqTransactionAdapter(db) { null })
    assertEquals(CrawlSiteResult.Triggered(dev.moreal.finds.application.model.CrawlRunId(runId)), runBlocking { restarted.execute(f.command) })
    val cleanup = ExpireAbandonedCrawlRuns(JooqCrawlMaintenance(db), clock, Duration.ofMinutes(5))
    time = NOW.plusSeconds(299)
    assertEquals(0, cleanup.execute(1))
    time = NOW.plusSeconds(300)
    assertEquals(1, cleanup.execute(1))
    val status = JooqCrawlRunRepository(db).latestStatuses().single()
    assertEquals(CrawlOutcome.FAILED, status.outcome)
    assertEquals("LEASE_EXPIRED", status.failure!!.code.name)
    assertEquals(0, cleanup.execute(1))
    assertEquals(CrawlSiteResult.Triggered(dev.moreal.finds.application.model.CrawlRunId(runId)), runBlocking { restarted.execute(f.command) })
    assertEquals(0, fetches)
    time = NOW.plusSeconds(900)
    val later = f.command.copy(actor = Actor.System, sessionId = null, trigger = CrawlTrigger.SCHEDULED,
      metadata = CommandMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.nameUUIDFromBytes("later-window".toByteArray())))
    assertIs<CrawlSiteResult.Succeeded>(runBlocking { restarted.execute(later) })
    assertIs<CrawlSiteResult.Triggered>(runBlocking { restarted.execute(later) })
    assertEquals(1, fetches)
    assertEquals(2, db.fetchCount(DSL.table("crawl_runs")))
    assertEquals(1, db.fetchCount(DSL.table("audit_events")))
    assertEquals(CrawlOutcome.SUCCESS, JooqCrawlRunRepository(db).latestStatuses().single().outcome)
  }

  @Test fun `orphan cleanup is bounded respects live leases and fences late completion`() {
    val (_, db) = migratedContext()
    val f = fixture(db) { error("unexpected fetch") }
    val runs = JooqCrawlRunRepository(db)
    val leases = JooqCrawlLeasePort(db)
    val a = runs.start(f.command.siteId, NOW)
    val b = runs.start(f.command.siteId, NOW)
    leases.tryAcquire(f.command.siteId, "live", NOW, Duration.ofMinutes(10))
    val cleanup = JooqCrawlMaintenance(db)
    assertFailsWith<IllegalArgumentException> { cleanup.expireAbandoned(NOW, NOW, 1001) }
    assertEquals(0, cleanup.expireAbandoned(NOW.plusSeconds(300), NOW, 1))
    assertEquals(1, cleanup.expireAbandoned(NOW.plusSeconds(600), NOW, 1))
    assertEquals(1, db.fetchValue("SELECT count(*)::int FROM crawl_runs WHERE outcome IS NULL"))
    assertEquals(1, cleanup.expireAbandoned(NOW.plusSeconds(600), NOW, 1))
    assertEquals(1, db.fetchCount(DSL.table("crawl_leases")))
    assertFailsWith<IllegalStateException> {
      JooqSuccessfulCrawlAdapter(db).applyAndComplete(a, SyncPlan(), 0, NOW.plusSeconds(601))
    }
    assertEquals(0, db.fetchCount(DSL.table("job_postings")))
    assertEquals("FAILED", db.fetchValue("SELECT outcome FROM crawl_runs WHERE id = ?", b.value))
  }

  private class SimulatedCrash : Error("simulated process death after commit")

  @Test fun `expired worker finishing cannot release the later run lease of the same dispatcher`() {
    val (_, db) = migratedContext()
    val time = java.util.concurrent.atomic.AtomicReference(NOW)
    val started = listOf(CountDownLatch(1), CountDownLatch(1))
    val release = listOf(CountDownLatch(1), CountDownLatch(1))
    val calls = java.util.concurrent.atomic.AtomicInteger()
    val f = fixture(db) { error("fixture must not fetch") }
    val source = SourceFetchPort { site ->
      val call = calls.getAndIncrement()
      started[call].countDown()
      check(release[call].await(10, TimeUnit.SECONDS))
      SourceFetchResult.Success(Snapshot(site.id, site.canonicalBaseUrl.host, time.get(), emptyList()))
    }
    val useCase = service(db, source, ClockPort(time::get), JooqTransactionAdapter(db) { null })
    Executors.newFixedThreadPool(2).use { pool ->
      try {
        val first = pool.submit<CrawlSiteResult> { runBlocking { useCase.execute(f.command) } }
        assertTrue(started[0].await(10, TimeUnit.SECONDS))
        time.set(NOW.plusSeconds(300))
        assertEquals(1, JooqCrawlMaintenance(db).expireAbandoned(time.get(), NOW, 1))
        time.set(NOW.plusSeconds(900))
        val later = f.command.copy(actor = Actor.System, trigger = CrawlTrigger.SCHEDULED, sessionId = null,
          metadata = CommandMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        val second = pool.submit<CrawlSiteResult> { runBlocking { useCase.execute(later) } }
        assertTrue(started[1].await(10, TimeUnit.SECONDS))
        release[0].countDown()
        assertIs<CrawlSiteResult.Triggered>(first.get(10, TimeUnit.SECONDS))
        assertEquals(1, db.fetchCount(DSL.table("crawl_leases")), "late old worker removed current lease")
        assertEquals(0, db.fetchCount(DSL.table("job_postings")))
        release[1].countDown()
        assertIs<CrawlSiteResult.Succeeded>(second.get(10, TimeUnit.SECONDS))
        assertEquals(0, db.fetchCount(DSL.table("crawl_leases")))
      } finally { release.forEach(CountDownLatch::countDown); pool.shutdownNow() }
    }
  }
  @Test fun `concurrent retry returns committed run while original transport is still blocked`() {
    val (_, db) = migratedContext()
    val fetching = CountDownLatch(1)
    val release = CountDownLatch(1)
    val f = fixture(db) { site ->
      // A different connection sees every committed trigger row before source transport runs.
      assertCounts(db, 1)
      fetching.countDown()
      check(release.await(10, TimeUnit.SECONDS))
      SourceFetchResult.Success(Snapshot(site.id, site.canonicalBaseUrl.host, NOW, emptyList()))
    }
    Executors.newFixedThreadPool(2).use { pool ->
      val original = pool.submit<CrawlSiteResult> { runBlocking { f.crawl.execute(f.command) } }
      try {
        assertTrue(fetching.await(10, TimeUnit.SECONDS))
        val replay = pool.submit<CrawlSiteResult> { runBlocking { f.crawl.execute(f.command) } }
          .get(5, TimeUnit.SECONDS)
        assertIs<CrawlSiteResult.Triggered>(replay)
        assertEquals(CrawlSiteResult.IdempotencyConflict,
          runBlocking { f.crawl.execute(f.command.copy(siteId = CareerSiteId(999))) })
        release.countDown()
        assertEquals(replay, original.get(10, TimeUnit.SECONDS))
        assertCounts(db, 1)
        assertEquals(0, db.fetchCount(DSL.table("crawl_leases")))
      } finally { release.countDown(); pool.shutdownNow() }
    }
  }

  @Test fun `audit database failure rolls back run lease and request before any transport`() {
    val (_, db) = migratedContext()
    var fetches = 0
    val f = fixture(db) { fetches++; error("unexpected transport") }
    db.execute("ALTER TABLE audit_events ADD CONSTRAINT reject_crawl CHECK (action <> 'crawl.manually_triggered')")
    assertIs<CrawlSiteResult.InfrastructureFailure>(runBlocking { f.crawl.execute(f.command) })
    assertCounts(db, 0)
    assertEquals(0, db.fetchCount(DSL.table("crawl_leases")))
    assertEquals(0, fetches)
    db.execute("ALTER TABLE audit_events DROP CONSTRAINT reject_crawl")
    assertIs<CrawlSiteResult.Triggered>(runBlocking { f.crawl.execute(f.command) })
    assertEquals(1, fetches)
    assertCounts(db, 1)
    assertEquals("FAILED", db.fetchValue("SELECT outcome FROM crawl_runs"))
    assertIs<CrawlSiteResult.Triggered>(runBlocking { f.crawl.execute(f.command) })
    assertEquals(1, fetches)
  }

  @Test fun `authorization denial writes separate categorical event and security database failure stays closed`() {
    val (_, db) = migratedContext()
    val f = fixture(db) { error("denied command fetched") }
    val denied = f.command.copy(actor = Actor.System)
    assertEquals(CrawlSiteResult.Forbidden, runBlocking { f.crawl.execute(denied) })
    assertCounts(db, 0)
    assertEquals(1, db.fetchCount(DSL.table("security_events")))
    assertEquals("{\"reason\": \"FORBIDDEN\"}", db.fetchValue("SELECT details::text FROM security_events"))
    db.execute("ALTER TABLE security_events ADD CONSTRAINT reject_denials CHECK (false) NOT VALID")
    assertFailsWith<DataAccessException> { runBlocking { f.crawl.execute(denied) } }
    assertCounts(db, 0)
  }

  @Test fun `security adapter forbids in-transaction appends and restricted role cannot mutate events`() {
    val source = resetPublicSchema() as PGSimpleDataSource
    val owner = DSL.using(source, SQLDialect.POSTGRES)
    owner.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'finds_app') THEN CREATE ROLE finds_app LOGIN PASSWORD 'task4-runtime'; END IF; END $$")
    Flyway.configure().dataSource(source).load().migrate()
    val runtime = PGSimpleDataSource().apply { setURL(source.getURL()); user = "finds_app"; password = "task4-runtime" }
    val db = DSL.using(runtime, SQLDialect.POSTGRES)
    val log = JooqSecurityEventLog(db)
    val event = SecurityEvent(UUID.randomUUID(), NOW, SecurityEventAction.REGISTRATION_DENIED, null,
      UUID.randomUUID(), UUID.randomUUID())
    assertFailsWith<IllegalStateException> { JooqTransactionAdapter(db) { null }.execute { log.append(event) } }
    assertEquals(0, db.fetchCount(DSL.table("security_events")))
    log.append(event)
    assertEquals(1, db.fetchCount(DSL.table("security_events")))
    for (sql in listOf("UPDATE security_events SET details = '{}'", "DELETE FROM security_events", "TRUNCATE security_events"))
      assertEquals("42501", assertFailsWith<DataAccessException> { db.execute(sql) }.sqlState())
    assertEquals(0, db.fetchCount(DSL.table("audit_events")))
  }

  @Test fun `crawl result codec rejects versions outcomes extra identifiers and wrong identifier type`() {
    for (result in listOf(
      StoredCommandResult(2, "crawl.trigger", "TRIGGERED", mapOf("crawl_run" to CommandResourceId.Number(1))),
      StoredCommandResult(1, "crawl.trigger", "SUCCEEDED", mapOf("crawl_run" to CommandResourceId.Number(1))),
      StoredCommandResult(1, "crawl.trigger", "TRIGGERED", mapOf("crawl_run" to CommandResourceId.Uuid(UUID.randomUUID()))),
      StoredCommandResult(1, "crawl.trigger", "TRIGGERED", mapOf("session" to CommandResourceId.Uuid(UUID.randomUUID()))),
      StoredCommandResult(1, "crawl.trigger", "TRIGGERED"),
    )) assertFailsWith<UnsupportedCommandResultException> { CommandResultCodec.encode("crawl.trigger", result) }
  }

  private fun fixture(db: DSLContext, source: SourceFetchPort): Fixture {
    val tx = JooqTransactionAdapter(db) { null }
    val user = User(UserId(UUID.randomUUID()), EmailAddress("crawl-admin@example.test"))
    val session = UserSessionId(UUID.randomUUID())
    val site = tx.execute {
      it.users.lockByEmail(user.email)
      it.users.save(user)
      it.credentials.insert(PasskeyCredential(user.id, PasskeyCredentialMaterial(CredentialId("crawl-passkey"),
        byteArrayOf(1), 0, emptySet(), false, false), NOW))
      it.users.save(User(user.id, user.email, UserStatus.ACTIVE, setOf(UserRole.USER, UserRole.ADMIN), setOf(CredentialId("crawl-passkey"))))
      it.userSessions.save(UserSession(session, user.id, NOW, NOW.plusSeconds(3600), NOW))
      assertIs<InsertCareerSiteResult.Inserted>(it.careerSites.insert(NewCareerSite(
        assertIs<SiteUrlResult.Valid>(SiteUrl.parse("https://crawl.example")).url, SourceProvider.FLEX, "Crawl"))).site
    }
    val command = CrawlSiteCommand(site.id, CrawlTrigger.MANUAL,
      Actor.User(user.id.value, setOf(UserRole.USER, UserRole.ADMIN), NOW, AuthenticationStrength.PASSKEY),
      CommandMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), session)
    return Fixture(service(db, source, ClockPort { NOW }, tx), command)
  }

  private fun service(db: DSLContext, source: SourceFetchPort, clock: ClockPort, tx: TransactionPort) =
    CrawlSite(JooqPostingRepository(db), JooqCrawlRunRepository(db), source,
      JooqCrawlLeasePort(db), JooqSuccessfulCrawlAdapter(db), clock, RetryPolicy(listOf(Duration.ofMinutes(5))),
      ClosePolicy(2), "test-worker", Duration.ofMinutes(5), tx, JooqSecurityEventLog(db))

  private fun assertCounts(db: DSLContext, n: Int) {
    for (table in listOf("crawl_runs", "command_requests", "audit_events")) assertEquals(n, db.fetchCount(DSL.table(table)), table)
  }
  private data class Fixture(val crawl: CrawlSite, val command: CrawlSiteCommand)
  companion object { val NOW: Instant = Instant.parse("2026-09-23T00:00:00Z") }
}
