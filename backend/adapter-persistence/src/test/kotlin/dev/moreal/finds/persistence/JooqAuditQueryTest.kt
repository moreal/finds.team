package dev.moreal.finds.persistence

import dev.moreal.finds.application.audit.*
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource

class JooqAuditQueryTest : PostgresIntegrationTest() {
  private val now = Instant.parse("2026-09-23T00:00:00Z")
  @Test fun `runtime role keyset pages equal times without duplicates despite newer inserts`() {
    val db = runtime()
    val tx = JooqTransactionAdapter(db) { null }
    fun insert(n: Int, time: Instant = now) = tx.execute { it.auditLog.append(event(n, time)) }
    (1..5).forEach { insert(it) }
    val query = JooqAuditQuery(db)
    val first = query.search(AuditSearch(limit = 2))
    assertEquals(listOf(id(5), id(4)), first.events.map { it.id })
    assertEquals(AuditCursor(now, id(4)), first.nextCursor)
    insert(6, now.plusSeconds(1))
    val second = query.search(AuditSearch(limit = 2, after = first.nextCursor))
    assertEquals(listOf(id(3), id(2)), second.events.map { it.id })
    val last = query.search(AuditSearch(limit = 2, after = second.nextCursor))
    assertEquals(listOf(id(1)), last.events.map { it.id })
    assertNull(last.nextCursor)
    assertEquals(emptyList(), query.search(AuditSearch(after = AuditCursor(now, id(1)))).events)
  }
  @Test fun `actor action target and half open time filters combine and return categorical details`() {
    val db = runtime()
    val actor = dev.moreal.finds.application.security.Actor.User(id(20), setOf(dev.moreal.finds.domain.identity.UserRole.ADMIN), now,
      dev.moreal.finds.application.security.AuthenticationStrength.PASSKEY)
    val action = AuditAction.ROLE_GRANTED
    val wanted = event(1).copy(actor = actor, action = action, targetType = "user", targetId = id(30).toString(),
      details = AuditDetails.from(action, mapOf("role" to "ADMIN")))
    JooqTransactionAdapter(db) { null }.execute { tx ->
      tx.auditLog.append(wanted)
      tx.auditLog.append(wanted.copy(id = id(2), occurredAt = now.minusSeconds(1)))
      tx.auditLog.append(wanted.copy(id = id(3), occurredAt = now.plusSeconds(1)))
      tx.auditLog.append(wanted.copy(id = id(4), actor = Actor.System))
      tx.auditLog.append(wanted.copy(id = id(5), targetId = id(31).toString()))
      tx.auditLog.append(wanted.copy(id = id(6), action = AuditAction.ROLE_REVOKED,
        details = AuditDetails.from(AuditAction.ROLE_REVOKED, mapOf("role" to "ADMIN"))))
      tx.auditLog.append(wanted.copy(id = id(7), targetType = "account"))
      tx.auditLog.append(wanted.copy(id = id(8), actor = dev.moreal.finds.application.security.Actor.User(id(21),
        setOf(dev.moreal.finds.domain.identity.UserRole.ADMIN), now, dev.moreal.finds.application.security.AuthenticationStrength.PASSKEY)))
    }
    val events = JooqAuditQuery(db).search(AuditSearch(actorUserId = actor.userId, actorKind = AuditActorKind.USER,
      action = action, targetType = "user", targetId = id(30).toString(), from = now, until = now.plusSeconds(1))).events
    assertEquals(listOf(id(1)), events.map { it.id })
    assertEquals(mapOf("role" to "ADMIN"), events.single().details.fields)
    assertEquals(actor.userId, events.single().actorUserId)
    assertEquals(AuditOutcome.SUCCEEDED, events.single().outcome)
    assertEquals(listOf(id(4)), JooqAuditQuery(db).search(AuditSearch(actorKind = AuditActorKind.SYSTEM)).events.map { it.id })
  }
  @Test fun `bounded runtime cleanup preserves audit retention and not yet expired requests`() {
    val db = runtime()
    JooqTransactionAdapter(db) { null }.execute { tx ->
      (1..5).forEach { n ->
        val created = if (n == 5) now.minusSeconds(86399) else now.minusSeconds(86400)
        val retention = if (n == 4) CommandRetention.AUDIT else CommandRetention.ORDINARY
        val request = CommandRequest(CommandRequestKey("SYSTEM:test", "enrollment.otp.request", id(n)), CommandRequestHash("a".repeat(64)), created, retention)
        tx.commandRequests.reserve(request)
        tx.commandRequests.complete(request.key, StoredCommandResult(1, "enrollment.otp.request", "ACCEPTED"))
      }
    }
    val maintenance = JooqCommandMaintenance(db)
    assertFailsWith<IllegalArgumentException> { maintenance.purgeExpired(now, 1001) }
    assertEquals(2, maintenance.purgeExpired(now, 2))
    assertEquals(3, db.fetchCount(DSL.table("command_requests")))
    assertEquals(1, maintenance.purgeExpired(now, 2))
    assertEquals(setOf(id(4), id(5)), db.fetch("SELECT idempotency_key FROM command_requests").map { it.get(0, UUID::class.java) }.toSet())
    assertEquals(0, maintenance.purgeExpired(now, 2))
    assertEquals(1, maintenance.purgeExpired(now.plusSeconds(1), 2))
    assertEquals(id(4), db.fetchValue("SELECT idempotency_key FROM command_requests"))
  }
  private fun event(n: Int, time: Instant = now) = AuditEvent(id(n), 1, time, Actor.System,
    AuditAction.MANUAL_CRAWL_TRIGGERED, "crawl_run", n.toString(), UUID.randomUUID(), UUID.randomUUID(), AuditOutcome.SUCCEEDED)
  private fun id(n: Int) = UUID(0, n.toLong())
  private fun runtime(): DSLContext {
    val source = resetPublicSchema() as PGSimpleDataSource
    DSL.using(source, SQLDialect.POSTGRES).execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'finds_app') THEN CREATE ROLE finds_app LOGIN PASSWORD 'task4-runtime'; END IF; END $$")
    Flyway.configure().dataSource(source).load().migrate()
    val runtime = PGSimpleDataSource().apply { setURL(source.getURL()); user = "finds_app"; password = "task4-runtime" }
    return DSL.using(runtime, SQLDialect.POSTGRES)
  }
}
