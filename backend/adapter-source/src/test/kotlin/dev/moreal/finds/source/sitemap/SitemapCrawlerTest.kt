package dev.moreal.finds.source.sitemap

import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.source.protocol.SourceProtocolSettings
import dev.moreal.finds.source.protocol.WebResponse
import dev.moreal.finds.source.protocol.WebResult
import dev.moreal.finds.source.robots.RobotsClient
import dev.moreal.finds.source.testing.FakeMonotonicTime
import dev.moreal.finds.source.testing.ScriptedWebClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class SitemapCrawlerTest {
  @Test
  fun `follows advertised nested indexes deduplicates cycles and keeps same-origin urls`() = runTest {
    val web = ScriptedWebClient(
      listOf(
        response(
          "https://jobs.example/robots.txt",
          "user-agent: *\nallow: /\nsitemap: https://jobs.example/root.xml",
        ),
        response(
          "https://jobs.example/root.xml",
          """
          <sitemapindex>
            <sitemap><loc>https://jobs.example/jobs.xml</loc></sitemap>
            <sitemap><loc>https://jobs.example/root.xml</loc></sitemap>
          </sitemapindex>
          """.trimIndent(),
        ),
        response(
          "https://jobs.example/jobs.xml",
          """
          <urlset>
            <url><loc>https://jobs.example/o/1</loc><lastmod>2026-09-20</lastmod></url>
            <url><loc>https://jobs.example/o/1</loc></url>
            <url><loc>https://foreign.example/o/2</loc></url>
          </urlset>
          """.trimIndent(),
        ),
      ),
    )
    val crawler = crawler(web)

    val result = assertIs<SitemapCrawlResult.Success>(
      crawler.discover(url("https://jobs.example")),
    )

    assertEquals(
      listOf(SitemapUrl(url("https://jobs.example/o/1"), "2026-09-20")),
      result.urls,
    )
    assertEquals(
      listOf(
        "https://jobs.example/robots.txt",
        "https://jobs.example/root.xml",
        "https://jobs.example/jobs.xml",
      ),
      web.requests.map { it.url.value.toString() },
    )
  }

  @Test
  fun `robots denial fails the crawl instead of producing an empty snapshot`() = runTest {
    val web = ScriptedWebClient(
      listOf(
        response(
          "https://jobs.example/robots.txt",
          "user-agent: *\ndisallow: /sitemap.xml",
        ),
      ),
    )

    val result = crawler(web).discover(url("https://jobs.example"))

    assertEquals(
      SitemapFailureCode.ROBOTS_DENIED,
      assertIs<SitemapCrawlResult.Failure>(result).code,
    )
    assertEquals(1, web.requests.size)
  }

  @Test
  fun `denied nested sitemap fails instead of returning a partial snapshot`() = runTest {
    val web = ScriptedWebClient(
      listOf(
        response(
          "https://jobs.example/robots.txt",
          "user-agent: *\nallow: /sitemap.xml\ndisallow: /private.xml",
        ),
        response(
          "https://jobs.example/sitemap.xml",
          "<sitemapindex><sitemap><loc>https://jobs.example/private.xml</loc></sitemap></sitemapindex>",
        ),
      ),
    )

    val result = crawler(web).discover(url("https://jobs.example"))

    assertEquals(
      SitemapFailureCode.ROBOTS_DENIED,
      assertIs<SitemapCrawlResult.Failure>(result).code,
    )
  }

  @Test
  fun `fails when nesting or aggregate url limits are exceeded`() = runTest {
    val nestedWeb = ScriptedWebClient(
      listOf(
        response("https://jobs.example/robots.txt", "user-agent: *\nallow: /"),
        response(
          "https://jobs.example/sitemap.xml",
          "<sitemapindex><sitemap><loc>https://jobs.example/deeper.xml</loc></sitemap></sitemapindex>",
        ),
      ),
    )
    val nested = crawler(
      nestedWeb,
      SourceProtocolSettings(maxSitemapDepth = 1),
    ).discover(url("https://jobs.example"))
    assertEquals(SitemapFailureCode.LIMIT_EXCEEDED, assertIs<SitemapCrawlResult.Failure>(nested).code)

    val urlsWeb = ScriptedWebClient(
      listOf(
        response("https://jobs.example/robots.txt", "user-agent: *\nallow: /"),
        response(
          "https://jobs.example/sitemap.xml",
          "<urlset><url><loc>https://jobs.example/1</loc></url><url><loc>https://jobs.example/2</loc></url></urlset>",
        ),
      ),
    )
    val tooMany = crawler(
      urlsWeb,
      SourceProtocolSettings(maxSitemapUrls = 1),
    ).discover(url("https://jobs.example"))
    assertEquals(SitemapFailureCode.LIMIT_EXCEEDED, assertIs<SitemapCrawlResult.Failure>(tooMany).code)
  }

  private fun crawler(
    web: ScriptedWebClient,
    settings: SourceProtocolSettings = SourceProtocolSettings(),
  ): SitemapCrawler {
    val time = FakeMonotonicTime()
    return SitemapCrawler(
      web,
      RobotsClient(web, settings, "finds_team", time),
      settings,
    )
  }

  private fun response(url: String, body: String): WebResult.Success = WebResult.Success(
    WebResponse(200, url(url), body = body.encodeToByteArray()),
  )

  private fun url(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url
}
