package dev.moreal.finds.domain.career

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SiteUrlTest {
  @Test
  fun `normalizes scheme IDNA host and trailing slash`() {
    val result = SiteUrl.parse("HTTPS://채용.example/path/")

    val url = assertIs<SiteUrlResult.Valid>(result).url
    assertEquals("https", url.value.scheme)
    assertEquals("xn--oo5bn6h.example", url.host.value)
    assertEquals("/path", url.value.path)
    assertEquals("https://xn--oo5bn6h.example/path", url.value.toASCIIString())
  }

  @Test
  fun `rejects unsafe site URLs`() {
    listOf(
      "http://jobs.example",
      "//jobs.example",
      "https://user@jobs.example",
      "https://jobs.example:8443",
      "https://127.0.0.1",
      "https://[::1]",
      "https://jobs.example/#fragment",
      "not a URL",
    ).forEach { value ->
      assertIs<SiteUrlResult.Invalid>(SiteUrl.parse(value), value)
    }
  }

  @Test
  fun `crawl settings require positive bounded durations`() {
    assertFailsWith<IllegalArgumentException> {
      CrawlSettings(successfulInterval = Duration.ZERO)
    }
    assertFailsWith<IllegalArgumentException> {
      CrawlSettings(successfulInterval = Duration.ofSeconds(-1))
    }
    assertFailsWith<IllegalArgumentException> {
      CrawlSettings(successfulInterval = Duration.ofDays(366))
    }
  }

  @Test
  fun `career site identity and display name must be valid`() {
    assertFailsWith<IllegalArgumentException> { CareerSiteId(0) }
    assertFailsWith<IllegalArgumentException> { CareerSiteId(-1) }

    val url = assertIs<SiteUrlResult.Valid>(SiteUrl.parse("https://jobs.example")).url
    assertFailsWith<IllegalArgumentException> {
      CareerSite(
        id = CareerSiteId(1),
        canonicalBaseUrl = url,
        provider = SourceProvider.GREETING,
        displayName = " ",
      )
    }
  }

  @Test
  fun `site host only accepts normalized public DNS names`() {
    assertEquals("jobs.example", SiteHost("jobs.example").value)
    assertFailsWith<IllegalArgumentException> { SiteHost("JOBS.EXAMPLE") }
    assertFailsWith<IllegalArgumentException> { SiteHost("127.0.0.1") }
    assertFailsWith<IllegalArgumentException> { SiteHost("localhost") }
  }
}
