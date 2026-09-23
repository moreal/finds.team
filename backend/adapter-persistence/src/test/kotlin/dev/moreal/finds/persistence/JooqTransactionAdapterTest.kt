package dev.moreal.finds.persistence

import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.audit.AuditDetails
import dev.moreal.finds.application.audit.AuditEvent
import dev.moreal.finds.application.audit.AuditOutcome
import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.mail.MailMessageId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.*

class JooqTransactionAdapterTest : PostgresIntegrationTest() {
  @Test
  fun `same key replays original semantic result and changed hash conflicts without writes`() {
    val (_, db) = migratedContext()
    val tx = adapter(db)
    val first = register(tx)
    assertEquals(Created(1), first)
    assertEquals(first, register(tx, REQUEST.copy(createdAt = NOW.plusSeconds(100000))))
    assertEquals(Conflict, register(tx, REQUEST.copy(requestHash = OTHER_HASH)))
    assertCounts(db, 1)
    assertEquals(true, db.fetchValue("""SELECT result = '{"version":1,"kind":"career_site.register",
      "outcome":"CREATED","resourceIds":{"career_site":{"type":"number","value":1}}}'::jsonb
      FROM command_requests"""))
    assertEquals(true, db.fetchValue("SELECT completed_at >= created_at AND expires_at = created_at + interval '24 hours' FROM command_requests"))
  }

  @Test
  fun `concurrent first use waits for winner and returns one result effect audit and mail`() = race()

  @Test
  fun `concurrent different hash waits then conflicts without a second effect`() = race(differentHash = true)

  @Test
  fun `concurrent reservation takes over when winner rolls back`() = race(rollbackWinner = true)

  @Test
  fun `audit check failure rolls back business completed result and encrypted outbox`() {
    val (_, db) = migratedContext()
    // Force a genuine database detail check failure without bypassing the application allowlist.
    db.execute("ALTER TABLE audit_events ADD CONSTRAINT task4_reject_provider CHECK (details->>'provider' <> 'FLEX')")
    val failure = assertFailsWith<DataAccessException> { register(adapter(db)) }
    assertEquals("23514", failure.sqlState())
    assertCounts(db, 0)
    db.execute("ALTER TABLE audit_events DROP CONSTRAINT task4_reject_provider")
    assertIs<Created>(register(adapter(db)))
    assertCounts(db, 1)
  }

  @Test
  fun `caught audit failure cannot report a successful transaction`() {
    val (_, db) = migratedContext()
    db.execute("ALTER TABLE audit_events ADD CONSTRAINT task4_reject_provider CHECK (details->>'provider' <> 'FLEX')")
    assertFailsWith<IllegalStateException> {
      adapter(db).execute { tx ->
        tx.commandRequests.reserve(REQUEST)
        val id = assertIs<InsertCareerSiteResult.Inserted>(tx.careerSites.insert(site())).site.id.value
        tx.outbox.enqueue(mail(), PAYLOAD, NOW)
        tx.commandRequests.complete(REQUEST.key, stored(id))
        assertFailsWith<DataAccessException> { tx.auditLog.append(event(id)) }
      }
    }
    assertCounts(db, 0)
  }

  @Test
  fun `all stores remain invisible outside the connection until commit and roll back together`() {
    val (_, db) = migratedContext()
    assertFailsWith<Rollback> {
      register(adapter(db), afterWrite = {
        assertCounts(db, 0)
        throw Rollback()
      })
    }
    assertCounts(db, 0)
    assertIs<Created>(register(adapter(db)))
    assertCounts(db, 1)
  }

  @Test
  fun `business constraint failure rolls back previously appended audit completed result and mail`() {
    val (_, db) = migratedContext()
    db.execute("ALTER TABLE career_sites ADD CONSTRAINT task4_reject_name CHECK (display_name <> 'Rejected')")
    assertFailsWith<DataAccessException> {
      adapter(db).execute { tx ->
        tx.commandRequests.reserve(REQUEST)
        tx.commandRequests.complete(REQUEST.key, stored(9))
        tx.auditLog.append(event(9))
        tx.outbox.enqueue(mail(), PAYLOAD, NOW)
        tx.careerSites.insert(site().copy(displayName = "Rejected"))
      }
    }
    assertCounts(db, 0)
  }

