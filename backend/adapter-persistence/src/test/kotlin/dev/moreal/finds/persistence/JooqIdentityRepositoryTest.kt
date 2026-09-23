package dev.moreal.finds.persistence

import dev.moreal.finds.application.audit.*
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.domain.identity.*
import java.time.Instant
import java.util.UUID
import org.flywaydb.core.Flyway
import org.jooq.exception.DataAccessException
import kotlin.test.*

class JooqIdentityRepositoryTest : PostgresIntegrationTest() {
  private val now = Instant.parse("2026-09-23T00:00:00Z")
  private val email = EmailAddress("Person@example.test")
  private val id = UserId(UUID.randomUUID())

  @Test fun `V5 adds identity tables and expiry indexes without replacing V4 data`() {
    val source = resetPublicSchema()
    Flyway.configure().dataSource(source).target("4").load().migrate()
    val db = org.jooq.impl.DSL.using(source, org.jooq.SQLDialect.POSTGRES)
    db.execute("INSERT INTO career_sites (canonical_base_url, host, provider, display_name) VALUES ('https://kept.test', 'kept.test', 'FLEX', 'Kept')")
    assertEquals(3, Flyway.configure().dataSource(source).load().migrate().migrationsExecuted)
    assertEquals("Kept", db.fetchValue("SELECT display_name FROM career_sites"))
    for (table in listOf("users", "user_roles", "passkey_credentials", "otp_challenges", "recovery_codes", "user_sessions", "restricted_sessions", "webauthn_challenges")) {
      assertEquals(table, db.fetchValue("SELECT to_regclass(?)::text", table))
    }
    for (table in listOf("otp_challenges", "user_sessions", "restricted_sessions", "webauthn_challenges")) {
      assertTrue(db.fetch("SELECT indexdef FROM pg_indexes WHERE tablename = ?", table).any { "expires_at" in it.get(0, String::class.java) })
    }
  }

