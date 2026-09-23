package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.CrawlChangeCounts
import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.audit.*
import dev.moreal.finds.application.command.CanonicalCommandEncoder
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.domain.identity.UserId
import dev.moreal.finds.domain.identity.UserRole
import java.util.UUID
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.crawl.ClosePolicy
import dev.moreal.finds.domain.crawl.CrawlEligibility
import dev.moreal.finds.domain.crawl.ReconciliationResult
import dev.moreal.finds.domain.crawl.RetryPolicy
import dev.moreal.finds.domain.crawl.Snapshot
import dev.moreal.finds.domain.crawl.decideCrawlEligibility
import dev.moreal.finds.domain.crawl.reconcile
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException

enum class CrawlTrigger {
  SCHEDULED,
  MANUAL,
}

data class CrawlSiteCommand(
  val siteId: CareerSiteId,
  val trigger: CrawlTrigger,
  val actor: Actor,
  val metadata: CommandMetadata,
  val sessionId: UserSessionId? = null,
)

sealed interface CrawlSiteResult {
  data class Triggered(val runId: CrawlRunId) : CrawlSiteResult
  data object Forbidden : CrawlSiteResult
  data object InvalidIdempotencyKey : CrawlSiteResult
  data object IdempotencyConflict : CrawlSiteResult
  data object NotFound : CrawlSiteResult

  data class NotDue(val nextEligibleAt: Instant) : CrawlSiteResult

  data object Disabled : CrawlSiteResult

  data object Busy : CrawlSiteResult

  data class Failed(
    val runId: CrawlRunId,
    val failure: CrawlFailure,
  ) : CrawlSiteResult

  data class Succeeded(
    val runId: CrawlRunId,
    val counts: CrawlChangeCounts,
  ) : CrawlSiteResult

  data class InfrastructureFailure(val message: String) : CrawlSiteResult
}

