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
import kotlin.test.*

class EnrollmentTest {
  private val start = Instant.parse("2026-09-22T12:00:00Z")
  private val email = EmailAddress("Person@Example.com")
  private val existingId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

  @Test
  fun `request returns the same public result for new active and suspended accounts`() {
    val fresh = Fixture()
    val active = Fixture(listOf(existing(UserStatus.ACTIVE)))
    val suspended = Fixture(listOf(existing(UserStatus.SUSPENDED)))
    assertEquals(RequestEnrollmentOtpResult.Accepted, fresh.request())
    assertEquals(fresh.request(), active.request())
    assertEquals(fresh.request(), suspended.request())
    assertTrue(fresh.tx.users.isEmpty(), "Email ownership has not yet been verified")
    assertTrue(active.tx.otpStates.isEmpty())
    assertTrue(suspended.tx.otpStates.isEmpty())
    assertTrue(active.tx.outboxMessages.isEmpty())
    assertTrue(suspended.tx.outboxMessages.isEmpty())
  }

  @Test
  fun `request stores only versioned keyed hash and enqueues eight digits with ten minute expiry`() {
    val f = Fixture()
    val metadata = metadata()
    f.request(metadata = metadata)
    val state = f.tx.otpStates.single()
    val challenge = assertNotNull(state.challenge)
    assertEquals(0, state.consecutiveFailures)
    assertEquals(start.plusSeconds(600), challenge.expiresAt)
    assertNull(challenge.consumedAt)
    assertEquals("00000042", f.code())
    assertEquals(1, challenge.hash.pepperVersion)
    assertEquals(32, challenge.hash.bytes.size)
    assertFalse(challenge.hash.bytes.contentEquals(f.code().toByteArray()))
    assertTrue(f.hashes.matches(challenge.hash, IdentityHashPurpose.ENROLLMENT_OTP, email.normalized, f.code()))
    val mail = f.tx.outboxMessages.single()
    assertEquals("ENROLLMENT", mail.metadata.purpose)
    assertEquals(start.plusSeconds(600), mail.metadata.expiresAt)
    assertEquals(metadata.correlationId, mail.metadata.correlationId)
    assertEquals(challenge.deliveryId.value, mail.metadata.id.value)
    assertFalse(state.toString().contains(f.code()))
  }

  @Test
  fun `normalized request replay keeps the original challenge delivery and correlation without a second effect`() {
    val f = Fixture()
    val metadata = metadata()
    f.request(metadata = metadata)
    val before = f.tx.otpStates.single().challenge
    assertEquals(RequestEnrollmentOtpResult.Accepted,
      f.request(EmailAddress("person@example.COM"), metadata.copy(requestId = UUID.randomUUID(), correlationId = UUID.randomUUID())))
    assertSame(before, f.tx.otpStates.single().challenge)
    assertEquals(1, f.tx.outboxMessages.size)
    assertEquals(metadata.correlationId, f.tx.outboxMessages.single().metadata.correlationId)
    assertEquals(1, f.tx.completedRequests.size)
    val entry = f.tx.completedRequests.entries.single()
    assertFalse(entry.key.scope.contains("person"))
    assertFalse(entry.key.scope.contains("@"))
    assertEquals("ACCEPTED", entry.value.outcome)
    assertTrue(entry.value.resourceIds.isEmpty())
  }

  @Test
  fun `reissue invalidates the old code and preserves account failures through the fifth wrong entry`() {
    val f = Fixture()
    f.request()
    val oldCode = f.code()
    repeat(3) { assertEquals(VerifyEnrollmentOtpResult.Rejected, f.verify("99999999")) }
    f.random.nextNumber = 73
    f.request()
    assertEquals("00000073", f.code())
    assertEquals(3, f.tx.otpStates.single().consecutiveFailures)
    assertEquals(VerifyEnrollmentOtpResult.Rejected, f.verify(oldCode))
    assertEquals(VerifyEnrollmentOtpResult.Rejected, f.verify("99999999"))
    assertEquals(5, f.tx.otpStates.single().consecutiveFailures)
    assertEquals(VerifyEnrollmentOtpResult.Rejected, f.verify(f.code()))
    f.request()
    assertEquals(5, f.tx.otpStates.single().consecutiveFailures)
    assertEquals(VerifyEnrollmentOtpResult.Rejected, f.verify(f.code()))
    assertTrue(f.tx.restrictedSessions.isEmpty())
    assertTrue(f.tx.users.isEmpty())
  }