  @Test
  fun `pending and repeated reservations and absent or repeated completion cannot commit`() {
    val (_, db) = migratedContext()
    val tx = adapter(db)
    assertFailsWith<IllegalStateException> { tx.execute { it.commandRequests.reserve(REQUEST) } }
    assertEquals(0, count(db, "command_requests"))
    for (changed in listOf(false, true)) {
      assertFailsWith<IllegalStateException> {
        tx.execute {
          it.commandRequests.reserve(REQUEST)
          it.commandRequests.reserve(if (changed) REQUEST.copy(requestHash = OTHER_HASH) else REQUEST)
        }
      }
    }
    assertFailsWith<IllegalStateException> { tx.execute { it.commandRequests.complete(REQUEST.key, stored(1)) } }
    assertFailsWith<IllegalStateException> {
      tx.execute {
        it.commandRequests.reserve(REQUEST)
        it.commandRequests.complete(REQUEST.key, stored(1))
        it.commandRequests.complete(REQUEST.key, stored(1))
      }
    }
    assertCounts(db, 0)
  }

  @Test
  fun `context and captured stores reject use after return across threads and nested transactions`() {
    val (_, db) = migratedContext()
    val tx = adapter(db)
    lateinit var captured: TransactionContext
    tx.execute { context ->
      captured = context
      Executors.newSingleThreadExecutor().use { pool ->
        pool.submit { assertFailsWith<IllegalStateException> { context.careerSites.findEnabled() } }
          .get(5, TimeUnit.SECONDS)
      }
      assertFailsWith<IllegalStateException> { tx.execute { } }
    }
    assertFailsWith<IllegalStateException> { captured.careerSites.findEnabled() }
    assertFailsWith<IllegalStateException> { captured.commandRequests.reserve(REQUEST) }
    assertFailsWith<IllegalStateException> { captured.auditLog.append(event(1)) }
    assertFailsWith<IllegalStateException> { captured.outbox.enqueue(mail(), PAYLOAD, NOW) }
    assertCounts(db, 0)
  }

  @Test
  fun `unsupported stored versions kinds outcomes and resources fail closed on replay`() {
    val (_, db) = migratedContext()
    register(adapter(db))
    val invalid = listOf(
      """{"version":99,"kind":"career_site.register","outcome":"CREATED","resourceIds":{"career_site":{"type":"number","value":1}}}""",
      """{"version":1,"kind":"other.command","outcome":"CREATED","resourceIds":{"career_site":{"type":"number","value":1}}}""",
      """{"version":1,"kind":"career_site.register","outcome":"ARBITRARY","resourceIds":{"career_site":{"type":"number","value":1}}}""",
      """{"version":1,"kind":"career_site.register","outcome":"CREATED","resourceIds":{"opaque":{"type":"uuid","value":"00000000-0000-0000-0000-000000000001"}}}""",
      """{"version":1,"kind":"career_site.register","outcome":"CREATED","resourceIds":{}}""",
      """{"version":1,"kind":"career_site.register","outcome":"CREATED","resourceIds":{"career_site":{"type":"uuid","value":"00000000-0000-0000-0000-000000000001"}}}""",
    )
    invalid.forEach { json ->
      db.execute("UPDATE command_requests SET result = ?::jsonb", json)
      assertFailsWith<UnsupportedCommandResultException> { register(adapter(db)) }
      assertCounts(db, 1)
    }
  }

