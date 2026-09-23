package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.MailPayloadCrypto
import dev.moreal.finds.application.port.MailOutboxEnqueue
import dev.moreal.finds.application.port.CareerSiteRepository
import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.port.TransactionContext
import dev.moreal.finds.application.port.TransactionPort
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteHost
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** All stores are created from jOOQ's transaction configuration, never the root DSL. */
class JooqTransactionAdapter(
  private val context: DSLContext,
  private val crypto: () -> MailPayloadCrypto?,
) : TransactionPort {
  override fun <T> execute(block: (TransactionContext) -> T): T {
    check(executing.get() != true) { "Nested transactions are not supported" }
    executing.set(true)
    try {
      // Resolve already-loaded keys before starting the transaction. Missing mail configuration
      // permits non-mail commands but enqueue fails closed; provisioning belongs to bootstrap.
      val loadedCrypto = crypto()
      return context.transactionResult { configuration ->
        val scope = TransactionScope()
        // SQL diagnostic bind logging must not expose identity values, even at DEBUG level.
        val settings = (configuration.settings().clone() as org.jooq.conf.Settings).withExecuteLogging(false)
        val transaction = Context(DSL.using(configuration.derive(settings)), scope, loadedCrypto)
        try {
          val result = block(transaction)
          transaction.commandRequests.checkCompleted()
          scope.checkSuccessful()
          result
        } finally {
          scope.close()
        }
      }
    } finally {
      executing.remove()
    }
  }

  private class Context(db: DSLContext, scope: TransactionScope, crypto: MailPayloadCrypto?) : TransactionContext {
    private val identityAccess = IdentityAccess(db, scope)
    override val users = JooqIdentityRepository(db, identityAccess)
    override val otpChallenges = JooqOtpChallengeStore(db, identityAccess)
    override val credentials = JooqCredentialRepository(db, identityAccess)
    override val restrictedSessions = JooqRestrictedSessionRepository(db, identityAccess)
    override val recoveryCodes = JooqRecoveryCodeRepository(db, identityAccess)
    override val userSessions = JooqSessionRepository(db, identityAccess)
    override val webauthnChallenges = JooqWebAuthnChallengeStore(db, identityAccess)
    private val sites = JooqCareerSiteRepository(db)
    override val careerSites = object : CareerSiteRepository {
      override fun findById(id: CareerSiteId) = scope.access { sites.findById(id) }
      override fun findByHost(host: SiteHost) = scope.access { sites.findByHost(host) }
      override fun insert(site: NewCareerSite) = scope.access { sites.insert(site) }
      override fun findEnabled() = scope.access { sites.findEnabled() }
    }
    override val commandRequests = JooqCommandRequestStore(db, scope)
    override val auditLog = JooqAuditLog(db, scope)
    override val outbox = MailOutboxEnqueue { metadata, plaintext, now ->
      scope.access {
        JooqMailOutbox(db, checkNotNull(crypto) { "Mail payload encryption is not configured" })
          .enqueue(metadata, plaintext, now)
      }
    }
  }

  private companion object {
    val executing = ThreadLocal<Boolean>()
  }
}

/** Also poison a transaction if its caller catches a store failure and tries to return normally. */
internal class TransactionScope {
  private val owner = Thread.currentThread()
  private var active = true
  private var failed = false

  fun <T> access(block: () -> T): T {
    check(active && Thread.currentThread() === owner) { "Transaction context is inactive" }
    check(!failed) { "Transaction has failed" }
    return try { block() } catch (failure: Throwable) { failed = true; throw failure }
  }

  fun checkSuccessful() = check(!failed) { "Transaction has failed" }
  fun close() { active = false }
}
