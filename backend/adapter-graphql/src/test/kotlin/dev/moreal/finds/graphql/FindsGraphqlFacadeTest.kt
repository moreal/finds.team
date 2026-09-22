package dev.moreal.finds.graphql

import dev.moreal.finds.application.model.CrawlChangeCounts
import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.usecase.CrawlSiteResult
import dev.moreal.finds.application.usecase.RegisterCareerSiteResult
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FindsGraphqlFacadeTest {
  @Test fun `registration variants map to stable payload codes`() = runTest {
    var result: RegisterCareerSiteResult = RegisterCareerSiteResult.Registered(site())
    val facade = facade(register = { result })
    assertEquals("1", facade.registerCareerSite(input()).site?.id)

    result = RegisterCareerSiteResult.AmbiguousProvider(setOf(SourceProvider.NINEHIRE, SourceProvider.FLEX))
    val ambiguous = facade.registerCareerSite(input())
    assertEquals(ApiErrorCode.AMBIGUOUS_PROVIDER, ambiguous.error?.code)
    assertEquals(listOf(SourceProvider.FLEX, SourceProvider.NINEHIRE), ambiguous.error?.providers)

    result = RegisterCareerSiteResult.InvalidUrl("HTTPS required")
    assertEquals(ApiErrorCode.INVALID_URL, facade.registerCareerSite(input()).error?.code)
  }

  @Test fun `manual crawl success failure and invalid id map without exceptions`() = runTest {
    var result: CrawlSiteResult = CrawlSiteResult.Succeeded(
      CrawlRunId(2), CrawlChangeCounts(1, 1, 0, 0, 0, 0, 0),
    )
    val facade = facade(crawl = { result })
    assertEquals(CrawlTriggerOutcome.SUCCEEDED, facade.triggerCrawl("1").outcome)

    result = CrawlSiteResult.Failed(
      CrawlRunId(3), CrawlFailure(CrawlFailureCode.ROBOTS_DENIED, "denied"),
    )
    val failed = facade.triggerCrawl("1")
    assertEquals(ApiErrorCode.CRAWL_FAILED, failed.error?.code)
    assertEquals("denied", failed.error?.message)

    assertEquals(ApiErrorCode.INVALID_INPUT, facade.triggerCrawl("bad").error?.code)
  }

  private fun facade(
    register: suspend () -> RegisterCareerSiteResult = { RegisterCareerSiteResult.UnsupportedProvider },
    crawl: suspend () -> CrawlSiteResult = { CrawlSiteResult.NotFound },
  ) = FindsGraphqlFacade(
    searchHandler = { _, _ -> SearchPage(emptyList(), null, 0) },
    registerHandler = { register() },
    crawlHandler = { crawl() },
    statusHandler = { emptyList() },
  )

  private fun input() = RegisterCareerSiteInput("https://jobs.example", "Acme")
  private fun site() = CareerSite(
    CareerSiteId(1),
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse("https://jobs.example")).url,
    SourceProvider.NINEHIRE,
    "Acme",
  )
}
