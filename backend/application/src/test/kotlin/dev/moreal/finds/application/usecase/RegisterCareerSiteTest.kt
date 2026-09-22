package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.port.InsertCareerSiteResult
import dev.moreal.finds.application.port.ProviderDiscoveryResult
import dev.moreal.finds.application.testing.FakeCareerSiteRepository
import dev.moreal.finds.application.testing.FakeSourceDiscoveryPort
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.CrawlSettings
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RegisterCareerSiteTest {
  @Test
  fun `invalid URL and display name stop before discovery`() = runTest {
    val fixture = fixture()

    assertIs<RegisterCareerSiteResult.InvalidUrl>(
      fixture.useCase.execute(command("http://jobs.example")),
    )
    assertIs<RegisterCareerSiteResult.InvalidDisplayName>(
      fixture.useCase.execute(command("https://jobs.example", " ")),
    )
    assertIs<RegisterCareerSiteResult.InvalidDisplayName>(
      fixture.useCase.execute(command("https://jobs.example", " Acme ")),
    )
    assertTrue(fixture.discovery.requestedUrls.isEmpty())
    assertTrue(fixture.sites.sites.isEmpty())
  }

  @Test
  fun `unsupported ambiguous and failed discovery are typed results`() = runTest {
    val fixture = fixture()

    fixture.discovery.result = ProviderDiscoveryResult.Unsupported
    assertEquals(
      RegisterCareerSiteResult.UnsupportedProvider,
      fixture.useCase.execute(command()),
    )

    fixture.discovery.result = ProviderDiscoveryResult.Ambiguous(
      setOf(SourceProvider.FLEX, SourceProvider.NINEHIRE),
    )
    assertEquals(
      RegisterCareerSiteResult.AmbiguousProvider(
        setOf(SourceProvider.FLEX, SourceProvider.NINEHIRE),
      ),
      fixture.useCase.execute(command()),
    )

    fixture.discovery.result = ProviderDiscoveryResult.Failed("homepage unavailable")
    assertEquals(
      RegisterCareerSiteResult.DiscoveryFailed("homepage unavailable"),
      fixture.useCase.execute(command()),
    )
    assertTrue(fixture.sites.sites.isEmpty())
  }

  @Test
  fun `existing host returns duplicate without discovery`() = runTest {
    val existing = existingSite()
    val fixture = fixture(listOf(existing))

    assertEquals(
      RegisterCareerSiteResult.AlreadyRegistered(existing),
      fixture.useCase.execute(command()),
    )
    assertTrue(fixture.discovery.requestedUrls.isEmpty())
  }

  @Test
  fun `insert race returns existing site`() = runTest {
    val existing = existingSite()
    val fixture = fixture()
    fixture.discovery.result = ProviderDiscoveryResult.Detected(SourceProvider.GREETING)
    fixture.sites.nextInsertResult = InsertCareerSiteResult.Duplicate(existing)

    assertEquals(
      RegisterCareerSiteResult.AlreadyRegistered(existing),
      fixture.useCase.execute(command()),
    )
    assertEquals(1, fixture.discovery.requestedUrls.size)
    assertTrue(fixture.sites.sites.isEmpty())
  }

  @Test
  fun `successful registration stores detected provider without crawling`() = runTest {
    val fixture = fixture()
    fixture.discovery.result = ProviderDiscoveryResult.Detected(SourceProvider.NINEHIRE)

    val result = assertIs<RegisterCareerSiteResult.Registered>(
      fixture.useCase.execute(command()),
    )

    assertEquals(CareerSiteId(1), result.site.id)
    assertEquals(SourceProvider.NINEHIRE, result.site.provider)
    assertEquals("Acme", result.site.displayName)
    assertEquals(listOf(result.site), fixture.sites.sites)
    assertEquals(1, fixture.discovery.requestedUrls.size)
  }

  @Test
  fun `successful registration applies the configured crawl policy`() = runTest {
    val sites = FakeCareerSiteRepository()
    val discovery = FakeSourceDiscoveryPort().apply {
      result = ProviderDiscoveryResult.Detected(SourceProvider.FLEX)
    }
    val expected = CrawlSettings(successfulInterval = Duration.ofHours(12))
    val useCase = RegisterCareerSite(sites, discovery, expected)

    val result = assertIs<RegisterCareerSiteResult.Registered>(useCase.execute(command()))

    assertEquals(expected, result.site.crawlSettings)
  }

  private fun fixture(initialSites: List<CareerSite> = emptyList()): Fixture {
    val sites = FakeCareerSiteRepository(initialSites)
    val discovery = FakeSourceDiscoveryPort()
    return Fixture(sites, discovery, RegisterCareerSite(sites, discovery))
  }

  private fun command(
    url: String = "https://jobs.example",
    displayName: String = "Acme",
  ) = RegisterCareerSiteCommand(url, displayName)

  private fun existingSite(): CareerSite = CareerSite(
    id = CareerSiteId(42),
    canonicalBaseUrl = validSiteUrl("https://jobs.example"),
    provider = SourceProvider.GREETING,
    displayName = "Existing",
  )

  private fun validSiteUrl(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url

  private data class Fixture(
    val sites: FakeCareerSiteRepository,
    val discovery: FakeSourceDiscoveryPort,
    val useCase: RegisterCareerSite,
  )
}
