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

/** Identity repositories will be added when the identity application ports exist. */
interface TransactionContext {
  val careerSites: CareerSiteRepository
  val commandRequests: CommandRequestStore
  val auditLog: AuditLog
  val outbox: MailOutboxEnqueue
}
