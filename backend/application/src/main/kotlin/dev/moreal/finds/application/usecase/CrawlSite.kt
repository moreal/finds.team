package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.CrawlChangeCounts
import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.port.CareerSiteRepository
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.CrawlLeasePort
import dev.moreal.finds.application.port.CrawlRunRepository
import dev.moreal.finds.application.port.PostingRepository
import dev.moreal.finds.application.port.SourceFetchPort
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.application.port.SuccessfulCrawlPort
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
)

sealed interface CrawlSiteResult {
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
  private val sites: CareerSiteRepository,
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
) {
  init {
    require(leaseOwner.isNotBlank()) { "Lease owner must not be blank" }
    require(!leaseTtl.isZero && !leaseTtl.isNegative) { "Lease TTL must be positive" }
  }

  suspend fun execute(command: CrawlSiteCommand): CrawlSiteResult {
    val site = try {
      sites.findById(command.siteId)
    } catch (error: Exception) {
      return error.asInfrastructureFailure()
    } ?: return CrawlSiteResult.NotFound

    if (!site.crawlSettings.enabled) {
      return CrawlSiteResult.Disabled
    }
    if (command.trigger == CrawlTrigger.SCHEDULED) {
      val eligibility = try {
        decideCrawlEligibility(
          site.crawlSettings,
          runs.latestHistory(site.id),
          retryPolicy,
          clock.now(),
        )
      } catch (error: Exception) {
        return error.asInfrastructureFailure()
      }
      when (eligibility) {
        is CrawlEligibility.Due -> Unit
        is CrawlEligibility.NotDue -> return CrawlSiteResult.NotDue(eligibility.nextEligibleAt)
        is CrawlEligibility.Disabled -> return CrawlSiteResult.Disabled
      }
    }

    val acquiredAt = clock.now()
    val acquired = try {
      leases.tryAcquire(site.id, leaseOwner, acquiredAt, leaseTtl)
    } catch (error: Exception) {
      return error.asInfrastructureFailure()
    }
    if (!acquired) {
      return CrawlSiteResult.Busy
    }

    var runId: CrawlRunId? = null
    var infrastructureFailureCode = CrawlFailureCode.PERSISTENCE_FAILED
    try {
      val startedRunId = runs.start(site.id, acquiredAt)
      runId = startedRunId
      infrastructureFailureCode = CrawlFailureCode.SOURCE_FETCH_FAILED
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
      if (error is CancellationException) throw error
      val failure = CrawlFailure(infrastructureFailureCode, error.safeMessage())
      runId?.let { started ->
        runCatching { runs.fail(started, failure, clock.now()) }
      }
      return CrawlSiteResult.InfrastructureFailure(failure.message)
    } finally {
      runCatching { leases.release(site.id, leaseOwner) }
    }
  }

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
