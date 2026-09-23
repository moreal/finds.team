package dev.moreal.finds_team.crawl

import dev.moreal.finds.application.usecase.ExpireAbandonedCrawlRuns
import dev.moreal.finds.application.usecase.PurgeExpiredCommandRequests
import org.springframework.scheduling.annotation.Scheduled

class ScheduledAuditMaintenance(private val requests: PurgeExpiredCommandRequests,
  private val runs: ExpireAbandonedCrawlRuns) {
  @Scheduled(initialDelayString = "\${finds.audit.maintenance-interval:1m}",
    fixedDelayString = "\${finds.audit.maintenance-interval:1m}")
  fun maintain() {
    requests.execute(100)
    runs.execute(100)
  }
}