  @Test
  fun `four failures still permit the correct code and reset consecutive failures on verified ownership`() {
    val f = Fixture()
    f.request()
    repeat(4) { f.verify("99999999") }
    assertIs<VerifyEnrollmentOtpResult.Verified>(f.verify(f.code()))
    assertEquals(0, f.tx.otpStates.single().consecutiveFailures)
  }

  @Test
  fun `expiry boundary and unknown addresses reject without creating users or sessions`() {
    val f = Fixture()
    assertEquals(VerifyEnrollmentOtpResult.Rejected, f.verify("00000042"))
    f.request()
    f.now = start.plusSeconds(600)
    assertEquals(VerifyEnrollmentOtpResult.Rejected, f.verify(f.code()))
    assertTrue(f.tx.users.isEmpty())
    assertTrue(f.tx.restrictedSessions.isEmpty())
  }

  @Test
  fun `successful verification uses the stored pepper version and consumes once into a restricted session`() {
    val f = Fixture()
    f.request()
    f.hashes.activeVersion = 2
    val result = assertIs<VerifyEnrollmentOtpResult.Verified>(f.verify(f.code()))
    assertEquals(listOf(1), f.hashes.matchedVersions)
    assertEquals(RestrictedSessionScope.ENROLLMENT, result.session.scope)
    assertEquals(start.plusSeconds(600), result.session.expiresAt)
    assertEquals(UserStatus.PENDING_PASSKEY, f.tx.users.single().status)
    assertEquals(setOf(UserRole.USER), f.tx.users.single().roles)
    assertEquals(start, f.tx.otpStates.single().challenge?.consumedAt)
    assertEquals(VerifyEnrollmentOtpResult.Rejected, f.verify(f.code()))
    assertEquals(1, f.tx.restrictedSessions.size)
  }

  @Test
  fun `concurrent valid verification has one winner`() {
    val f = Fixture()
    f.request()
    val code = f.code()
    Executors.newFixedThreadPool(2).use { pool ->
      val results = pool.invokeAll(List(2) { Callable { f.verify(code) } }).map { it.get() }
      assertEquals(1, results.count { it is VerifyEnrollmentOtpResult.Verified })
      assertEquals(1, results.count { it == VerifyEnrollmentOtpResult.Rejected })
    }
    assertEquals(1, f.tx.users.size)
  }

  @Test
  fun `completion activates first credential audits invalidates session and returns separate pepper recovery code once`() {
    val f = Fixture()
    val session = f.enroll()
    val registration = registration(session)
    val metadata = metadata()
    val result = assertIs<CompletePasskeyEnrollmentResult.Completed>(f.complete(session, registration, metadata))
    val user = f.tx.users.single()
    assertEquals(UserStatus.ACTIVE, user.status)
    assertEquals(setOf(UserRole.USER), user.roles)
    assertEquals(setOf(CredentialId("credential-1")), user.credentials)
    assertEquals(user.id, result.userId)
    assertEquals("AAAAA-AAAAA-AAAAA-AAAAA-AAAAAA", result.recoveryCode.format())
    assertEquals(1, f.tx.credentials.size)
    assertContentEquals(byteArrayOf(1, 2, 3), f.tx.credentials.single().material.publicKeyCose)
    assertEquals(start, f.tx.restrictedSessions.single().invalidatedAt)
    val recovery = f.tx.recoveryCodes.single()
    assertEquals(user.id, recovery.userId)
    assertTrue(f.hashes.matches(recovery.hash, IdentityHashPurpose.RECOVERY_CODE, user.id.value.toString(), result.recoveryCode.format()))
    assertFalse(f.hashes.matches(recovery.hash, IdentityHashPurpose.ENROLLMENT_OTP, user.id.value.toString(), result.recoveryCode.format()))
    val audit = f.tx.auditEvents.single()
    assertEquals(AuditAction.PASSKEY_REGISTERED, audit.action)
    assertEquals(metadata.requestId, audit.requestId)
    assertEquals(metadata.correlationId, audit.correlationId)
    assertEquals(user.id.value.toString(), audit.targetId)
    val actor = assertIs<Actor.User>(audit.actor)
    assertEquals(user.id.value, actor.userId)
    assertEquals(AuthenticationStrength.PASSKEY, actor.authenticationStrength)
    assertTrue(audit.details.fields.isEmpty())
    assertEquals(CompletePasskeyEnrollmentResult.AlreadyCompleted(user.id), f.complete(session, registration, metadata))
    assertEquals(1, f.tx.credentials.size)
    assertEquals(1, f.tx.auditEvents.size)
    assertFalse(result.toString().contains(result.recoveryCode.format()))
    assertTrue(f.tx.completedRequests.values.all { it.resourceIds.keys.all { key -> key == "user" } })
  }

