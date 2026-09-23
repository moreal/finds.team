package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class JooqIdentityChallengeTest : PostgresIntegrationTest() {
  private val now = Instant.parse("2026-09-23T00:00:00Z")
  private val email = EmailAddress("handle@example.test")
  private val id = UserId(UUID.randomUUID())

  @Test fun `user handle is stable opaque independent of account UUID and defensive`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { null }
    tx.execute { it.users.lockByEmail(email); it.users.save(User(id, email)) }
    val handle = tx.execute { assertNotNull(it.users.findUserHandle(id)) }
    assertEquals(16, handle.size)
    val userBytes = java.nio.ByteBuffer.allocate(16).putLong(id.value.mostSignificantBits).putLong(id.value.leastSignificantBits).array()
    assertFalse(handle.contentEquals(userBytes))
    tx.execute { it.users.lockByEmail(email); it.users.save(User(id, email)) }
    assertContentEquals(handle, tx.execute { it.users.findUserHandle(id) })
    handle.fill(0)
    assertFalse(handle.contentEquals(tx.execute { it.users.findUserHandle(id) }))
  }

  @Test fun `challenge consumes once only for matching purpose RP session user and unexpired binding`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { null }
    val session = RestrictedSession(RestrictedSessionId(UUID.randomUUID()), id, RestrictedSessionScope.ENROLLMENT, now, now.plusSeconds(600))
    val challenge = WebAuthnChallenge(UUID.randomUUID(), WebAuthnChallengePurpose.REGISTRATION, "finds.team",
      KeyedIdentityHash(1, ByteArray(32) { 1 }), KeyedIdentityHash(1, ByteArray(32) { 2 }), id, session.id, now, now.plusSeconds(300))
    tx.execute {
      it.users.lockByEmail(email); it.users.save(User(id, email)); it.restrictedSessions.save(session)
      it.webauthnChallenges.save(challenge)
    }
    for (changed in listOf(challenge.copy(rpId = "wrong.test"), challenge.copy(purpose = WebAuthnChallengePurpose.AUTHENTICATION),
      challenge.copy(userId = UserId(UUID.randomUUID())), challenge.copy(restrictedSessionId = RestrictedSessionId(UUID.randomUUID())),
      challenge.copy(hash = KeyedIdentityHash(2, ByteArray(32) { 1 })),
      challenge.copy(sessionBinding = KeyedIdentityHash(1, ByteArray(32) { 3 })))) {
      assertFalse(tx.execute { it.users.lockByEmail(email); it.webauthnChallenges.consume(changed, now) })
    }
    assertFalse(tx.execute { it.users.lockByEmail(email); it.webauthnChallenges.consume(challenge, challenge.expiresAt) })
    assertFalse(tx.execute { it.users.lockByEmail(email); it.webauthnChallenges.consume(challenge, now.minusSeconds(1)) })
    val results = race(2) { tx.execute { it.users.lockByEmail(email); it.webauthnChallenges.consume(challenge, now) } }
    assertEquals(1, results.count { it })
    assertEquals(now, tx.execute { it.webauthnChallenges.findById(challenge.id)?.consumedAt })
    assertFails { tx.execute { it.users.lockByEmail(email); it.webauthnChallenges.save(challenge) } }
    assertEquals(now, db.fetchOne("SELECT consumed_at FROM webauthn_challenges")!!.get(0, java.time.OffsetDateTime::class.java).toInstant())
  }

  @Test fun `challenge consume and credential usage roll back with caller transaction`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { null }
    val challenge = WebAuthnChallenge(UUID.randomUUID(), WebAuthnChallengePurpose.AUTHENTICATION, "finds.team",
      KeyedIdentityHash(1, ByteArray(32) { 1 }), KeyedIdentityHash(1, ByteArray(32) { 2 }), null, null, now, now.plusSeconds(300))
    tx.execute {
      it.users.lockByEmail(email); it.users.save(User(id, email))
      it.credentials.insert(credential(id, "counter", now))
      it.users.save(User(id, email, UserStatus.ACTIVE, credentials = setOf(CredentialId("counter"))))
      it.webauthnChallenges.save(challenge)
    }
    assertFailsWith<IllegalStateException> {
      tx.execute {
        it.users.lockByEmail(email)
        assertTrue(it.webauthnChallenges.consume(challenge, now))
        assertTrue(it.credentials.updateUsage(CredentialId("counter"), 7, 8, false, now.plusSeconds(1)))
        error("caller rollback")
      }
    }
    tx.execute {
      assertNull(it.webauthnChallenges.findById(challenge.id)?.consumedAt)
      assertEquals(7L, it.credentials.findById(CredentialId("counter"))?.material?.signatureCount)
      it.users.lockByEmail(email)
      assertFalse(it.credentials.updateUsage(CredentialId("counter"), 6, 8, false, now.plusSeconds(1)))
      assertFalse(it.credentials.updateUsage(CredentialId("counter"), 7, 7, false, now.plusSeconds(1)))
      assertTrue(it.credentials.updateUsage(CredentialId("counter"), 7, 8, false, now.plusSeconds(1)))
      val stored = assertNotNull(it.credentials.findById(CredentialId("counter")))
      assertEquals(8L, stored.material.signatureCount)
      assertFalse(stored.material.backedUp)
      assertEquals(now.plusSeconds(1), stored.lastUsedAt)
    }
  }
}
