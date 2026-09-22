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

class NinehireSourceAdapterTest {
  @Test
  fun `parses structured postings and canonicalizes query and apply suffix`() = runTest {
    val web = ScriptedWebClient(
      listOf(
        response("https://acme.ninehire.site/robots.txt", "user-agent: *\nallow: /"),
        response("https://acme.ninehire.site/sitemap.xml", resource("/fixtures/ninehire/sitemap.xml")),
        response("https://acme.ninehire.site/job_posting/key-1?utm_source=test", resource("/fixtures/ninehire/open.html")),
        response("https://acme.ninehire.site/job_posting/key-2/apply", resource("/fixtures/ninehire/closed.html")),
      ),
    )

    val snapshot = assertIs<SourceFetchResult.Success>(adapter(web).fetch(site())).snapshot

    val posting = snapshot.postings.single()
    assertEquals("key-1", posting.externalKey)
    assertEquals("Backend Engineer", posting.title)
    assertEquals("What you will do Build systems", posting.descriptionText)
    assertEquals("https://acme.ninehire.site/job_posting/key-1", posting.canonicalUrl.value.toString())
    assertEquals("full_time", posting.employmentHint)
    assertEquals("Seoul", posting.locationHint)
  }

  @Test
  fun `narrow html fallback works but unknown shapes fail the snapshot`() = runTest {
    val fallback = "<h1 data-testid='job-title'>Fallback Role</h1><div data-testid='job-description'><p>Fallback body</p></div>"
    val success = scriptedSingle("fallback", fallback)
    assertIs<SourceFetchResult.Success>(adapter(success).fetch(site()))

    val malformed = scriptedSingle("bad", "<html><h1>Unknown</h1></html>")
    assertIs<SourceFetchResult.Failure>(adapter(malformed).fetch(site()))
  }

  private fun scriptedSingle(key: String, body: String): ScriptedWebClient {
    val sitemap = "<urlset><url><loc>https://acme.ninehire.site/job_posting/$key</loc></url></urlset>"
    return ScriptedWebClient(
      listOf(
        response("https://acme.ninehire.site/robots.txt", "user-agent: *\nallow: /"),
        response("https://acme.ninehire.site/sitemap.xml", sitemap),
        response("https://acme.ninehire.site/job_posting/$key", body),
      ),
    )
  }

  private fun adapter(web: ScriptedWebClient): NinehireSourceAdapter {
    val settings = SourceProtocolSettings()
    val robots = RobotsClient(web, settings, "finds_team", FakeMonotonicTime())
    return NinehireSourceAdapter(
      web,
      robots,
      SitemapCrawler(web, robots, settings),
      ClockPort { NOW },
    )
  }

  private fun site() = CareerSite(
    CareerSiteId(1), url("https://acme.ninehire.site"), SourceProvider.NINEHIRE, "Acme",
  )

  private fun response(value: String, body: String) = WebResult.Success(
    WebResponse(200, url(value), body = body.encodeToByteArray()),
  )
  private fun resource(path: String): String = requireNotNull(javaClass.getResource(path)).readText()
  private fun url(value: String): SiteUrl = assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url

  private companion object { val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z") }
}
