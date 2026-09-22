package dev.moreal.finds.source.sitemap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SitemapParserTest {
  @Test
  fun `parses namespaced url sets with optional lastmod`() {
    val result = assertIs<SitemapParseResult.Success>(
      SitemapParser.parse(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
          <url><loc>https://jobs.example/a</loc><lastmod>2026-09-20</lastmod></url>
          <url><loc>https://jobs.example/b</loc></url>
        </urlset>
        """.trimIndent().encodeToByteArray(),
      ),
    )

    val document = assertIs<SitemapDocument.UrlSet>(result.document)
    assertEquals(
      listOf(
        SitemapLocation("https://jobs.example/a", "2026-09-20"),
        SitemapLocation("https://jobs.example/b", null),
      ),
      document.locations,
    )
  }

  @Test
  fun `parses non-namespaced sitemap indexes`() {
    val result = assertIs<SitemapParseResult.Success>(
      SitemapParser.parse(
        """
        <sitemapindex>
          <sitemap><loc>https://jobs.example/one.xml</loc></sitemap>
          <sitemap><loc>https://jobs.example/two.xml</loc><lastmod>2026-09-21T12:00:00Z</lastmod></sitemap>
        </sitemapindex>
        """.trimIndent().encodeToByteArray(),
      ),
    )

    assertEquals(2, assertIs<SitemapDocument.Index>(result.document).locations.size)
  }

  @Test
  fun `rejects malformed wrong-root missing-location and doctype documents`() {
    val invalid = listOf(
      "<urlset><url></url></urlset>",
      "<html></html>",
      "<urlset>",
      "<!DOCTYPE urlset [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><urlset></urlset>",
    )

    invalid.forEach { xml ->
      assertIs<SitemapParseResult.Failure>(SitemapParser.parse(xml.encodeToByteArray()))
    }
  }
}
