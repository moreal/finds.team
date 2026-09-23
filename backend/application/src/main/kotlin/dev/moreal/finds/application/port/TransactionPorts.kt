package dev.moreal.finds.application.port

/**
 * Executes one short atomic unit of work. All context stores share the same transaction; return
 * publishes all writes, any thrown failure rolls them all back. Context and stores must not escape
 * the callback or be used by another thread. Nested transactions are not supported by this contract.
 *
 * Provider discovery, WebAuthn verification, DNS and mail delivery happen outside this boundary.
 * Implementations must not retry the callback implicitly (it may generate identifiers).
 */
interface TransactionPort {
  fun <T> execute(block: (TransactionContext) -> T): T
}

/**
 * Identity defaults fail closed until an adapter explicitly wires them. Existing non-identity
 * commands continue to work; bootstrap must not expose enrollment endpoints before that wiring.
 */
interface TransactionContext {
  val careerSites: CareerSiteRepository
  val commandRequests: CommandRequestStore
  val auditLog: AuditLog
  val outbox: MailOutboxEnqueue
  val users: UserRepository get() = error("Identity persistence is not configured")
  val otpChallenges: OtpChallengeRepository get() = error("Identity persistence is not configured")
  val credentials: PasskeyCredentialRepository get() = error("Identity persistence is not configured")
  val restrictedSessions: RestrictedSessionRepository get() = error("Identity persistence is not configured")
  val recoveryCodes: RecoveryCodeRepository get() = error("Identity persistence is not configured")
  val userSessions: UserSessionRepository get() = error("Identity persistence is not configured")
}
