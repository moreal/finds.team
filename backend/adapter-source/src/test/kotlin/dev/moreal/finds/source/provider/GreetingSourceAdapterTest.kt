package dev.moreal.finds.source.provider

import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.source.protocol.SourceProtocolSettings
import dev.moreal.finds.source.protocol.WebResponse
import dev.moreal.finds.source.protocol.WebResult
import dev.moreal.finds.source.robots.RobotsClient
import dev.moreal.finds.source.sitemap.SitemapCrawler
import dev.moreal.finds.source.testing.FakeMonotonicTime
import dev.moreal.finds.source.testing.ScriptedWebClient
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GreetingSourceAdapterTest {
  @Test
  fun `discovers sitemap and returns only explicitly open hydrated postings`() = runTest {
    val web = ScriptedWebClient(
      listOf(
        response("https://acme.career.greetinghr.com/robots.txt", "user-agent: *\nallow: /"),
        response("https://acme.career.greetinghr.com/sitemap.xml", resource("/fixtures/greeting/sitemap.xml")),
        response("https://acme.career.greetinghr.com/o/101", resource("/fixtures/greeting/open.html")),
        response("https://acme.career.greetinghr.com/o/102", resource("/fixtures/greeting/closed.html")),
      ),
    )

    val snapshot = assertIs<SourceFetchResult.Success>(adapter(web).fetch(site())).snapshot

    assertEquals(listOf("101"), snapshot.postings.map { it.externalKey })
    assertEquals("Backend Engineer", snapshot.postings.single().title)
    assertEquals("Build & operate", snapshot.postings.single().descriptionText)
  }

  @Test
  fun `missing required hydration fails the whole snapshot`() = runTest {
    val sitemap = "<urlset><url><loc>https://acme.career.greetinghr.com/o/101</loc></url></urlset>"
    val web = ScriptedWebClient(
      listOf(
        response("https://acme.career.greetinghr.com/robots.txt", "user-agent: *\nallow: /"),
        response("https://acme.career.greetinghr.com/sitemap.xml", sitemap),
        response("https://acme.career.greetinghr.com/o/101", "<html></html>"),
      ),
    )

    assertIs<SourceFetchResult.Failure>(adapter(web).fetch(site()))
  }

  private fun adapter(web: ScriptedWebClient): GreetingSourceAdapter {
    val settings = SourceProtocolSettings()
    val robots = RobotsClient(web, settings, "finds_team", FakeMonotonicTime())
    return GreetingSourceAdapter(
      web,
      robots,
      SitemapCrawler(web, robots, settings),
      ClockPort { NOW },
    )
  }

  private fun site() = CareerSite(
    CareerSiteId(1),
    url("https://acme.career.greetinghr.com"),
    SourceProvider.GREETING,
    "Acme",
  )

  private fun response(value: String, body: String) = WebResult.Success(
    WebResponse(200, url(value), body = body.encodeToByteArray()),
  )

  private fun resource(path: String): String = requireNotNull(javaClass.getResource(path)).readText()
  private fun url(value: String): SiteUrl = assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url

  private companion object {
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
  }
}
