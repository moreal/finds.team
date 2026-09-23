package dev.moreal.finds.application.testing

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import dev.moreal.mail.MailMessageId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Immutable identity values make shallow map snapshots safe; maps never escape the transaction. */
internal class FakeIdentityState(initialUsers: List<User> = emptyList()) {
  val users = initialUsers.associateByTo(linkedMapOf()) { it.id }
  val otps = linkedMapOf<Pair<EmailAddress, VerificationPurpose>, OtpAccountState>()
  val credentials = linkedMapOf<CredentialId, PasskeyCredential>()
  val sessions = linkedMapOf<RestrictedSessionId, RestrictedSession>()
  val recoveryCodes = linkedMapOf<UserId, RecoveryCodeHash>()
  val userSessions = linkedMapOf<UserSessionId, UserSession>()

  fun snapshot() = FakeIdentityState(users.values.toList()).also {
    it.otps.putAll(otps)
    it.credentials.putAll(credentials)
    it.sessions.putAll(sessions)
    it.recoveryCodes.putAll(recoveryCodes)
    it.userSessions.putAll(userSessions)
  }

  fun stores(checkActive: () -> Unit) = Stores(checkActive)

  inner class Stores(private val checkActive: () -> Unit) {
    private val lockedEmails = mutableSetOf<EmailAddress>()
    private fun requireLock(email: EmailAddress) {
      checkActive()
      check(email in lockedEmails) { "Identity mutation requires the account lock" }
    }
    private fun requireUserLock(id: UserId) = requireLock(checkNotNull(users[id]).email)

    val userRepository = object : UserRepository {
      override fun lockByEmail(email: EmailAddress): User? {
        checkActive()
        lockedEmails += email
        return users.values.singleOrNull { it.email == email }
      }
      override fun findById(id: UserId): User? { checkActive(); return users[id] }
      override fun save(user: User) {
        requireLock(user.email)
        check(users.values.none { it.email == user.email && it.id != user.id }) { "Duplicate email" }
        check(users[user.id]?.email?.let { it == user.email } != false) { "Email cannot be changed" }
        users[user.id] = user
      }
    }
    val otpRepository = object : OtpChallengeRepository {
      override fun find(email: EmailAddress, purpose: VerificationPurpose): OtpAccountState? {
        checkActive(); return otps[email to purpose]
      }
      override fun save(state: OtpAccountState) {
        requireLock(state.email)
        otps[state.email to state.purpose] = state
      }
    }
    val credentialRepository = object : PasskeyCredentialRepository {
      override fun findById(id: CredentialId): PasskeyCredential? { checkActive(); return credentials[id] }
      override fun insert(credential: PasskeyCredential): Boolean {
        requireUserLock(credential.userId)
        if (credential.material.id in credentials) return false
        credentials[credential.material.id] = credential
        return true
      }
      override fun findByUserId(userId: UserId): List<PasskeyCredential> {
        checkActive(); return credentials.values.filter { it.userId == userId }
      }
      override fun remove(id: CredentialId) {
        checkActive()
        credentials[id]?.let { requireUserLock(it.userId); credentials.remove(id) }
      }
      override fun rename(id: CredentialId, label: String) {
        checkActive()
        val credential = checkNotNull(credentials[id])
        requireUserLock(credential.userId)
        credentials[id] = credential.copy(label = label)
      }
    }
    val sessionRepository = object : RestrictedSessionRepository {
      override fun findById(id: RestrictedSessionId): RestrictedSession? { checkActive(); return sessions[id] }
      override fun save(session: RestrictedSession) {
        requireUserLock(session.userId)
        check(sessions[session.id]?.userId?.let { it == session.userId } != false) { "Session owner cannot change" }
        sessions[session.id] = session
      }
      override fun invalidateForUser(userId: UserId, scope: RestrictedSessionScope, now: Instant) {
        requireUserLock(userId)
        sessions.replaceAll { _, session ->
          if (session.userId == userId && session.scope == scope && session.invalidatedAt == null)
            session.copy(invalidatedAt = now) else session
        }
      }
    }
    val recoveryRepository = object : RecoveryCodeRepository {
      override fun findByUserId(userId: UserId): RecoveryCodeHash? { checkActive(); return recoveryCodes[userId] }
      override fun save(code: RecoveryCodeHash) { requireUserLock(code.userId); recoveryCodes[code.userId] = code }
    }
    val userSessionRepository = object : UserSessionRepository {
      override fun findById(id: UserSessionId): UserSession? { checkActive(); return userSessions[id] }
      override fun findByUserId(userId: UserId): List<UserSession> {
        checkActive(); return userSessions.values.filter { it.userId == userId }
      }
      override fun save(session: UserSession) {
        requireUserLock(session.userId)
        check(userSessions[session.id]?.userId?.let { it == session.userId } != false) { "Session owner cannot change" }
        userSessions[session.id] = session
      }
      override fun revoke(id: UserSessionId, now: Instant) {
        checkActive()
        userSessions[id]?.let { session ->
          requireUserLock(session.userId)
          if (session.revokedAt == null) userSessions[id] = session.copy(revokedAt = now)
        }
      }
      override fun revokeForUser(userId: UserId, now: Instant, except: UserSessionId?) {
        requireUserLock(userId)
        userSessions.replaceAll { id, session ->
          if (session.userId == userId && id != except && session.revokedAt == null) session.copy(revokedAt = now) else session
        }
      }
    }
  }
}