  @Test
  fun `bootstrap admin roles require verified normalized allowlisted email and are assigned only at completion`() {
    val f = Fixture(allowlist = setOf(EmailAddress("PERSON@example.com")))
    f.request()
    assertTrue(f.tx.users.isEmpty())
    val session = assertIs<VerifyEnrollmentOtpResult.Verified>(f.verify(f.code())).session
    assertEquals(setOf(UserRole.USER), f.tx.users.single().roles)
    assertIs<CompletePasskeyEnrollmentResult.Completed>(f.complete(session))
    assertEquals(setOf(UserRole.USER, UserRole.ADMIN), f.tx.users.single().roles)
    assertEquals(setOf(UserRole.USER), BootstrapInitialRolePolicy(setOf(email)).rolesForVerifiedEmail(EmailAddress("person+other@example.com")))
  }

  @Test
  fun `completion rejects mismatched proof scope expiry and already used restricted sessions`() {
    val f = Fixture()
    val session = f.enroll()
    assertEquals(CompletePasskeyEnrollmentResult.Rejected,
      f.complete(session, registration(session, userId = existingId)))
    assertEquals(CompletePasskeyEnrollmentResult.Rejected,
      f.complete(session, registration(session, sessionId = RestrictedSessionId(UUID.randomUUID()))))
    f.tx.execute { it.users.lockByEmail(email); it.restrictedSessions.save(session.copy(scope = RestrictedSessionScope.RECOVERY)) }
    assertEquals(CompletePasskeyEnrollmentResult.Rejected, f.complete(session))
    f.tx.execute { it.users.lockByEmail(email); it.restrictedSessions.save(session) }
    f.now = session.expiresAt
    assertEquals(CompletePasskeyEnrollmentResult.Rejected, f.complete(session))
    assertTrue(f.tx.credentials.isEmpty())
    assertTrue(f.tx.recoveryCodes.isEmpty())
    assertTrue(f.tx.auditEvents.isEmpty())
    f.now = start
    assertIs<CompletePasskeyEnrollmentResult.Completed>(f.complete(session))
    assertEquals(CompletePasskeyEnrollmentResult.Rejected, f.complete(session))
  }

  @Test
  fun `suspending pending account prevents completion`() {
    val f = Fixture()
    val session = f.enroll()
    f.tx.execute { it.users.lockByEmail(email); it.users.save(User(session.userId, email, UserStatus.SUSPENDED)) }
    assertEquals(CompletePasskeyEnrollmentResult.Rejected, f.complete(session))
    assertTrue(f.tx.credentials.isEmpty())
    assertTrue(f.tx.auditEvents.isEmpty())
  }

  @Test
  fun `credential collision and changed idempotent request are rejected without further effects`() {
    val f = Fixture()
    val session = f.enroll()
    val metadata = metadata()
    assertIs<CompletePasskeyEnrollmentResult.Completed>(f.complete(session, metadata = metadata))
    assertEquals(CompletePasskeyEnrollmentResult.IdempotencyConflict,
      f.complete(session, registration(session, credentialId = CredentialId("other-credential")), metadata))
    val otherEmail = EmailAddress("other@example.com")
    f.request(otherEmail)
    val other = assertIs<VerifyEnrollmentOtpResult.Verified>(f.verify(f.code(), otherEmail)).session
    assertEquals(CompletePasskeyEnrollmentResult.Rejected, f.complete(other))
    assertEquals(1, f.tx.credentials.size)
    assertEquals(1, f.tx.auditEvents.size)
    assertEquals(UserStatus.PENDING_PASSKEY, f.tx.users.single { it.id == other.userId }.status)
  }

  @Test
  fun `outbox failure rolls back request challenge and reservation so retry can issue`() {
    val f = Fixture()
    val metadata = metadata()
    f.notifier.failAfterEnqueue = true
    assertFailsWith<IllegalStateException> { f.request(metadata = metadata) }
    assertTrue(f.tx.otpStates.isEmpty())
    assertTrue(f.tx.outboxMessages.isEmpty())
    assertTrue(f.tx.completedRequests.isEmpty())
    f.notifier.failAfterEnqueue = false
    f.request(metadata = metadata)
    assertEquals(1, f.tx.otpStates.size)
    assertEquals(1, f.tx.outboxMessages.size)
  }

