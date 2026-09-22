package dev.moreal.finds.application.testing

import dev.moreal.finds.application.model.CrawlChangeCounts
import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.model.CrawlStatus
import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.model.PageRequest
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.port.CareerSiteRepository
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.CrawlLeasePort
import dev.moreal.finds.application.port.CrawlRunRepository
import dev.moreal.finds.application.port.InsertCareerSiteResult
import dev.moreal.finds.application.port.PostingRepository
import dev.moreal.finds.application.port.ProviderDiscoveryResult
import dev.moreal.finds.application.port.SourceDiscoveryPort
import dev.moreal.finds.application.port.SourceFetchPort
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.application.port.SuccessfulCrawlPort
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.crawl.CrawlHistory
import dev.moreal.finds.domain.crawl.SyncPlan
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.search.Filter
import java.time.Duration
import java.time.Instant

class FakeCareerSiteRepository(
  initialSites: List<CareerSite> = emptyList(),
) : CareerSiteRepository {
  val sites: MutableList<CareerSite> = initialSites.toMutableList()
  var enabledQueries: Int = 0
  var nextInsertResult: InsertCareerSiteResult? = null
  private var nextId = (initialSites.maxOfOrNull { it.id.value } ?: 0) + 1

  override fun findById(id: CareerSiteId): CareerSite? = sites.find { it.id == id }

  override fun findByHost(host: SiteHost): CareerSite? =
    sites.find { it.canonicalBaseUrl.host == host }

  override fun insert(site: NewCareerSite): InsertCareerSiteResult {
    nextInsertResult?.let { result ->
      nextInsertResult = null
      return result
    }
    findByHost(site.canonicalBaseUrl.host)?.let { return InsertCareerSiteResult.Duplicate(it) }
    val inserted = CareerSite(
      id = CareerSiteId(nextId++),
      canonicalBaseUrl = site.canonicalBaseUrl,
      provider = site.provider,
      displayName = site.displayName,
      crawlSettings = site.crawlSettings,
    )
    sites += inserted
    return InsertCareerSiteResult.Inserted(inserted)
  }

  override fun findEnabled(): List<CareerSite> {
    enabledQueries += 1
    return sites.filter { it.crawlSettings.enabled }
  }
}

class FakeSourceDiscoveryPort(
  var result: ProviderDiscoveryResult = ProviderDiscoveryResult.Unsupported,
) : SourceDiscoveryPort {
  val requestedUrls = mutableListOf<SiteUrl>()

  override suspend fun detect(url: SiteUrl): ProviderDiscoveryResult {
    requestedUrls += url
    return result
  }
}

class FakePostingRepository : PostingRepository {
  val postingsBySite = mutableMapOf<CareerSiteId, List<JobPosting>>()
  val searchRequests = mutableListOf<Pair<Filter, PageRequest>>()
  var searchResult = SearchPage(emptyList(), next = null, totalCount = 0)

  override fun findByCareerSite(id: CareerSiteId): List<JobPosting> =
    postingsBySite[id].orEmpty().toList()

  override fun search(filter: Filter, page: PageRequest): SearchPage {
    searchRequests += filter to page
    return searchResult
  }
}

data class AppliedCrawl(
  val runId: CrawlRunId,
  val plan: SyncPlan,
  val fetched: Int,
  val finishedAt: Instant,
)

class FakeSuccessfulCrawlPort : SuccessfulCrawlPort {
  val applied = mutableListOf<AppliedCrawl>()
  var result = CrawlChangeCounts(0, 0, 0, 0, 0, 0, 0)
  var throwable: RuntimeException? = null

  override fun applyAndComplete(
    runId: CrawlRunId,
    plan: SyncPlan,
    fetched: Int,
    finishedAt: Instant,
  ): CrawlChangeCounts {
    throwable?.let { throw it }
    applied += AppliedCrawl(runId, plan, fetched, finishedAt)
    return result
  }
}

class FakeClock(var instant: Instant) : ClockPort {
  override fun now(): Instant = instant
}

class FakeSourceFetchPort(
  var result: SourceFetchResult,
) : SourceFetchPort {
  val fetchedSites = mutableListOf<CareerSite>()
  var throwable: RuntimeException? = null

  override suspend fun fetch(site: CareerSite): SourceFetchResult {
    throwable?.let { throw it }
    fetchedSites += site
    return result
  }
}

data class FailedRun(
  val runId: CrawlRunId,
  val failure: CrawlFailure,
  val finishedAt: Instant,
)

class FakeCrawlRunRepository : CrawlRunRepository {
  val histories = mutableMapOf<CareerSiteId, CrawlHistory?>()
  val startedSites = mutableListOf<Pair<CareerSiteId, Instant>>()
  val failedRuns = mutableListOf<FailedRun>()
  var statuses: List<CrawlStatus> = emptyList()
  var startThrowable: RuntimeException? = null
  var failThrowable: RuntimeException? = null
  private var nextId = 1L

  override fun latestHistory(siteId: CareerSiteId): CrawlHistory? = histories[siteId]

  override fun start(siteId: CareerSiteId, startedAt: Instant): CrawlRunId {
    startThrowable?.let { throw it }
    startedSites += siteId to startedAt
    return CrawlRunId(nextId++)
  }

  override fun fail(runId: CrawlRunId, failure: CrawlFailure, finishedAt: Instant) {
    failThrowable?.let { throw it }
    failedRuns += FailedRun(runId, failure, finishedAt)
  }

  override fun latestStatuses(): List<CrawlStatus> = statuses.toList()
}

data class LeaseAttempt(
  val siteId: CareerSiteId,
  val owner: String,
  val now: Instant,
  val ttl: Duration,
)

class FakeCrawlLeasePort : CrawlLeasePort {
  val attempts = mutableListOf<LeaseAttempt>()
  val releases = mutableListOf<Pair<CareerSiteId, String>>()
  var acquireResult = true

  override fun tryAcquire(
    siteId: CareerSiteId,
    owner: String,
    now: Instant,
    ttl: Duration,
  ): Boolean {
    attempts += LeaseAttempt(siteId, owner, now, ttl)
    return acquireResult
  }

  override fun release(siteId: CareerSiteId, owner: String) {
    releases += siteId to owner
  }
}
