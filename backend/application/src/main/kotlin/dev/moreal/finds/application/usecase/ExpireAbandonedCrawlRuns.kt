package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.CrawlMaintenancePort
import java.time.Duration

class ExpireAbandonedCrawlRuns(private val runs: CrawlMaintenancePort, private val clock: ClockPort,
  private val leaseDuration: Duration) {
  init { require(!leaseDuration.isZero && !leaseDuration.isNegative) }
  fun execute(limit: Int = 100): Int {
    require(limit in 1..1000)
    val now = clock.now()
    return runs.expireAbandoned(now, now.minus(leaseDuration), limit)
  }
}