  @Test fun `normalized email roles credential material and immutable ownership round trip`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { null }
    tx.execute {
      assertNull(it.users.lockByEmail(email))
      it.users.save(User(id, email))
      assertTrue(it.credentials.insert(credential(id, "credential-a", now)))
      it.users.save(User(id, email, UserStatus.ACTIVE, setOf(UserRole.USER, UserRole.ADMIN), setOf(CredentialId("credential-a"))))
    }
    tx.execute {
      val user = assertNotNull(it.users.lockByEmail(EmailAddress("PERSON@EXAMPLE.TEST")))
      assertEquals(id, user.id)
      assertEquals(setOf(UserRole.USER, UserRole.ADMIN), user.roles)
      assertEquals(setOf(CredentialId("credential-a")), user.credentials)
      val stored = assertNotNull(it.credentials.findById(CredentialId("credential-a")))
      assertContentEquals(byteArrayOf(1, 2, 3), stored.material.publicKeyCose)
      assertEquals(7L, stored.material.signatureCount)
      assertEquals(setOf("internal", "usb"), stored.material.transports)
      assertTrue(stored.material.backupEligible && stored.material.backedUp)
      assertEquals(now, stored.lastUsedAt)
      assertFalse(it.credentials.insert(credential(id, "credential-a", now)))
    }
    assertFails { tx.execute { it.users.lockByEmail(email); it.users.save(User(UserId(UUID.randomUUID()), email)) } }
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM users"))
    assertEquals(16, db.fetchValue("SELECT octet_length(user_handle) FROM users"))
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM passkey_credentials"))
    assertFailsWith<DataAccessException> { db.execute("UPDATE users SET normalized_email = 'UPPER@example.test'") }
    assertFailsWith<DataAccessException> { db.execute("UPDATE passkey_credentials SET signature_count = -1") }
    assertFailsWith<DataAccessException> { db.execute("UPDATE passkey_credentials SET backup_eligible = false") }
    db.execute("DELETE FROM users")
    assertEquals(0L, db.fetchValue("SELECT count(*) FROM user_roles"))
    assertEquals(0L, db.fetchValue("SELECT count(*) FROM passkey_credentials"))
  }

  @Test fun `identity mutations require account lock and escaped stores fail closed`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { null }
    assertFailsWith<IllegalStateException> { tx.execute { it.users.save(User(id, email)) } }
    assertEquals(0L, db.fetchValue("SELECT count(*) FROM users"))
    val escaped = tx.execute { it.users }
    assertFailsWith<IllegalStateException> { escaped.lockByEmail(email) }
    assertFailsWith<IllegalStateException> {
      tx.execute {
        runCatching { it.users.save(User(id, email)) }
      }
    }
  }

  @Test fun `global credential collision has one winner across different account locks`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { null }
    val next = java.util.concurrent.atomic.AtomicInteger()
    val results = race(2) {
      val account = User(UserId(UUID.randomUUID()), EmailAddress("person${next.incrementAndGet()}@example.test"))
      tx.execute {
        it.users.lockByEmail(account.email)
        it.users.save(account)
        val inserted = it.credentials.insert(credential(account.id, "globally-unique", now))
        if (inserted) it.users.save(User(account.id, account.email, UserStatus.ACTIVE, credentials = setOf(CredentialId("globally-unique"))))
        inserted
      }
    }
    assertEquals(1, results.count { it })
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM passkey_credentials"))
    assertEquals(2L, db.fetchValue("SELECT count(*) FROM users"))
  }

  @Test fun `session revocation preserves immutable owner timestamps and cannot resurrect a tombstone`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { null }
    val normal = UserSession(UserSessionId(UUID.randomUUID()), id, now, now.plusSeconds(3600), now)
    val restricted = RestrictedSession(RestrictedSessionId(UUID.randomUUID()), id, RestrictedSessionScope.ENROLLMENT, now, now.plusSeconds(600))
    tx.execute {
      it.users.lockByEmail(email); it.users.save(User(id, email))
      it.userSessions.save(normal); it.restrictedSessions.save(restricted)
      it.userSessions.revokeForUser(id, now, except = normal.id)
      assertTrue(assertNotNull(it.userSessions.findById(normal.id)).isUsable(now))
      it.userSessions.revoke(normal.id, now)
      it.restrictedSessions.invalidateForUser(id, RestrictedSessionScope.ENROLLMENT, now)
    }
    assertFails { tx.execute { it.users.lockByEmail(email); it.userSessions.save(normal) } }
    assertFails { tx.execute { it.users.lockByEmail(email); it.restrictedSessions.save(restricted) } }
    tx.execute {
      assertEquals(now, it.userSessions.findById(normal.id)?.revokedAt)
      assertEquals(now, it.restrictedSessions.findById(restricted.id)?.invalidatedAt)
    }
  }

  @Test fun `bounded tombstone purge retains replayable rows and removes only expired oldest bindings`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { null }
    val expiry = now.plusSeconds(600)
    val sessions = tx.execute {
      it.users.lockByEmail(email); it.users.save(User(id, email))
      List(3) { offset ->
        RestrictedSession(RestrictedSessionId(UUID.randomUUID()), id, RestrictedSessionScope.RECOVERY,
          now, expiry.plusSeconds(offset.toLong())).also(it.restrictedSessions::save)
      }.also { _ -> it.restrictedSessions.invalidateForUser(id, RestrictedSessionScope.RECOVERY, now) }
    }
    assertEquals(0, tx.execute { it.restrictedSessions.purgeExpired(expiry.plusSeconds(86399), 10) })
    assertEquals(1, tx.execute { it.restrictedSessions.purgeExpired(expiry.plusSeconds(86402), 1) })
    tx.execute {
      assertNull(it.restrictedSessions.findById(sessions.first().id))
      assertNotNull(it.restrictedSessions.findById(sessions.last().id))
    }
    assertEquals(1, tx.execute { it.restrictedSessions.purgeExpired(expiry.plusSeconds(86401), 10) })
    assertEquals(1, tx.execute { it.restrictedSessions.purgeExpired(expiry.plusSeconds(86402), 10) })
    assertEquals(0L, db.fetchValue("SELECT count(*) FROM restricted_sessions"))
    assertFailsWith<IllegalArgumentException> { tx.execute { it.restrictedSessions.purgeExpired(now, 0) } }
  }

  @Test fun `passkey rename audit is admitted but secrets and unknown actions remain rejected`() {
    val (_, db) = migratedContext()
    JooqTransactionAdapter(db) { null }.execute {
      it.auditLog.append(AuditEvent(UUID.randomUUID(), 1, now, Actor.System, AuditAction.PASSKEY_RENAMED,
        "user", id.value.toString(), UUID.randomUUID(), UUID.randomUUID(), AuditOutcome.SUCCEEDED))
    }
    assertEquals("passkey.renamed", db.fetchValue("SELECT action FROM audit_events"))
    assertFailsWith<DataAccessException> {
      db.execute("INSERT INTO audit_events SELECT gen_random_uuid(), schema_version, occurred_at, actor_kind, actor_user_id, action, target_type, target_id, request_id, correlation_id, outcome, '{\"label\":\"secret\"}'::jsonb FROM audit_events")
    }
  }
}

internal fun credential(user: UserId, id: String, now: Instant) = PasskeyCredential(user,
  PasskeyCredentialMaterial(CredentialId(id), byteArrayOf(1, 2, 3), 7, setOf("internal", "usb"), true, true), now, "Laptop", now)
