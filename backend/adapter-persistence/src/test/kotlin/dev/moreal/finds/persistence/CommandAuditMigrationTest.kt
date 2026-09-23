package dev.moreal.finds.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandAuditMigrationTest : PostgresIntegrationTest() {
  @Test
  fun `command uniqueness includes scope operation and key and requires a canonical hash`() = migrated { db ->
    db.exec(command())
    assertState("23505") { db.exec(command()) }
    db.exec(command(scope = "SYSTEM:other"))
    db.exec(command(operation = "other.command"))
    assertState("23502") { db.exec(command(key = 2, hash = "NULL")) }
    assertState("23514") { db.exec(command(key = 2, hash = "'invalid'")) }
    assertState("23514") { db.exec(command(key = 2, scope = "raw@example.test")) }
  }

  @Test
  fun `ordinary expiry is exactly 24 hours and audit retention has no ordinary expiry`() = migrated { db ->
    db.exec(command())
    assertEquals("86400.000000", db.value("SELECT extract(epoch FROM expires_at - created_at)::text FROM command_requests"))
    db.exec(command(key = 2, retention = "AUDIT", expiry = "NULL"))
    assertState("23514") { db.exec(command(key = 3, retention = "AUDIT")) }
    assertState("23514") { db.exec(command(key = 4, expiry = "NULL")) }
    assertState("23514") { db.exec(command(key = 5, expiry = "'2026-09-24T02:00:00Z'")) }
  }

  @Test
  fun `completed results admit only versioned categorical outcomes and typed resource ids`() = migrated { db ->
    db.exec(command())
    val valid = """{"version":1,"kind":"career_site.register","outcome":"CREATED","resourceIds":{"career_site":{"type":"number","value":7}}}"""
    db.exec("UPDATE command_requests SET result = '$valid'::jsonb, completed_at = '2026-09-23T02:00:00Z'")
    listOf(
      "[]", "{}", valid.replace("\"version\":1", "\"version\":0"),
      valid.dropLast(1) + ",\"token\":\"secret\"}",
      valid.replace("{\"type\":\"number\",\"value\":7}", "\"raw-response\""),
      valid.replace("\"career_site\":", "\"session_token\":"),
    ).forEach { invalid ->
      assertState("23514") { db.exec("UPDATE command_requests SET result = '$invalid'::jsonb") }
    }
    assertState("23514") { db.exec("UPDATE command_requests SET completed_at = NULL") }
  }

  @Test
  fun `audit details are action allowlisted categorical objects and events are immutable even to owner`() = migrated { db ->
    db.exec(audit())
    listOf("[]", "null", "{\"token\":\"secret\"}", "{\"provider\":\"raw@example.test\"}").forEach { details ->
      assertState("23514") { db.exec(audit(id = 2, details = details)) }
    }
    assertState("23514") { db.exec(audit(id = 2, actor = "USER", userId = "NULL")) }
    assertState("23514") { db.exec(audit(id = 2, outcome = "failed")) }
    assertState("55000") { db.exec("UPDATE audit_events SET details = '{}'::jsonb") }
    assertState("55000") { db.exec("DELETE FROM audit_events") }
    assertState("55000") { db.exec("TRUNCATE audit_events") }
    assertEquals("1", db.value("SELECT count(*)::text FROM audit_events"))
  }

  @Test
  fun `security events are separate redacted objects with indexed actor action target and time`() = migrated { db ->
    db.exec("""INSERT INTO security_events (event_id, occurred_at, action, request_id, correlation_id, details)
      VALUES (gen_random_uuid(), now(), 'authorization.denied', gen_random_uuid(), gen_random_uuid(), '{}')""")
    assertState("23514") {
      db.exec("""INSERT INTO security_events (event_id, occurred_at, action, request_id, correlation_id, details)
        VALUES (gen_random_uuid(), now(), 'authorization.denied', gen_random_uuid(), gen_random_uuid(), '[]')""")
    }
    for (table in listOf("audit_events", "security_events")) {
      val indexes = db.createStatement().use { statement ->
        statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE tablename = '$table'").use { rows ->
          buildList { while (rows.next()) add(rows.getString(1)) }
        }
      }
      for (columns in listOf("(occurred_at DESC, event_id DESC)", "(action, occurred_at DESC, event_id DESC)",
        "(actor_user_id, occurred_at DESC, event_id DESC)", "(target_type, target_id, occurred_at DESC, event_id DESC)")) {
        assertTrue(indexes.any { columns in it }, "Missing $table index $columns")
      }
    }
  }

  @Test
  fun `bootstrap preserves existing data migrates as owner and runtime cannot bypass audit protections`() {
    val script = Path.of("../../deploy/postgres/00-create-runtime-role.sh").toAbsolutePath().normalize()
    assertTrue(Files.isRegularFile(script), "Existing-volume role bootstrap must exist")
    PostgreSQLContainer("postgres:17-alpine")
      .withEnv("FINDS_MIGRATION_DB_PASSWORD", "test-migration-password")
      .withEnv("FINDS_DB_PASSWORD", "test-runtime-password")
      .use { postgres ->
        postgres.start()
        val admin = PGSimpleDataSource().apply {
          setURL(postgres.jdbcUrl); user = postgres.username; password = postgres.password
        }
        Flyway.configure().dataSource(admin).target("2").load().migrate()
        admin.connection.use { db ->
          db.exec("INSERT INTO career_sites (canonical_base_url, host, provider, display_name) VALUES ('https://fixture.test', 'fixture.test', 'FLEX', 'Keep me')")
        }
        postgres.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/bootstrap-roles.sh")
        repeat(2) {
          val result = postgres.execInContainer("sh", "/tmp/bootstrap-roles.sh")
          assertEquals(0, result.exitCode, result.stderr)
          assertFalse((result.stdout + result.stderr).contains("test-migration-password"))
          assertFalse((result.stdout + result.stderr).contains("test-runtime-password"))
        }
        val migrator = PGSimpleDataSource().apply {
          setURL(postgres.jdbcUrl); user = "finds_migrator"; password = "test-migration-password"
        }
        assertEquals(6, Flyway.configure().dataSource(migrator).load().migrate().migrationsExecuted)
        // Rerunning after V3 must not broaden the audit grants.
        assertEquals(0, postgres.execInContainer("sh", "/tmp/bootstrap-roles.sh").exitCode)
        val runtime = PGSimpleDataSource().apply {
          setURL(postgres.jdbcUrl); user = "finds_app"; password = "test-runtime-password"
        }
        runtime.connection.use { db ->
          assertEquals("Keep me", db.value("SELECT display_name FROM career_sites"))
          db.exec("UPDATE career_sites SET enabled = false")
          db.exec(audit())
          assertEquals("1", db.value("SELECT count(*)::text FROM audit_events"))
          listOf("UPDATE audit_events SET details = '{}'", "DELETE FROM audit_events", "TRUNCATE audit_events",
            "ALTER TABLE audit_events DISABLE TRIGGER ALL", "DROP TABLE audit_events",
            "CREATE TABLE public.bypass (id int)", "SET ROLE finds_migrator",
            "SET session_replication_role = replica", "UPDATE flyway_schema_history SET success = false",
            "CREATE OR REPLACE FUNCTION reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NULL; END'")
            .forEach { sql -> assertState("42501") { db.exec(sql) } }
          assertEquals("false", db.value("SELECT (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)::text FROM pg_roles WHERE rolname = current_user"))
          for (table in listOf("users", "user_roles", "passkey_credentials", "otp_challenges", "recovery_codes", "user_sessions", "restricted_sessions", "webauthn_challenges", "auth_rate_buckets")) {
            for (permission in listOf("SELECT", "INSERT", "UPDATE", "DELETE"))
              assertEquals("true", db.value("SELECT has_table_privilege(current_user, '$table', '$permission')::text"))
            assertEquals("false", db.value("SELECT has_table_privilege(current_user, '$table', 'TRUNCATE')::text"))
          }
        }
        val runtimeTx = JooqTransactionAdapter(org.jooq.impl.DSL.using(runtime, org.jooq.SQLDialect.POSTGRES)) { null }
        val identityEmail = dev.moreal.finds.domain.identity.EmailAddress("runtime@example.test")
        val identityId = dev.moreal.finds.domain.identity.UserId(java.util.UUID.randomUUID())
        runtimeTx.execute {
          it.users.lockByEmail(identityEmail)
          it.users.save(dev.moreal.finds.domain.identity.User(identityId, identityEmail))
          assertTrue(it.credentials.insert(credential(identityId, "runtime-key", java.time.Instant.now())))
          it.users.save(dev.moreal.finds.domain.identity.User(identityId, identityEmail,
            dev.moreal.finds.domain.identity.UserStatus.ACTIVE, credentials = setOf(dev.moreal.finds.domain.identity.CredentialId("runtime-key"))))
        }
        assertEquals(identityId, runtimeTx.execute { it.users.lockByEmail(identityEmail)?.id })
      }
  }

  private fun migrated(block: (Connection) -> Unit) {
    val source = resetPublicSchema()
    Flyway.configure().dataSource(source).load().migrate()
    source.connection.use(block)
  }

  private fun command(key: Int = 1, scope: String = "SYSTEM:job", operation: String = "career_site.register",
    hash: String = "'${"a".repeat(64)}'", retention: String = "ORDINARY", expiry: String = "'2026-09-24T01:00:00Z'") =
    """INSERT INTO command_requests (scope, operation, idempotency_key, request_hash, created_at, retention, expires_at)
      VALUES ('$scope', '$operation', '00000000-0000-0000-0000-${key.toString().padStart(12, '0')}', $hash,
      '2026-09-23T01:00:00Z', '$retention', $expiry)"""

  private fun audit(id: Int = 1, details: String = "{\"provider\":\"FLEX\"}", actor: String = "SYSTEM",
    userId: String = "NULL", outcome: String = "succeeded") =
    """INSERT INTO audit_events (event_id, schema_version, occurred_at, actor_kind, actor_user_id, action,
      target_type, target_id, request_id, correlation_id, outcome, details)
      VALUES ('00000000-0000-0000-0000-${id.toString().padStart(12, '0')}', 1, now(), '$actor', $userId,
      'career_site.registered', 'career_site', '1', gen_random_uuid(), gen_random_uuid(), '$outcome', '$details')"""

  private fun Connection.exec(sql: String) { createStatement().use { it.execute(sql) } }
  private fun Connection.value(sql: String): String? = createStatement().use { statement ->
    statement.executeQuery(sql).use { result -> check(result.next()); result.getString(1) }
  }
  private fun assertState(state: String, block: () -> Unit) {
    assertEquals(state, assertFailsWith<SQLException>(block = block).sqlState)
  }
}
