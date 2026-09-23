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
    return Fixture(CrawlSite(JooqPostingRepository(db), JooqCrawlRunRepository(db), source,
      JooqCrawlLeasePort(db), JooqSuccessfulCrawlAdapter(db), ClockPort { NOW }, RetryPolicy(listOf(Duration.ofMinutes(5))),
      ClosePolicy(2), "test-worker", Duration.ofMinutes(5), tx, JooqSecurityEventLog(db)), command)
  }

  private fun assertCounts(db: DSLContext, n: Int) {
    for (table in listOf("crawl_runs", "command_requests", "audit_events")) assertEquals(n, db.fetchCount(DSL.table(table)), table)
  }
  private data class Fixture(val crawl: CrawlSite, val command: CrawlSiteCommand)
  companion object { val NOW: Instant = Instant.parse("2026-09-23T00:00:00Z") }
}
