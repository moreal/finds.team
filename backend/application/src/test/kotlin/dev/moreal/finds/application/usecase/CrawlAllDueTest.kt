package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.testing.FakeCareerSiteRepository
import dev.moreal.finds.application.testing.FakeClock
import dev.moreal.finds.application.testing.FakeCrawlRunRepository
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.CrawlSettings
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.crawl.CrawlHistory
import dev.moreal.finds.domain.crawl.CrawlOutcome
import dev.moreal.finds.domain.crawl.RetryPolicy
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CrawlAllDueTest {
  @Test
  fun `selects due sites in repository order and excludes disabled and not due`() {
    val fixture = fixture()

    assertEquals(
      listOf(CareerSiteId(1), CareerSiteId(2), CareerSiteId(3), CareerSiteId(6)),
      fixture.useCase.execute(limit = 100),
    )
    assertEquals(1, fixture.sites.enabledQueries)
  }

  @Test
  fun `limit applies after eligibility so due sites are not starved`() {
    val fixture = fixture()

    assertEquals(
      listOf(CareerSiteId(1), CareerSiteId(2)),
      fixture.useCase.execute(limit = 2),
    )
    assertEquals(1, fixture.sites.enabledQueries)
  }

  @Test
  fun `invalid limit fails before repositories are queried`() {
    val fixture = fixture()

    assertFailsWith<IllegalArgumentException> { fixture.useCase.execute(0) }
    assertFailsWith<IllegalArgumentException> { fixture.useCase.execute(1_001) }
    assertEquals(0, fixture.sites.enabledQueries)
  }

  private fun fixture(): Fixture {
    val sites = FakeCareerSiteRepository(
      listOf(
        site(1),
        site(2),
        site(3),
        site(4),
        site(5, enabled = false),
        site(6),
      ),
    )
    val runs = FakeCrawlRunRepository().apply {
      histories[CareerSiteId(2)] = CrawlHistory(
        CrawlOutcome.SUCCESS,
        NOW.minus(Duration.ofHours(7)),
        consecutiveFailures = 0,
      )
      histories[CareerSiteId(3)] = CrawlHistory(
        CrawlOutcome.FAILED,
        NOW.minus(Duration.ofHours(3)),
        consecutiveFailures = 3,
      )
      histories[CareerSiteId(4)] = CrawlHistory(
        CrawlOutcome.SUCCESS,
        NOW.minus(Duration.ofHours(1)),
        consecutiveFailures = 0,
      )
      histories[CareerSiteId(6)] = CrawlHistory(
        CrawlOutcome.FAILED,
        NOW.minus(Duration.ofMinutes(31)),
        consecutiveFailures = 2,
      )
    }
    return Fixture(
      sites,
      CrawlAllDue(sites, runs, FakeClock(NOW), RETRY_POLICY),
    )
  }

  private fun site(id: Long, enabled: Boolean = true): CareerSite = CareerSite(
    id = CareerSiteId(id),
    canonicalBaseUrl = assertIs<SiteUrlResult.Valid>(
      SiteUrl.parse("https://jobs$id.example"),
    ).url,
    provider = SourceProvider.GREETING,
    displayName = "Site $id",
    crawlSettings = CrawlSettings(enabled = enabled),
  )

  private data class Fixture(
    val sites: FakeCareerSiteRepository,
    val useCase: CrawlAllDue,
  )

  private companion object {
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
    val RETRY_POLICY = RetryPolicy(
      listOf(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)),
    )
  }
}