class CrawlSite(
  private val postings: PostingRepository,
  private val runs: CrawlRunRepository,
  private val source: SourceFetchPort,
  private val leases: CrawlLeasePort,
  private val completion: SuccessfulCrawlPort,
  private val clock: ClockPort,
  private val retryPolicy: RetryPolicy,
  private val closePolicy: ClosePolicy,
  private val leaseOwner: String,
  private val leaseTtl: Duration,
  private val transactions: TransactionPort,
  private val securityEvents: SecurityEventPort,
) {
  init {
    require(leaseOwner.isNotBlank()) { "Lease owner must not be blank" }
    require(!leaseTtl.isZero && !leaseTtl.isNegative) { "Lease TTL must be positive" }
  }

  suspend fun execute(command: CrawlSiteCommand): CrawlSiteResult {
    val reservation = try {
      transactions.execute { tx -> reserve(tx, command) }
    } catch (rejected: TriggerRejected) {
      Reservation(rejected.result)
    } catch (error: Exception) {
      return error.asInfrastructureFailure()
    }
    if (reservation.result == CrawlSiteResult.Forbidden) {
      securityEvents.denied(SecurityEventAction.CRAWL_DENIED, clock.now(), command.metadata,
        (command.actor as? Actor.User)?.userId, command.siteId.value)
    }
    val site = reservation.site ?: return reservation.result
    val runId = (reservation.result as CrawlSiteResult.Triggered).runId
    // The transaction has committed the run, lease, audit and semantic result. Neither source I/O
    // nor reconciliation can retain its connection. A replay returns above without fetching.
    val completed = crawl(site, runId, checkNotNull(reservation.leaseToken))
    return if (command.trigger == CrawlTrigger.MANUAL) reservation.result else completed
  }

  private fun reserve(tx: TransactionContext, command: CrawlSiteCommand): Reservation {
    val actor = command.actor
    val scope = if (command.trigger == CrawlTrigger.SCHEDULED) {
      if (actor != Actor.System) return Reservation(CrawlSiteResult.Forbidden)
      "SYSTEM:crawl_scheduler"
    } else {
      if (actor !is Actor.User || UserRole.ADMIN !in actor.roles || !actor.hasRecentPasskeyAuthentication(clock.now()))
        return Reservation(CrawlSiteResult.Forbidden)
      val session = command.sessionId ?: return Reservation(CrawlSiteResult.Forbidden)
      val user = tx.lockUsers(setOf(UserId(actor.userId)))[UserId(actor.userId)]
      if (!tx.authorize(SessionPrincipal(actor, session), user, clock.now(), recent = true) || UserRole.ADMIN !in checkNotNull(user).roles)
        return Reservation(CrawlSiteResult.Forbidden)
      actor.userId.toString()
    }
    val key = CommandRequestKey(scope, OPERATION, command.metadata.idempotencyKey
      ?: return Reservation(CrawlSiteResult.InvalidIdempotencyKey))
    val now = clock.now()
    val hash = CanonicalCommandEncoder.hash(mapOf("siteId" to command.siteId.value, "trigger" to command.trigger.name))
    val retention = if (command.trigger == CrawlTrigger.MANUAL) CommandRetention.AUDIT else CommandRetention.ORDINARY
    when (val reserved = tx.commandRequests.reserve(CommandRequest(key, hash, now, retention))) {
      CommandReservation.Conflict -> return Reservation(CrawlSiteResult.IdempotencyConflict)
      is CommandReservation.Replay -> {
        reserved.result.requireSupported(OPERATION, 1)
        if (reserved.result.outcome != "TRIGGERED" || reserved.result.resourceIds.keys != setOf("crawl_run"))
          throw UnsupportedCommandResultException()
        val id = reserved.result.resourceIds["crawl_run"] as? CommandResourceId.Number ?: throw UnsupportedCommandResultException()
        return Reservation(CrawlSiteResult.Triggered(CrawlRunId(id.value)))
      }
      CommandReservation.Reserved -> Unit
    }
    val site = tx.careerSites.findById(command.siteId) ?: throw TriggerRejected(CrawlSiteResult.NotFound)
    if (!site.crawlSettings.enabled) throw TriggerRejected(CrawlSiteResult.Disabled)
    if (command.trigger == CrawlTrigger.SCHEDULED) {
      when (val eligibility = decideCrawlEligibility(site.crawlSettings, tx.crawlRuns.latestHistory(site.id), retryPolicy, now)) {
        is CrawlEligibility.Due -> Unit
        is CrawlEligibility.NotDue -> throw TriggerRejected(CrawlSiteResult.NotDue(eligibility.nextEligibleAt))
        is CrawlEligibility.Disabled -> throw TriggerRejected(CrawlSiteResult.Disabled)
      }
    }
    // A process can have an expired worker finishing while its next run already owns the site.
    // Give every reservation its own release token, even when both use the same dispatcher name.
    val leaseToken = "$leaseOwner:${UUID.randomUUID()}"
    if (!tx.crawlLeases.tryAcquire(site.id, leaseToken, now, leaseTtl)) throw TriggerRejected(CrawlSiteResult.Busy)
    val runId = tx.crawlRuns.start(site.id, now)
    if (command.trigger == CrawlTrigger.MANUAL) tx.auditLog.append(AuditEvent(UUID.randomUUID(), 1, now, actor,
      AuditAction.MANUAL_CRAWL_TRIGGERED, "crawl_run", runId.value.toString(), command.metadata.requestId,
      command.metadata.correlationId, AuditOutcome.SUCCEEDED))
    tx.commandRequests.complete(key, StoredCommandResult(1, OPERATION, "TRIGGERED",
      mapOf("crawl_run" to CommandResourceId.Number(runId.value))))
    return Reservation(CrawlSiteResult.Triggered(runId), site, leaseToken)
  }

  private suspend fun crawl(site: CareerSite, startedRunId: CrawlRunId, leaseToken: String): CrawlSiteResult {
    var infrastructureFailureCode = CrawlFailureCode.SOURCE_FETCH_FAILED
    try {
      val fetchResult = source.fetch(site)
      if (fetchResult is SourceFetchResult.Failure) {
        val finishedAt = clock.now()
        runs.fail(startedRunId, fetchResult.failure, finishedAt)
        return CrawlSiteResult.Failed(startedRunId, fetchResult.failure)
      }

      val snapshot = (fetchResult as SourceFetchResult.Success).snapshot
      snapshot.identityFailure(site)?.let { failure ->
        runs.fail(startedRunId, failure, clock.now())
        return CrawlSiteResult.Failed(startedRunId, failure)
      }

      infrastructureFailureCode = CrawlFailureCode.PERSISTENCE_FAILED
      val existing = postings.findByCareerSite(site.id)
      val reconciliation = reconcile(existing, snapshot, closePolicy, clock.now())
      if (reconciliation !is ReconciliationResult.Success) {
        val failure = reconciliation.toFailure()
        runs.fail(startedRunId, failure, clock.now())
        return CrawlSiteResult.Failed(startedRunId, failure)
      }

      val counts = completion.applyAndComplete(
        runId = startedRunId,
        plan = reconciliation.plan,
        fetched = snapshot.postings.size,
        finishedAt = clock.now(),
      )
      return CrawlSiteResult.Succeeded(startedRunId, counts)
    } catch (error: Exception) {
      if (error is CancellationException) {
        val failure = CrawlFailure(CrawlFailureCode.CANCELLED, error.safeMessage())
        runCatching { runs.fail(startedRunId, failure, clock.now()) }
        throw error
      }
      val failure = CrawlFailure(infrastructureFailureCode, error.safeMessage())
      runCatching { runs.fail(startedRunId, failure, clock.now()) }
      return CrawlSiteResult.InfrastructureFailure(failure.message)
    } finally {
      runCatching { leases.release(site.id, leaseToken) }
    }
  }

  private data class Reservation(val result: CrawlSiteResult, val site: CareerSite? = null, val leaseToken: String? = null)
  private class TriggerRejected(val result: CrawlSiteResult) : RuntimeException(null, null, false, false)
  private companion object { const val OPERATION = "crawl.trigger" }

  private fun Snapshot.identityFailure(site: CareerSite): CrawlFailure? = when {
    careerSiteId != site.id -> CrawlFailure(
      CrawlFailureCode.WRONG_SITE,
      "Snapshot belongs to career site ${careerSiteId.value}, expected ${site.id.value}",
    )
    siteHost != site.canonicalBaseUrl.host -> CrawlFailure(
      CrawlFailureCode.WRONG_POSTING_HOST,
      "Snapshot host ${siteHost.value} does not match ${site.canonicalBaseUrl.host.value}",
    )
    else -> null
  }

  private fun ReconciliationResult.toFailure(): CrawlFailure = when (this) {
    is ReconciliationResult.Success -> error("Successful reconciliation is not a failure")
    ReconciliationResult.SuspiciousEmptySnapshot -> CrawlFailure(
      CrawlFailureCode.SUSPICIOUS_SNAPSHOT,
      "Empty snapshot rejected while open postings exist",
    )
    is ReconciliationResult.DuplicateExternalKey -> CrawlFailure(
      CrawlFailureCode.DUPLICATE_EXTERNAL_KEY,
      "Snapshot contains duplicate external key $externalKey",
    )
    is ReconciliationResult.WrongCareerSite -> CrawlFailure(
      CrawlFailureCode.WRONG_SITE,
      "Posting belongs to career site ${actual.value}, expected ${expected.value}",
    )
    is ReconciliationResult.WrongPostingHost -> CrawlFailure(
      CrawlFailureCode.WRONG_POSTING_HOST,
      "Posting $externalKey uses host ${actual.value}, expected ${expected.value}",
    )
  }

  private fun Exception.asInfrastructureFailure(): CrawlSiteResult.InfrastructureFailure {
    if (this is CancellationException) throw this
    return CrawlSiteResult.InfrastructureFailure(safeMessage())
  }

  private fun Exception.safeMessage(): String =
    (message ?: this::class.simpleName ?: "Infrastructure failure")
      .replace(Regex("[\\r\\n]+"), " ")
      .trim()
      .take(1_000)
}
