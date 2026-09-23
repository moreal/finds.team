package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.testing.*
import dev.moreal.finds.application.usecase.*
import dev.moreal.finds.domain.identity.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class JooqRecoveryConcurrencyTest : PostgresIntegrationTest() {
  private val now = Instant.parse("2026-09-23T00:00:00Z")
  private val email = EmailAddress("recovery@example.test")
  private val id = UserId(UUID.randomUUID())
  private val hashes = TestIdentityHashes()

  @Test fun `concurrent recovery has one winner revokes every old credential and session and preserves secret free replay`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { null }
    val sessions = seed(tx)
    val command = command(sessions.first())
    val complete = CompletePasskeyRecovery(tx, ClockPort { now }, DeterministicIdentityRandom(), hashes)
    val results = race(2) { complete.execute(command) }
    val winner = results.filterIsInstance<CompletePasskeyRecoveryResult.Completed>().single()
    assertEquals(1, results.count { it == CompletePasskeyRecoveryResult.AlreadyCompleted(id) })
    assertEquals(CompletePasskeyRecoveryResult.Rejected, complete.execute(command(sessions.last())))
    assertEquals(CompletePasskeyRecoveryResult.Rejected, complete.execute(command.copy(metadata = metadata())))
    assertEquals(CompletePasskeyRecoveryResult.IdempotencyConflict,
      complete.execute(command.copy(registration = VerifiedPasskeyRegistration(id, sessions.first().id, credential(id, "different", now).material))))
    tx.execute {
      assertEquals(setOf(CredentialId("new")), it.users.lockByEmail(email)?.credentials)
      assertEquals(listOf("new"), it.credentials.findByUserId(id).map { c -> c.material.id.value })
      assertTrue(it.userSessions.findByUserId(id).all { s -> s.revokedAt == now })
      sessions.forEach { s -> assertFalse(assertNotNull(it.restrictedSessions.findById(s.id)).isUsable(now)) }
      assertTrue(hashes.matches(assertNotNull(it.recoveryCodes.findByUserId(id)).hash, IdentityHashPurpose.RECOVERY_CODE, id.value.toString(), winner.recoveryCode.format()))
    }
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM audit_events"))
    assertEquals(2L, db.fetchValue("SELECT count(*) FROM user_sessions WHERE revoked_at IS NOT NULL"))
    for (table in listOf("recovery_codes", "command_requests", "audit_events"))
      assertFalse(db.fetch("SELECT row_to_json(t)::text FROM $table t").toString().contains(winner.recoveryCode.format()))
  }

  @Test fun `audit failure rolls back replacement old code consumption session revocation and command reservation`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { null }
    val session = seed(tx).first()
    db.execute("ALTER TABLE audit_events ADD CONSTRAINT reject_fixture CHECK (false)")
    assertFails { CompletePasskeyRecovery(tx, ClockPort { now }, DeterministicIdentityRandom(), hashes).execute(command(session)) }
    assertEquals(2L, db.fetchValue("SELECT count(*) FROM passkey_credentials"))
    assertEquals(0L, db.fetchValue("SELECT count(*) FROM user_sessions WHERE revoked_at IS NOT NULL"))
    assertEquals(0L, db.fetchValue("SELECT count(*) FROM restricted_sessions WHERE invalidated_at IS NOT NULL"))
    assertEquals(0L, db.fetchValue("SELECT count(*) FROM command_requests"))
    tx.execute { assertTrue(hashes.matches(assertNotNull(it.recoveryCodes.findByUserId(id)).hash, IdentityHashPurpose.RECOVERY_CODE, id.value.toString(), "old-code")) }
  }

  @Test fun `different recovery sessions racing distinct commands cannot each rotate credentials and code`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { null }
    val sessions = seed(tx)
    val commands = sessions.mapIndexed { index, session -> command(session, "replacement-$index") }
    val next = java.util.concurrent.atomic.AtomicInteger()
    val complete = CompletePasskeyRecovery(tx, ClockPort { now }, DeterministicIdentityRandom(), hashes)
    // Distinct sessions, idempotency keys AND replacement IDs: uniqueness cannot select a winner.
    val results = race(2) {
      val command = commands[next.getAndIncrement()]
      command to complete.execute(command)
    }
    val (winningCommand, winningResult) = results.single { it.second is CompletePasskeyRecoveryResult.Completed }
    val (losingCommand, _) = results.single { it.second == CompletePasskeyRecoveryResult.Rejected }
    val recoveryCode = (winningResult as CompletePasskeyRecoveryResult.Completed).recoveryCode
    tx.execute {
      val expected = setOf(winningCommand.registration.credential.id)
      assertEquals(expected, it.users.lockByEmail(email)?.credentials)
      assertEquals(expected, it.credentials.findByUserId(id).map { key -> key.material.id }.toSet())
      assertNull(it.credentials.findById(losingCommand.registration.credential.id))
      assertNull(it.credentials.findById(CredentialId("old-a")))
      assertNull(it.credentials.findById(CredentialId("old-b")))
      val oldSessions = it.userSessions.findByUserId(id)
      assertEquals(2, oldSessions.size)
      assertTrue(oldSessions.all { session -> session.revokedAt == now })
      sessions.forEach { session -> assertEquals(now, it.restrictedSessions.findById(session.id)?.invalidatedAt) }
      val code = assertNotNull(it.recoveryCodes.findByUserId(id))
      assertTrue(hashes.matches(code.hash, IdentityHashPurpose.RECOVERY_CODE, id.value.toString(), recoveryCode.format()))
      assertFalse(hashes.matches(code.hash, IdentityHashPurpose.RECOVERY_CODE, id.value.toString(), "old-code"))
    }
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM recovery_codes"))
    assertEquals(1L, db.fetchValue("SELECT revision FROM recovery_codes"))
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM audit_events"))
    assertEquals(winningCommand.metadata.requestId, db.fetchValue("SELECT request_id FROM audit_events"))
    assertEquals(winningCommand.metadata.correlationId, db.fetchValue("SELECT correlation_id FROM audit_events"))
    assertEquals(0L, db.fetchValue("SELECT count(*) FROM audit_events WHERE request_id = ?", losingCommand.metadata.requestId))
    assertEquals(0L, db.fetchValue("SELECT count(*) FROM mail_outbox"))
    assertEquals(2L, db.fetchValue("SELECT count(*) FROM command_requests"))
    assertEquals("COMPLETED", db.fetchValue("SELECT result->>'outcome' FROM command_requests WHERE idempotency_key = ?", winningCommand.metadata.idempotencyKey))
    assertEquals("REJECTED", db.fetchValue("SELECT result->>'outcome' FROM command_requests WHERE idempotency_key = ?", losingCommand.metadata.idempotencyKey))
  }

  private fun command(session: RestrictedSession, credentialId: String = "new") = CompletePasskeyRecoveryCommand(session.id,
    VerifiedPasskeyRegistration(id, session.id, credential(id, credentialId, now).material), metadata())

  private fun seed(tx: TransactionPort): List<RestrictedSession> = tx.execute {
    it.users.lockByEmail(email)
    it.users.save(User(id, email))
    listOf("old-a", "old-b").forEach { key -> assertTrue(it.credentials.insert(credential(id, key, now))) }
    it.users.save(User(id, email, UserStatus.ACTIVE, credentials = setOf(CredentialId("old-a"), CredentialId("old-b"))))
    it.recoveryCodes.save(RecoveryCodeHash(id, hashes.hash(IdentityHashPurpose.RECOVERY_CODE, id.value.toString(), "old-code"), now))
    repeat(2) { _ -> it.userSessions.save(UserSession(UserSessionId(UUID.randomUUID()), id, now, now.plusSeconds(3600), now)) }
    List(2) { _ -> RestrictedSession(RestrictedSessionId(UUID.randomUUID()), id, RestrictedSessionScope.RECOVERY, now, now.plusSeconds(600)).also(it.restrictedSessions::save) }
  }
}
