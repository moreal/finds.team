package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.application.testing.*
import dev.moreal.finds.domain.identity.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RecoveryTest {
  @Test fun `requests hide unknown and suspended accounts and enqueue recovery purpose atomically`() {
    val f = SecurityFixture()
    assertEquals(RequestRecoveryOtpResult.Accepted, f.requestRecovery())
    assertEquals(RequestRecoveryOtpResult.Accepted, f.requestRecovery(EmailAddress("unknown@example.com")))
    f.suspend()
    assertEquals(RequestRecoveryOtpResult.Accepted, f.requestRecovery())
    val mail = f.tx.outboxMessages.single()
    assertEquals("RECOVERY", mail.metadata.purpose)
    assertEquals(f.start.plusSeconds(600), mail.metadata.expiresAt)
    assertEquals("00000042", f.otp())
    assertTrue(f.hashes.matches(f.tx.otpStates.single().challenge!!.hash,
      IdentityHashPurpose.RECOVERY_OTP, f.email.normalized, f.otp()))
  }

  @Test fun `request replay and email cooldown avoid duplicate delivery and preserve failure counters`() {
    val f = SecurityFixture()
    val metadata = f.metadata()
    f.requestRecovery(metadata = metadata)
    f.verifyRecovery(otp = "99999999")
    f.requestRecovery(metadata = metadata)
    f.requestRecovery()
    assertEquals(1, f.tx.outboxMessages.size)
    f.now = f.start.plusSeconds(60)
    f.random.nextNumber = 73
    f.requestRecovery()
    assertEquals(2, f.tx.outboxMessages.size)
    assertEquals(1, f.tx.otpStates.single().consecutiveFailures)
    assertEquals(VerifyRecoveryProofsResult.Rejected, f.verifyRecovery(otp = "00000042"))
  }

  @Test fun `neither email OTP alone nor saved recovery code alone establishes a session`() {
    val f = SecurityFixture()
    f.requestRecovery()
    assertEquals(VerifyRecoveryProofsResult.Rejected, f.verifyRecovery(code = null))
    assertEquals(VerifyRecoveryProofsResult.Rejected, f.verifyRecovery(otp = null))
    assertEquals(VerifyRecoveryProofsResult.Rejected, f.verifyRecovery(code = "invalid"))
    assertTrue(f.tx.restrictedSessions.isEmpty())
    assertEquals(3, f.tx.otpStates.single().consecutiveFailures)
    assertEquals(UserStatus.ACTIVE, f.tx.users.single().status)
  }

  @Test fun `five failures temporarily lock both proofs and reissue cannot reset the counter`() {
    val f = SecurityFixture()
    f.requestRecovery()
    repeat(5) { f.verifyRecovery(code = "invalid") }
    assertEquals(VerifyRecoveryProofsResult.Rejected, f.verifyRecovery())
    f.now = f.start.plusSeconds(899)
    f.requestRecovery()
    assertEquals(1, f.tx.outboxMessages.size)
    assertEquals(5, f.tx.otpStates.single().consecutiveFailures)
    f.now = f.start.plusSeconds(900)
    f.requestRecovery()
    assertEquals(5, f.tx.otpStates.single().consecutiveFailures)
    assertIs<VerifyRecoveryProofsResult.Verified>(f.verifyRecovery())
    assertEquals(0, f.tx.otpStates.single().consecutiveFailures)
  }

  @Test fun `both proofs consume OTP once into expiring restricted recovery scope without changing active account`() {
    val f = SecurityFixture()
    f.requestRecovery()
    f.hashes.activeVersion = 2
    val session = assertIs<VerifyRecoveryProofsResult.Verified>(f.verifyRecovery()).session
    assertEquals(RestrictedSessionScope.RECOVERY, session.scope)
    assertEquals(f.start.plusSeconds(600), session.expiresAt)
    assertEquals(UserStatus.ACTIVE, f.tx.users.single().status)
    assertEquals(2, f.tx.credentials.size)
    assertTrue(f.tx.userSessions.all { it.revokedAt == null })
    assertEquals(VerifyRecoveryProofsResult.Rejected, f.verifyRecovery())
    assertEquals(f.start, f.tx.otpStates.single().challenge!!.consumedAt)
    assertEquals(listOf(1, 1), f.hashes.matchedVersions)
  }

  @Test fun `OTP expiry rejects and expired recovery session does not burn the saved code`() {
    val f = SecurityFixture()
    f.requestRecovery()
    f.now = f.start.plusSeconds(600)
    assertEquals(VerifyRecoveryProofsResult.Rejected, f.verifyRecovery())
    f.requestRecovery()
    val expired = assertIs<VerifyRecoveryProofsResult.Verified>(f.verifyRecovery()).session
    f.now = expired.expiresAt
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(expired))
    f.requestRecovery()
    assertIs<VerifyRecoveryProofsResult.Verified>(f.verifyRecovery())
  }

  @Test fun `completion replaces all old credentials sessions and code with one audited result`() {
    val f = SecurityFixture()
    val firstSession = f.beginRecovery()
    f.now = f.start.plusSeconds(60)
    val secondSession = f.beginRecovery()
    f.hashes.activeVersion = 2
    val metadata = f.metadata()
    val result = assertIs<CompletePasskeyRecoveryResult.Completed>(f.completeRecovery(firstSession, metadata = metadata))
    assertEquals(setOf(CredentialId("new-passkey")), f.tx.users.single().credentials)
    assertEquals(listOf(CredentialId("new-passkey")), f.tx.credentials.map { it.material.id })
    assertTrue(f.tx.userSessions.all { it.revokedAt == f.now })
    assertTrue(f.tx.restrictedSessions.all { it.invalidatedAt == f.now })
    assertNotEquals(f.oldRecovery.format(), result.recoveryCode.format())
    assertTrue(f.hashes.matches(f.tx.recoveryCodes.single().hash, IdentityHashPurpose.RECOVERY_CODE,
      f.userId.value.toString(), result.recoveryCode.format()))
    assertFalse(f.hashes.matches(f.tx.recoveryCodes.single().hash, IdentityHashPurpose.RECOVERY_CODE,
      f.userId.value.toString(), f.oldRecovery.format()))
    assertEquals(2, f.tx.recoveryCodes.single().hash.pepperVersion)
    val audit = f.tx.auditEvents.single()
    assertEquals(AuditAction.ACCOUNT_RECOVERY_COMPLETED, audit.action)
    assertEquals(f.userId.value.toString(), audit.targetId)
    assertEquals(metadata.correlationId, audit.correlationId)
    assertTrue(audit.details.fields.isEmpty())
    assertEquals(CompletePasskeyRecoveryResult.AlreadyCompleted(f.userId), f.completeRecovery(firstSession, metadata = metadata))
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(secondSession))
    assertEquals(1, f.tx.auditEvents.size)
    f.now = f.now.plusSeconds(60)
    f.requestRecovery()
    assertEquals(VerifyRecoveryProofsResult.Rejected, f.verifyRecovery())
    assertFalse(result.toString().contains(result.recoveryCode.format()))
  }

  @Test fun `completion requires new credential matching proof session user scope and active status`() {
    val f = SecurityFixture()
    val session = f.beginRecovery()
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(session, "old-passkey-1"))
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(session,
      proof = VerifiedPasskeyRegistration(UserId(UUID.randomUUID()), session.id, f.material("new-passkey"))))
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(session,
      proof = VerifiedPasskeyRegistration(f.userId, RestrictedSessionId(UUID.randomUUID()), f.material("new-passkey"))))
    f.tx.execute { it.users.lockByEmail(f.email); it.restrictedSessions.save(session.copy(scope = RestrictedSessionScope.ENROLLMENT)) }
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(session))
    f.tx.execute { it.users.lockByEmail(f.email); it.restrictedSessions.save(session) }
    f.suspend()
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(session))
    assertEquals(2, f.tx.credentials.size)
    assertTrue(f.tx.auditEvents.isEmpty())
  }

  @Test fun `concurrent completion has exactly one plaintext bearing winner`() {
    val f = SecurityFixture()
    val session = f.beginRecovery()
    val results = concurrent { f.completeRecovery(session) }
    assertEquals(1, results.count { it is CompletePasskeyRecoveryResult.Completed })
    assertEquals(1, results.count { it == CompletePasskeyRecoveryResult.Rejected })
    assertEquals(1, f.tx.auditEvents.size)
  }

  @Test fun `lost response recovery replay returns no secret after expiry and altered credential material conflicts`() {
    val f = SecurityFixture()
    val session = f.beginRecovery()
    val metadata = f.metadata()
    val material = f.material("new-passkey")
    val original = VerifiedPasskeyRegistration(f.userId, session.id, material)
    val completed = assertIs<CompletePasskeyRecoveryResult.Completed>(f.completeRecovery(session, metadata = metadata, proof = original))
    val recoveryHash = f.tx.recoveryCodes.single()
    f.now = session.expiresAt
    val replay = f.completeRecovery(session, metadata = metadata, proof = original)
    assertEquals(CompletePasskeyRecoveryResult.AlreadyCompleted(f.userId), replay)
    assertFalse(replay.toString().contains(completed.recoveryCode.format()))
    val changes = listOf(
      f.material("changed-id"),
      PasskeyCredentialMaterial(material.id, byteArrayOf(4), 0, setOf("internal"), true, true),
      PasskeyCredentialMaterial(material.id, byteArrayOf(1, 2, 3), 1, setOf("internal"), true, true),
      PasskeyCredentialMaterial(material.id, byteArrayOf(1, 2, 3), 0, setOf("usb"), true, true),
      PasskeyCredentialMaterial(material.id, byteArrayOf(1, 2, 3), 0, setOf("internal"), true, false),
      PasskeyCredentialMaterial(material.id, byteArrayOf(1, 2, 3), 0, setOf("internal"), false, false),
    )
    for (changed in changes) assertEquals(CompletePasskeyRecoveryResult.IdempotencyConflict,
      f.completeRecovery(session, metadata = metadata, proof = VerifiedPasskeyRegistration(f.userId, session.id, changed)))
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(session, metadata = f.metadata(), proof = original))
    assertEquals(1, f.tx.credentials.size)
    assertEquals(1, f.tx.auditEvents.size)
    assertSame(recoveryHash, f.tx.recoveryCodes.single())
    assertTrue(f.tx.completedRequests.values.all { it.resourceIds.isEmpty() })
  }

  @Test fun `recovery replay still requires original stored session user and recovery scope binding`() {
    val f = SecurityFixture()
    val session = f.beginRecovery()
    f.now = f.start.plusSeconds(60)
    val otherSession = f.beginRecovery()
    val metadata = f.metadata()
    assertIs<CompletePasskeyRecoveryResult.Completed>(f.completeRecovery(session, metadata = metadata))
    assertEquals(CompletePasskeyRecoveryResult.IdempotencyConflict, f.completeRecovery(otherSession, metadata = metadata))
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(session, metadata = metadata,
      proof = VerifiedPasskeyRegistration(f.otherId, session.id, f.material("new-passkey"))))
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(session, metadata = metadata,
      proof = VerifiedPasskeyRegistration(f.userId, RestrictedSessionId(UUID.randomUUID()), f.material("new-passkey"))))
    f.tx.execute { it.users.lockByEmail(f.email); it.restrictedSessions.save(session.copy(scope = RestrictedSessionScope.ENROLLMENT, invalidatedAt = f.now)) }
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(session, metadata = metadata))
    assertEquals(1, f.tx.auditEvents.size)
  }

  @Test fun `recovery replay expires twenty four hours after ceremony expiry`() {
    val f = SecurityFixture()
    val session = f.beginRecovery()
    val metadata = f.metadata()
    assertIs<CompletePasskeyRecoveryResult.Completed>(f.completeRecovery(session, metadata = metadata))
    f.now = session.expiresAt.plusSeconds(86400)
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(session, metadata = metadata))
  }

  @Test fun `concurrent identical recovery retries produce one plaintext result and one secret free replay`() {
    val f = SecurityFixture()
    val session = f.beginRecovery()
    val metadata = f.metadata()
    val results = concurrent { f.completeRecovery(session, metadata = metadata) }
    assertEquals(1, results.count { it is CompletePasskeyRecoveryResult.Completed })
    assertEquals(1, results.count { it == CompletePasskeyRecoveryResult.AlreadyCompleted(f.userId) })
    assertEquals(1, f.tx.auditEvents.size)
    assertEquals(1, f.tx.credentials.size)
  }

  @Test fun `concurrent recovery retries changing verified material conflict with the one winner`() {
    val f = SecurityFixture()
    val session = f.beginRecovery()
    val metadata = f.metadata()
    val counter = java.util.concurrent.atomic.AtomicInteger()
    val results = concurrent {
      val material = PasskeyCredentialMaterial(CredentialId("new-passkey"), byteArrayOf(counter.incrementAndGet().toByte()),
        0, setOf("internal"), true, true)
      f.completeRecovery(session, metadata = metadata, proof = VerifiedPasskeyRegistration(f.userId, session.id, material))
    }
    assertEquals(1, results.count { it is CompletePasskeyRecoveryResult.Completed })
    assertEquals(1, results.count { it == CompletePasskeyRecoveryResult.IdempotencyConflict })
    assertEquals(1, f.tx.auditEvents.size)
    assertEquals(1, f.tx.credentials.size)
  }

  @Test fun `audit and notification failures roll back every recovery write`() {
    val f = SecurityFixture()
    f.notifier.failAfterEnqueue = true
    assertFailsWith<IllegalStateException> { f.requestRecovery() }
    assertTrue(f.tx.otpStates.isEmpty())
    assertTrue(f.tx.outboxMessages.isEmpty())
    assertTrue(f.tx.completedRequests.isEmpty())
    f.notifier.failAfterEnqueue = false
    val session = f.beginRecovery()
    f.tx.auditFailure = { error("audit unavailable") }
    assertFailsWith<IllegalStateException> { f.completeRecovery(session) }
    assertEquals(2, f.tx.credentials.size)
    assertTrue(f.tx.userSessions.all { it.revokedAt == null })
    assertTrue(f.tx.restrictedSessions.all { it.invalidatedAt == null })
    assertEquals(1, f.tx.recoveryCodes.single().hash.pepperVersion)
    f.tx.auditFailure = null
    assertIs<CompletePasskeyRecoveryResult.Completed>(f.completeRecovery(session))
  }
}

