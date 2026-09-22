package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.port.InsertCareerSiteResult
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.CrawlSettings
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class JooqCareerSiteRepositoryTest : PostgresIntegrationTest() {
  @Test
  fun `insert and find round trip every domain field`() {
    val (_, context) = migratedContext()
    val repository = JooqCareerSiteRepository(context)
    val newSite = NewCareerSite(
      url("https://acme.ninehire.site"),
      SourceProvider.NINEHIRE,
      "Acme",
      CrawlSettings(Duration.ofHours(12), enabled = true),
    )

    val inserted = assertIs<InsertCareerSiteResult.Inserted>(repository.insert(newSite)).site

    assertEquals(CareerSiteId(1), inserted.id)
    assertEquals(inserted, repository.findById(inserted.id))
    assertEquals(inserted, repository.findByHost(inserted.canonicalBaseUrl.host))
    assertNull(repository.findById(CareerSiteId(999)))
  }

  @Test
  fun `duplicate host returns existing row and enabled query is deterministic`() {
    val (_, context) = migratedContext()
    val repository = JooqCareerSiteRepository(context)
    val first = assertIs<InsertCareerSiteResult.Inserted>(
      repository.insert(NewCareerSite(url("https://one.careers.team"), SourceProvider.FLEX, "One")),
    ).site
    repository.insert(
      NewCareerSite(
        url("https://disabled.greetinghr.com"),
        SourceProvider.GREETING,
        "Disabled",
        CrawlSettings(enabled = false),
      ),
    )
    val third = assertIs<InsertCareerSiteResult.Inserted>(
      repository.insert(NewCareerSite(url("https://three.ninehire.site"), SourceProvider.NINEHIRE, "Three")),
    ).site

    val duplicate = repository.insert(
      NewCareerSite(url("https://one.careers.team/jobs"), SourceProvider.FLEX, "Other"),
    )

    assertEquals(first, assertIs<InsertCareerSiteResult.Duplicate>(duplicate).existing)
    assertEquals(listOf(first.id, third.id), repository.findEnabled().map { it.id })
  }

  private fun url(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url
}