  @Test
  fun `audit failure rolls back credential user session recovery hash and completion reservation`() {
    val f = Fixture()
    val session = f.enroll()
    val metadata = metadata()
    val requestsBefore = f.tx.completedRequests.size
    f.tx.auditFailure = { error("audit unavailable") }
    assertFailsWith<IllegalStateException> { f.complete(session, metadata = metadata) }
    assertEquals(UserStatus.PENDING_PASSKEY, f.tx.users.single().status)
    assertNull(f.tx.restrictedSessions.single().invalidatedAt)
    assertTrue(f.tx.credentials.isEmpty())
    assertTrue(f.tx.recoveryCodes.isEmpty())
    assertTrue(f.tx.auditEvents.isEmpty())
    assertEquals(requestsBefore, f.tx.completedRequests.size)
    f.tx.auditFailure = null
    assertIs<CompletePasskeyEnrollmentResult.Completed>(f.complete(session, metadata = metadata))
  }

  @Test
  fun `concurrent completion exposes recovery plaintext to exactly one caller`() {
    val f = Fixture()
    val session = f.enroll()
    val metadata = metadata()
    Executors.newFixedThreadPool(2).use { pool ->
      val results = pool.invokeAll(List(2) { Callable { f.complete(session, metadata = metadata) } }).map { it.get() }
      assertEquals(1, results.count { it is CompletePasskeyEnrollmentResult.Completed })
      assertEquals(1, results.count { it is CompletePasskeyEnrollmentResult.AlreadyCompleted })
    }
    assertEquals(1, f.tx.credentials.size)
    assertEquals(1, f.tx.auditEvents.size)
  }

  @Test
  fun `identity stores cannot escape the transaction and byte arrays cannot mutate committed state`() {
    val f = Fixture()
    f.request()
    val original = f.tx.otpStates.single().challenge!!.hash.bytes
    f.tx.otpStates.single().challenge!!.hash.bytes.fill(0)
    assertContentEquals(original, f.tx.otpStates.single().challenge!!.hash.bytes)
    lateinit var escaped: UserRepository
    f.tx.execute { escaped = it.users }
    assertFailsWith<IllegalStateException> { escaped.findById(existingId) }
  }

  private fun existing(status: UserStatus) = User(existingId, email, status,
    credentials = setOf(CredentialId("already-registered")))

  private fun metadata() = CommandMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

  private fun registration(session: RestrictedSession, userId: UserId = session.userId,
    sessionId: RestrictedSessionId = session.id, credentialId: CredentialId = CredentialId("credential-1")) =
    VerifiedPasskeyRegistration(userId, sessionId,
      PasskeyCredentialMaterial(credentialId, byteArrayOf(1, 2, 3), 0, setOf("internal"), true, true))

  private inner class Fixture(initialUsers: List<User> = emptyList(), allowlist: Set<EmailAddress> = emptySet()) {
    var now = start
    val clock = ClockPort { now }
    val tx = FakeTransaction(initialUsers = initialUsers)
    val random = DeterministicIdentityRandom(nextNumber = 42)
    val hashes = TestIdentityHashes()
    val notifier = FakeVerificationCodeNotifier(clock)
    val requestUseCase = RequestEnrollmentOtp(tx, clock, random, hashes, notifier)
    val verifyUseCase = VerifyEnrollmentOtp(tx, clock, random, hashes)
    val completeUseCase = CompletePasskeyEnrollment(tx, clock, random, hashes, BootstrapInitialRolePolicy(allowlist))
    fun request(address: EmailAddress = email, metadata: CommandMetadata = metadata()) =
      requestUseCase.execute(RequestEnrollmentOtpCommand(address, metadata))
    fun code() = notifier.codeFrom(tx.outboxMessages.last())
    fun verify(code: String, address: EmailAddress = email) = verifyUseCase.execute(VerifyEnrollmentOtpCommand(address, VerificationCode(code)))
    fun enroll(): RestrictedSession {
      request()
      return assertIs<VerifyEnrollmentOtpResult.Verified>(verify(code())).session
    }
    fun complete(session: RestrictedSession, proof: VerifiedPasskeyRegistration = registration(session), metadata: CommandMetadata = metadata()) =
      completeUseCase.execute(CompletePasskeyEnrollmentCommand(session.id, proof, metadata))
  }
}
