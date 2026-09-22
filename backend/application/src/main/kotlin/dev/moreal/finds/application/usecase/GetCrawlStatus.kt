package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.CrawlStatus
import dev.moreal.finds.application.port.CrawlRunRepository

class GetCrawlStatus(
  private val runs: CrawlRunRepository,
) {
  fun execute(): List<CrawlStatus> = runs.latestStatuses().toList()
}
