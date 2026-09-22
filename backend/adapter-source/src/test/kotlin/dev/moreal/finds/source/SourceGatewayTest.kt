package dev.moreal.finds.source

import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.source.protocol.SourceProtocolSettings
import dev.moreal.finds.source.provider.SourceAdapter
import java.time.Duration
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SourceGatewayTest {
  @Test
  fun `dispatches by persisted provider without redetection`() = runTest {
    val flex = FakeAdapter(SourceProvider.FLEX)
    val greeting = FakeAdapter(SourceProvider.GREETING)
    val gateway = SourceGateway(listOf(flex, greeting), SourceProtocolSettings())

    gateway.fetch(site(SourceProvider.GREETING))

    assertEquals(0, flex.sites.size)
    assertEquals(1, greeting.sites.size)
  }

  @Test
  fun `missing duplicate timeout and thrown failures are typed`() = runTest {
    val missing = SourceGateway(emptyList(), SourceProtocolSettings())
    assertEquals(
      CrawlFailureCode.SOURCE_FETCH_FAILED,
      assertIs<SourceFetchResult.Failure>(missing.fetch(site(SourceProvider.FLEX))).failure.code,
    )
    assertFailsWith<IllegalArgumentException> {
      SourceGateway(
        listOf(FakeAdapter(SourceProvider.FLEX), FakeAdapter(SourceProvider.FLEX)),
        SourceProtocolSettings(),
      )
    }

    val timeoutAdapter = FakeAdapter(SourceProvider.FLEX) { awaitCancellation() }
    val timeout = SourceGateway(
      listOf(timeoutAdapter),
      SourceProtocolSettings(siteTimeout = Duration.ofMillis(1)),
    )
    assertEquals(
      CrawlFailureCode.TIMEOUT,
      assertIs<SourceFetchResult.Failure>(timeout.fetch(site(SourceProvider.FLEX))).failure.code,
    )

    val throwing = SourceGateway(
      listOf(FakeAdapter(SourceProvider.FLEX) { error("secret\nboom") }),
      SourceProtocolSettings(),
    )
    assertEquals(
      "secret boom",
      assertIs<SourceFetchResult.Failure>(throwing.fetch(site(SourceProvider.FLEX))).failure.message,
    )
  }

  private class FakeAdapter(
    override val provider: SourceProvider,
    private val action: suspend () -> SourceFetchResult = {
      SourceFetchResult.Failure(
        dev.moreal.finds.application.model.CrawlFailure(
          CrawlFailureCode.SOURCE_FETCH_FAILED,
          "fake",
        ),
      )
    },
  ) : SourceAdapter {
    val sites = mutableListOf<CareerSite>()
    override suspend fun fetch(site: CareerSite): SourceFetchResult {
      sites += site
      return action()
    }
  }

  private fun site(provider: SourceProvider) = CareerSite(
    CareerSiteId(1),
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse("https://jobs.example")).url,
    provider,
    "Acme",
  )
}
