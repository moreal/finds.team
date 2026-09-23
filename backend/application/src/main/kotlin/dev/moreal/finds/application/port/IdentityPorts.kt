package dev.moreal.finds.application.port

import dev.moreal.finds.domain.identity.*
import java.time.Instant
import java.util.Collections
import java.util.UUID

/**
 * All identity mutations for an account must hold lockByEmail until transaction end. The lock also
 * covers absent users (for example an advisory/account-key lock), so concurrent first enrollment,
 * OTP consumption/reissue and completion serialize. Email uniqueness uses EmailAddress.normalized.
 */
interface UserRepository {
  fun lockByEmail(email: EmailAddress): User?
  fun findById(id: UserId): User?
  fun save(user: User)
}

/** Immutable HMAC-SHA-256 digest, never plaintext. Bytes are defensively copied and diagnostics redact. */
class KeyedIdentityHash(val pepperVersion: Int, bytes: ByteArray) {
  private val digest = bytes.copyOf()
  val bytes: ByteArray get() = digest.copyOf()
  init { require(pepperVersion > 0 && bytes.size == 32) { "Invalid identity hash" } }
  override fun toString(): String = "KeyedIdentityHash(pepperVersion=$pepperVersion, <redacted>)"
}

enum class IdentityHashPurpose { ENROLLMENT_OTP, RECOVERY_OTP, RECOVERY_CODE, COMMAND_SCOPE }

/**
 * HMAC-SHA-256 over unambiguously framed purpose, binding and value. Use distinct loaded, versioned
 * peppers for OTPs, recovery codes and command scopes; never fetch keys over the network here.
 * [matches] selects the stored pepper version and compares fixed-length digests in constant time.
 * COMMAND_SCOPE keys must remain stable through the command retention window (including rotations).
 */
interface KeyedIdentityHashPort {
  fun hash(purpose: IdentityHashPurpose, binding: String, value: String): KeyedIdentityHash
  fun matches(expected: KeyedIdentityHash, purpose: IdentityHashPurpose, binding: String, value: String): Boolean
}

/** Cryptographically secure bytes, unbiased bounded integers, and unpredictable 128-bit identifiers. */
interface SecureRandomPort {
  fun bytes(size: Int): ByteArray
  fun nextInt(bound: Int): Int
  fun uuid(): UUID
}

data class OtpChallenge(
  val deliveryId: DeliveryRequestId,
  val hash: KeyedIdentityHash,
  val expiresAt: Instant,
  val consumedAt: Instant? = null,
)

/** Failure state survives challenge replacement/expiry. Only successful verification resets it. */
data class OtpAccountState(
  val email: EmailAddress,
  val purpose: VerificationPurpose,
  val consecutiveFailures: Int = 0,
  val challenge: OtpChallenge? = null,
) {
  init { require(consecutiveFailures >= 0) { "Invalid OTP failure count" } }
}

/** Read/update under the normalized-email account lock. Save replaces the only usable challenge. */
interface OtpChallengeRepository {
  fun find(email: EmailAddress, purpose: VerificationPurpose): OtpAccountState?
  fun save(state: OtpAccountState)
}

@JvmInline
value class RestrictedSessionId(val value: UUID) {
  override fun toString(): String = "RestrictedSessionId(<redacted>)"
}

enum class RestrictedSessionScope { ENROLLMENT, RECOVERY }

/** This is never an ordinary authenticated session or an application Actor. */
data class RestrictedSession(
  val id: RestrictedSessionId,
  val userId: UserId,
  val scope: RestrictedSessionScope,
  val createdAt: Instant,
  val expiresAt: Instant,
  val invalidatedAt: Instant? = null,
) {
  init { require(expiresAt > createdAt) { "Invalid restricted session lifetime" } }
  fun isUsable(now: Instant): Boolean = invalidatedAt == null && now >= createdAt && now < expiresAt
}

/** Writes and single consumption serialize under the owning user's account lock. */
interface RestrictedSessionRepository {
  fun findById(id: RestrictedSessionId): RestrictedSession?
  fun save(session: RestrictedSession)
  fun invalidateForUser(userId: UserId, scope: RestrictedSessionScope, now: Instant)
}

/** Public cryptographic data, owned by adapters; the application does not parse COSE or transports. */
class PasskeyCredentialMaterial(
  val id: CredentialId,
  publicKeyCose: ByteArray,
  val signatureCount: Long,
  transports: Set<String>,
  val backupEligible: Boolean,
  val backedUp: Boolean,
) {
  private val publicKey = publicKeyCose.copyOf()
  val publicKeyCose: ByteArray get() = publicKey.copyOf()
  val transports: Set<String> = Collections.unmodifiableSet(LinkedHashSet(transports))
  init {
    require(publicKeyCose.isNotEmpty() && signatureCount >= 0) { "Invalid credential material" }
    require(!backedUp || backupEligible) { "Invalid credential backup state" }
  }
  override fun toString(): String = "PasskeyCredentialMaterial(<redacted>)"
}

/**
 * Trusted outer-adapter result only: RP ID, origin, challenge, session/user binding and required user
 * verification have already passed cryptographic verification outside the transaction. Never bind
 * an HTTP request directly to this type. Construction itself is NOT cryptographic verification.
 */
class VerifiedPasskeyRegistration(
  val userId: UserId,
  val sessionId: RestrictedSessionId,
  val credential: PasskeyCredentialMaterial,
) {
  override fun toString(): String = "VerifiedPasskeyRegistration(<redacted>)"
}

data class PasskeyCredential(
  val userId: UserId,
  val material: PasskeyCredentialMaterial,
  val createdAt: Instant,
  val label: String = "Passkey",
  val lastUsedAt: Instant? = null,
)

interface PasskeyCredentialRepository {
  fun findById(id: CredentialId): PasskeyCredential?
  /** Global credential uniqueness is enforced by the database; false leaves existing data intact. */
  fun insert(credential: PasskeyCredential): Boolean
}

data class RecoveryCodeHash(val userId: UserId, val hash: KeyedIdentityHash, val createdAt: Instant)

interface RecoveryCodeRepository {
  fun findByUserId(userId: UserId): RecoveryCodeHash?
  fun save(code: RecoveryCodeHash)
}

fun interface InitialRolePolicyPort {
  /** Call only after email ownership was verified. USER is mandatory and ADMIN is allowlist-only. */
  fun rolesForVerifiedEmail(email: EmailAddress): Set<UserRole>
}

class BootstrapInitialRolePolicy(allowlist: Set<EmailAddress> = emptySet()) : InitialRolePolicyPort {
  private val normalizedAllowlist = allowlist.map { it.normalized }.toSet()
  override fun rolesForVerifiedEmail(email: EmailAddress): Set<UserRole> =
    if (email.normalized in normalizedAllowlist) setOf(UserRole.USER, UserRole.ADMIN) else setOf(UserRole.USER)
}