  @Test
  fun `command-specific result allowlist prevents secret and unrelated identifiers from being stored`() {
    val (_, db) = migratedContext()
    val invalid = listOf(
      StoredCommandResult(99, "career_site.register", "CREATED", mapOf("career_site" to CommandResourceId.Number(1))),
      StoredCommandResult(1, "other.command", "CREATED"),
      StoredCommandResult(1, "career_site.register", "UNKNOWN"),
      StoredCommandResult(1, "career_site.register", "CREATED", mapOf("session" to CommandResourceId.Uuid(UUID.randomUUID()))),
      StoredCommandResult(1, "career_site.register", "CREATED", mapOf("opaque" to CommandResourceId.Uuid(UUID.randomUUID()))),
      StoredCommandResult(1, "career_site.register", "CREATED"),
    )
    invalid.forEach { result ->
      assertFailsWith<UnsupportedCommandResultException> {
        adapter(db).execute { tx ->
          tx.commandRequests.reserve(REQUEST)
          tx.careerSites.insert(site())
          tx.commandRequests.complete(REQUEST.key, result)
        }
      }
      assertCounts(db, 0)
    }
  }

  @Test
  fun `duplicate host preserves a usable transaction and original duplicate result`() {
    val (_, db) = migratedContext()
    val id = assertIs<Created>(register(adapter(db))).id
    val other = REQUEST.copy(key = REQUEST.key.copy(idempotencyKey = UUID.randomUUID()), retention = CommandRetention.AUDIT)
    adapter(db).execute { tx ->
      tx.commandRequests.reserve(other)
      assertEquals(id, assertIs<InsertCareerSiteResult.Duplicate>(tx.careerSites.insert(site())).existing.id.value)
      tx.commandRequests.complete(other.key, stored(id, "ALREADY_REGISTERED"))
    }
    val replay = adapter(db).execute { assertIs<CommandReservation.Replay>(it.commandRequests.reserve(other)).result }
    assertEquals("ALREADY_REGISTERED", replay.outcome)
    assertEquals(mapOf("career_site" to CommandResourceId.Number(id)), replay.resourceIds)
    assertEquals(true, db.fetchValue("SELECT expires_at IS NULL FROM command_requests WHERE retention = 'AUDIT'"))
    assertEquals(1, count(db, "career_sites"))
    assertEquals(1, count(db, "audit_events"))
  }