internal class SecurityFixture {
  val start: Instant = Instant.parse("2026-09-22T12:00:00Z")
  var now: Instant = start
  val clock = ClockPort { now }
  val email = EmailAddress("person@example.com")
  val userId = UserId(UUID(0, 1))
  val otherId = UserId(UUID(0, 2))
  val currentSessionId = UserSessionId(UUID(0, 11))
  val otherSessionId = UserSessionId(UUID(0, 12))
  val random = DeterministicIdentityRandom()
  val hashes = TestIdentityHashes()
  val notifier = FakeVerificationCodeNotifier(clock)
  val oldRecovery = RecoveryCode.fromBytes(ByteArray(16) { 1 })
  val tx = FakeTransaction(initialUsers = listOf(User(userId, email, UserStatus.ACTIVE,
    credentials = setOf(CredentialId("old-passkey-1"), CredentialId("old-passkey-2")))))

  init {
    tx.execute {
      it.users.lockByEmail(email)
      listOf("old-passkey-1", "old-passkey-2").forEach { id -> it.credentials.insert(PasskeyCredential(userId, material(id), start)) }
      it.recoveryCodes.save(RecoveryCodeHash(userId, hashes.hash(IdentityHashPurpose.RECOVERY_CODE,
        userId.value.toString(), oldRecovery.format()), start))
      listOf(currentSessionId, otherSessionId).forEach { id ->
        it.userSessions.save(UserSession(id, userId, start, start.plusSeconds(3600), start))
      }
    }
  }

