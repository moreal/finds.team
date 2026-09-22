package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.port.CareerSiteRepository
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.CrawlRunRepository
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.crawl.CrawlEligibility
import dev.moreal.finds.domain.crawl.RetryPolicy
import dev.moreal.finds.domain.crawl.decideCrawlEligibility

class CrawlAllDue(
  private val sites: CareerSiteRepository,
  private val runs: CrawlRunRepository,
  private val clock: ClockPort,
  private val retryPolicy: RetryPolicy,
) {
  fun execute(limit: Int): List<CareerSiteId> {
    require(limit in 1..1_000) { "Crawl selection limit must be between 1 and 1000" }
    val now = clock.now()
    return sites.findEnabled()
      .asSequence()
      .filter { site ->
        decideCrawlEligibility(
          site.crawlSettings,
          runs.latestHistory(site.id),
          retryPolicy,
          now,
        ) is CrawlEligibility.Due
      }
      .map(CareerSite::id)
      .take(limit)
      .toList()
  }
}
