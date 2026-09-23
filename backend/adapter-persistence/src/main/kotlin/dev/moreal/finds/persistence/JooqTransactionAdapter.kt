package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.MailPayloadCrypto
import dev.moreal.finds.application.port.MailOutboxEnqueue
import dev.moreal.finds.application.port.CareerSiteRepository
import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.port.TransactionContext
import dev.moreal.finds.application.port.TransactionPort
import dev.moreal.finds.application.port.CrawlRunRepository
import dev.moreal.finds.application.port.CrawlLeasePort
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.model.CrawlFailure
import java.time.Instant
import java.time.Duration
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
    override val passkeyRegistrationReceipts = dev.moreal.finds.application.port.PasskeyRegistrationReceiptPort { userId, credentialId ->
      scope.access { JooqPasskeyRegistrationReceipt(db).managementId(userId, credentialId) }
    }
    override val restrictedSessions = JooqRestrictedSessionRepository(db, identityAccess)
    override val recoveryCodes = JooqRecoveryCodeRepository(db, identityAccess)
    override val userSessions = JooqSessionRepository(db, identityAccess)
    override val webauthnChallenges = JooqWebAuthnChallengeStore(db, identityAccess)
    private val sites = JooqCareerSiteRepository(db)
    private val runs = JooqCrawlRunRepository(db)
    private val leases = JooqCrawlLeasePort(db)
    override val crawlRuns = object : CrawlRunRepository {
      override fun latestHistory(siteId: CareerSiteId) = scope.access { runs.latestHistory(siteId) }
      override fun start(siteId: CareerSiteId, startedAt: Instant) = scope.access { runs.start(siteId, startedAt) }
      override fun fail(runId: CrawlRunId, failure: CrawlFailure, finishedAt: Instant) = scope.access { runs.fail(runId, failure, finishedAt) }
      override fun latestStatuses() = scope.access { runs.latestStatuses() }
    }
    override val crawlLeases = object : CrawlLeasePort {
      override fun tryAcquire(siteId: CareerSiteId, owner: String, now: Instant, ttl: Duration) = scope.access { leases.tryAcquire(siteId, owner, now, ttl) }
      override fun release(siteId: CareerSiteId, owner: String) = scope.access { leases.release(siteId, owner) }
    }
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

  internal companion object {
    private val executing = ThreadLocal<Boolean>()
    fun requireOutsideTransaction() = check(executing.get() != true) { "Security events require a separate transaction" }
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