  @Test
  fun `limited runtime role commits replays and cannot mutate audit`() {
    val source = resetPublicSchema() as PGSimpleDataSource
    source.connection.use { connection ->
      connection.createStatement().use {
        it.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'finds_app') THEN CREATE ROLE finds_app LOGIN PASSWORD 'task4-runtime'; END IF; END $$")
      }
    }
    Flyway.configure().dataSource(source).load().migrate()
    val runtime = PGSimpleDataSource().apply { setURL(source.getURL()); user = "finds_app"; password = "task4-runtime" }
    val db = DSL.using(runtime, SQLDialect.POSTGRES)
    val first = register(adapter(db))
    assertEquals(first, register(adapter(db)))
    assertCounts(db, 1)
    assertEquals("42501", assertFailsWith<DataAccessException> { db.execute("UPDATE audit_events SET details = '{}'::jsonb") }.sqlState())
  }

  private fun race(differentHash: Boolean = false, rollbackWinner: Boolean = false) {
    val (source, db) = migratedContext()
    val reserved = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      source.connection.use { firstConnection ->
        source.connection.use { secondConnection ->
          val firstDb = DSL.using(firstConnection, SQLDialect.POSTGRES)
          val secondDb = DSL.using(secondConnection, SQLDialect.POSTGRES)
          val pid = secondDb.fetchValue("SELECT pg_backend_pid()") as Int
          assertNotEquals(firstDb.fetchValue("SELECT pg_backend_pid()"), pid)
          val first = pool.submit<Created?> {
            try {
              assertIs<Created>(register(adapter(firstDb), afterWrite = {
                reserved.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                if (rollbackWinner) throw Rollback()
              }))
            } catch (failure: Rollback) { if (!rollbackWinner) throw failure; null }
          }
          assertTrue(reserved.await(10, TimeUnit.SECONDS))
          val second = pool.submit<Any> {
            register(adapter(secondDb), if (differentHash) REQUEST.copy(requestHash = OTHER_HASH) else REQUEST)
          }
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
          while (db.fetchValue("SELECT EXISTS (SELECT FROM pg_locks WHERE pid = ? AND NOT granted)", pid) != true && System.nanoTime() < deadline) {
            Thread.sleep(10)
          }
          assertEquals(true, db.fetchValue("SELECT EXISTS (SELECT FROM pg_locks WHERE pid = ? AND NOT granted)", pid), "Second connection must wait on the uncommitted key")
          release.countDown()
          val original = first.get(10, TimeUnit.SECONDS)
          val replay = second.get(10, TimeUnit.SECONDS)
          when {
            rollbackWinner -> { assertNull(original); assertIs<Created>(replay) }
            differentHash -> assertEquals(Conflict, replay)
            else -> assertEquals(original, replay)
          }
          assertCounts(db, 1)
        }
      }
    } finally {
      release.countDown()
      pool.shutdownNow()
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
    }
  }

  private fun register(tx: TransactionPort, request: CommandRequest = REQUEST, afterWrite: () -> Unit = {}): Any =
    tx.execute { context ->
      when (val reservation = context.commandRequests.reserve(request)) {
        CommandReservation.Conflict -> Conflict
        is CommandReservation.Replay -> Created(assertIs<CommandResourceId.Number>(reservation.result.resourceIds.getValue("career_site")).value)
        CommandReservation.Reserved -> {
          val id = assertIs<InsertCareerSiteResult.Inserted>(context.careerSites.insert(site())).site.id.value
          context.outbox.enqueue(mail(), PAYLOAD, NOW)
          context.commandRequests.complete(request.key, stored(id))
          context.auditLog.append(event(id))
          afterWrite()
          Created(id)
        }
      }
    }

  private fun adapter(db: DSLContext) = JooqTransactionAdapter(db) { AesGcmMailPayloadCrypto(1, mapOf(1 to ByteArray(32) { 7 })) }
  private fun site() = NewCareerSite(assertIs<SiteUrlResult.Valid>(SiteUrl.parse("https://task4.example")).url, SourceProvider.FLEX, "Task 4")
  private fun stored(id: Long, outcome: String = "CREATED") = StoredCommandResult(1, "career_site.register", outcome, mapOf("career_site" to CommandResourceId.Number(id)))
  private fun mail() = MailPayloadMetadata(MailMessageId(UUID.randomUUID()), "EMAIL_OTP", NOW.plusSeconds(300))
  private fun event(id: Long) = AuditEvent(UUID.randomUUID(), 1, NOW, Actor.System, AuditAction.CAREER_SITE_REGISTERED,
    "career_site", id.toString(), UUID.randomUUID(), UUID.randomUUID(), AuditOutcome.SUCCEEDED,
    AuditDetails.from(AuditAction.CAREER_SITE_REGISTERED, mapOf("provider" to "FLEX")))
  private fun count(db: DSLContext, table: String) = db.fetchCount(DSL.table(table))
  private fun assertCounts(db: DSLContext, expected: Int) {
    for (table in listOf("career_sites", "command_requests", "audit_events", "mail_outbox")) assertEquals(expected, count(db, table), table)
  }

  private data class Created(val id: Long)
  private data object Conflict
  private class Rollback : RuntimeException()
  private companion object {
    val NOW: Instant = Instant.parse("2026-09-23T00:00:00Z")
    val REQUEST = CommandRequest(CommandRequestKey("SYSTEM:task4", "career_site.register", UUID.randomUUID()), CommandRequestHash("a".repeat(64)), NOW)
    val OTHER_HASH = CommandRequestHash("b".repeat(64))
    val PAYLOAD = "private@example.test OTP=194725".encodeToByteArray()
  }
}
