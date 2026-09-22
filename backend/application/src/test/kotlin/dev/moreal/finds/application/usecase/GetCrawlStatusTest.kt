package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.model.CrawlStatus
import dev.moreal.finds.application.testing.FakeCrawlRunRepository
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.crawl.CrawlOutcome
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class GetCrawlStatusTest {
  @Test
  fun `returns latest statuses in repository order without sharing mutable list`() {
    val seeded = mutableListOf(
      CrawlStatus(
        careerSiteId = CareerSiteId(2),
        runId = CrawlRunId(20),
        outcome = CrawlOutcome.SUCCESS,
        finishedAt = NOW,
        failure = null,
      ),
      CrawlStatus(
        careerSiteId = CareerSiteId(1),
        runId = CrawlRunId(10),
        outcome = CrawlOutcome.FAILED,
        finishedAt = NOW.minusSeconds(60),
        failure = CrawlFailure(CrawlFailureCode.TIMEOUT, "site timeout"),
      ),
    )
    val runs = FakeCrawlRunRepository().apply { statuses = seeded }

    val result = GetCrawlStatus(runs).execute()
    seeded.clear()

    assertEquals(listOf(CareerSiteId(2), CareerSiteId(1)), result.map { it.careerSiteId })
    assertEquals(2, result.size)
  }

  private companion object {
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
  }
}
