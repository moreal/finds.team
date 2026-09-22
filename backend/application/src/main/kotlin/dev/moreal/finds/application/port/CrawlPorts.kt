package dev.moreal.finds.application.port

import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.model.CrawlStatus
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.crawl.CrawlHistory
import dev.moreal.finds.domain.crawl.Snapshot
import java.time.Duration
import java.time.Instant

fun interface ClockPort {
  fun now(): Instant
}

fun interface SourceFetchPort {
  suspend fun fetch(site: CareerSite): SourceFetchResult
}

sealed interface SourceFetchResult {
  data class Success(val snapshot: Snapshot) : SourceFetchResult

  data class Failure(val failure: CrawlFailure) : SourceFetchResult
}

interface CrawlRunRepository {
  fun latestHistory(siteId: CareerSiteId): CrawlHistory?

  fun start(siteId: CareerSiteId, startedAt: Instant): CrawlRunId

  fun fail(runId: CrawlRunId, failure: CrawlFailure, finishedAt: Instant)

  fun latestStatuses(): List<CrawlStatus>
}

interface CrawlLeasePort {
  fun tryAcquire(
    siteId: CareerSiteId,
    owner: String,
    now: Instant,
    ttl: Duration,
  ): Boolean

  fun release(siteId: CareerSiteId, owner: String)
}