/** Predictable secrets are deliberately confined to test fixtures. */
class DeterministicIdentityRandom(var nextNumber: Int = 42) : SecureRandomPort {
  private var nextId = 100L
  override fun bytes(size: Int): ByteArray = ByteArray(size)
  override fun nextInt(bound: Int): Int = nextNumber.also { require(it in 0 until bound) }
  override fun uuid(): UUID = UUID(0, nextId++)
}

/** Real HMAC/comparison with public test-only keys, including version selection and key separation. */
class TestIdentityHashes : KeyedIdentityHashPort {
  var activeVersion = 1
  val matchedVersions = mutableListOf<Int>()
  override fun hash(purpose: IdentityHashPurpose, binding: String, value: String): KeyedIdentityHash =
    digest(if (purpose == IdentityHashPurpose.COMMAND_SCOPE) 1 else activeVersion, purpose, binding, value)

  override fun matches(expected: KeyedIdentityHash, purpose: IdentityHashPurpose, binding: String, value: String): Boolean {
    matchedVersions += expected.pepperVersion
    return MessageDigest.isEqual(expected.bytes, digest(expected.pepperVersion, purpose, binding, value).bytes)
  }

  private fun digest(version: Int, purpose: IdentityHashPurpose, binding: String, value: String): KeyedIdentityHash {
    val key = "public-test-key-${purpose.name}-$version".toByteArray()
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    val bytes = ByteArrayOutputStream().use { buffer ->
      DataOutputStream(buffer).use { it.writeUTF(purpose.name); it.writeUTF(binding); it.writeUTF(value) }
      buffer.toByteArray()
    }
    return KeyedIdentityHash(version, mac.doFinal(bytes))
  }
}

/** Models semantic enqueue only; production notifier/outbox encryption has adapter integration tests. */
class FakeVerificationCodeNotifier(private val clock: ClockPort) : VerificationCodeNotifier {
  var failAfterEnqueue = false
  override fun deliver(transaction: TransactionContext, recipient: EmailAddress, purpose: VerificationPurpose,
    code: VerificationCode, expiresAt: Instant, idempotencyKey: DeliveryRequestId, correlationId: UUID): DeliveryRequestId {
    val payload = ByteArrayOutputStream().use { buffer ->
      DataOutputStream(buffer).use { it.writeUTF(recipient.value); it.writeUTF(code.value) }
      buffer.toByteArray()
    }
    try {
      transaction.outbox.enqueue(MailPayloadMetadata(MailMessageId(idempotencyKey.value), purpose.name, expiresAt, correlationId), payload, clock.now())
      check(!failAfterEnqueue) { "Simulated notification failure" }
    } finally { payload.fill(0) }
    return idempotencyKey
  }

  fun codeFrom(mail: EnqueuedMail): String = DataInputStream(ByteArrayInputStream(mail.plaintext)).use {
    it.readUTF()
    it.readUTF()
  }
}