  fun metadata() = CommandMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
  fun material(id: String) = PasskeyCredentialMaterial(CredentialId(id), byteArrayOf(1, 2, 3), 0, setOf("internal"), true, true)
  fun principal(strength: AuthenticationStrength = AuthenticationStrength.PASSKEY, at: Instant = start) =
    SessionPrincipal(Actor.User(userId.value, setOf(UserRole.USER), at, strength), currentSessionId)
  fun requestRecovery(email: EmailAddress = this.email, metadata: CommandMetadata = metadata()) =
    RequestRecoveryOtp(tx, clock, random, hashes, notifier).execute(RequestRecoveryOtpCommand(email, metadata))
  fun otp() = notifier.codeFrom(tx.outboxMessages.last())
  fun verifyRecovery(otp: String? = otp(), code: String? = oldRecovery.format()) =
    VerifyRecoveryProofs(tx, clock, random, hashes).execute(VerifyRecoveryProofsCommand(email, otp?.let(::VerificationCode), code))
  fun beginRecovery(): RestrictedSession { requestRecovery(); return assertIs<VerifyRecoveryProofsResult.Verified>(verifyRecovery()).session }
  fun completeRecovery(session: RestrictedSession, id: String = "new-passkey", metadata: CommandMetadata = metadata(),
    proof: VerifiedPasskeyRegistration = VerifiedPasskeyRegistration(userId, session.id, material(id))) =
    CompletePasskeyRecovery(tx, clock, random, hashes).execute(CompletePasskeyRecoveryCommand(session.id, proof, metadata))
  fun suspend() = tx.execute {
    val user = it.users.lockByEmail(email)!!
    it.users.save(User(user.id, user.email, UserStatus.SUSPENDED, user.roles, user.credentials))
  }
  fun addAdminAndTarget() = tx.execute {
    val user = it.users.lockByEmail(email)!!
    it.users.save(User(user.id, user.email, user.status, setOf(UserRole.USER, UserRole.ADMIN), user.credentials))
    val targetEmail = EmailAddress("target@example.com")
    it.users.lockByEmail(targetEmail)
    it.users.save(User(otherId, targetEmail, UserStatus.ACTIVE, credentials = setOf(CredentialId("target-passkey"))))
  }
}

internal fun <T> concurrent(block: () -> T): List<T> {
  val pool = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable).apply { isDaemon = true } }
  return try { pool.invokeAll(List(2) { Callable { block() } }, 5, TimeUnit.SECONDS).map { it.get(1, TimeUnit.SECONDS) } }
  finally { pool.shutdownNow(); check(pool.awaitTermination(5, TimeUnit.SECONDS)) }
}
