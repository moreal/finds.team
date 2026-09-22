package dev.moreal.finds.application.port

import dev.moreal.finds.application.model.CrawlChangeCounts
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.model.PageRequest
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.crawl.SyncPlan
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.search.Filter
import java.time.Instant

interface PostingRepository {
  fun findByCareerSite(id: CareerSiteId): List<JobPosting>

  fun search(filter: Filter, page: PageRequest): SearchPage
}

fun interface SuccessfulCrawlPort {
  fun applyAndComplete(
    runId: CrawlRunId,
    plan: SyncPlan,
    fetched: Int,
    finishedAt: Instant,
  ): CrawlChangeCounts
}
