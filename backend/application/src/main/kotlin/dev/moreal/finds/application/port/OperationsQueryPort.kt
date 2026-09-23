package dev.moreal.finds.application.port

import dev.moreal.finds.application.model.*
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.crawl.CrawlOutcome
import java.time.Instant
import java.util.UUID

/** No provider failure text or infrastructure diagnostics enters the query model. */
data class CrawlRunView(val id: CrawlRunId, val siteId: CareerSiteId, val startedAt: Instant,
  val finishedAt: Instant?, val outcome: CrawlOutcome?, val counts: CrawlChangeCounts)
data class CrawlSummary(val outcome: CrawlOutcome?, val finishedAt: Instant?)
data class CrawlHistoryKey(val siteId: CareerSiteId, val page: ConnectionRequest)
data class AuditPageKey(val filter: AuditSearch, val page: ConnectionRequest)

interface OperationsQueryPort {
  fun crawlSummaries(ids: List<CareerSiteId>): List<CrawlSummary?>
  fun crawlHistory(keys: List<CrawlHistoryKey>): List<Result<ConnectionPage<CrawlRunView>>>
  fun crawlStatuses(pages: List<ConnectionRequest>): List<Result<ConnectionPage<CrawlStatus>>>
  fun crawlRuns(ids: List<CrawlRunId>): List<CrawlRunView?>
  fun auditPages(keys: List<AuditPageKey>): List<Result<ConnectionPage<AuditRecord>>>
  fun auditEvents(ids: List<UUID>): List<AuditRecord?>
}
