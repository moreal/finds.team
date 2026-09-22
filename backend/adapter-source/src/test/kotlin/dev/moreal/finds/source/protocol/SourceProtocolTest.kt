package dev.moreal.finds.source.protocol

import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.source.testing.FakeMonotonicTime
import dev.moreal.finds.source.testing.ScriptedWebClient
import java.time.Duration
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SourceProtocolTest {
  @Test
  fun `default bounds are explicit and production safe`() {
    val settings = SourceProtocolSettings()

    assertEquals(Duration.ofSeconds(10), settings.connectTimeout)
    assertEquals(Duration.ofSeconds(30), settings.requestTimeout)
    assertEquals(Duration.ofMinutes(5), settings.siteTimeout)
    assertEquals(5 * 1024 * 1024, settings.maxResponseBytes)
    assertEquals(5, settings.maxRedirects)
    assertEquals(Duration.ofHours(24), settings.robotsSuccessTtl)
    assertEquals(Duration.ofMinutes(5), settings.robotsUnavailableTtl)
    assertEquals(3, settings.maxSitemapDepth)
    assertEquals(100, settings.maxSitemapDocuments)
    assertEquals(50_000, settings.maxSitemapUrls)
    assertEquals(Duration.ofMillis(500), settings.minimumHostSpacing)
  }

  @Test
  fun `durations must be positive and bounded`() {
    val invalid = listOf<(Duration) -> SourceProtocolSettings>(
      { SourceProtocolSettings(connectTimeout = it) },
      { SourceProtocolSettings(requestTimeout = it) },
      { SourceProtocolSettings(siteTimeout = it) },
      { SourceProtocolSettings(robotsSuccessTtl = it) },
      { SourceProtocolSettings(robotsUnavailableTtl = it) },
      { SourceProtocolSettings(minimumHostSpacing = it) },
    )

    invalid.forEach { construct ->
      assertFailsWith<IllegalArgumentException> { construct(Duration.ZERO) }
      assertFailsWith<IllegalArgumentException> { construct(Duration.ofDays(8)) }
    }
  }

  @Test
  fun `numeric limits reject zero negative and excessive values`() {
    assertFailsWith<IllegalArgumentException> {
      SourceProtocolSettings(maxResponseBytes = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      SourceProtocolSettings(maxResponseBytes = 50 * 1024 * 1024 + 1)
    }
    assertFailsWith<IllegalArgumentException> {
      SourceProtocolSettings(maxRedirects = -1)
    }
    assertFailsWith<IllegalArgumentException> {
      SourceProtocolSettings(maxRedirects = 11)
    }
    assertFailsWith<IllegalArgumentException> {
      SourceProtocolSettings(maxSitemapDepth = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      SourceProtocolSettings(maxSitemapDocuments = 1_001)
    }
    assertFailsWith<IllegalArgumentException> {
      SourceProtocolSettings(maxSitemapUrls = 1_000_001)
    }
  }

  @Test
  fun `request requires its destination in a nonempty host allowlist`() {
    val url = url("https://jobs.example/openings")

    assertEquals(setOf(SiteHost("jobs.example")), WebRequest(url).allowedHosts)
    assertFailsWith<IllegalArgumentException> {
      WebRequest(url, emptySet())
    }
    assertFailsWith<IllegalArgumentException> {
      WebRequest(url, setOf(SiteHost("api.example")))
    }
  }

  @Test
  fun `response owns bytes and decodes only on demand`() {
    val source = "채용".encodeToByteArray()
    val response = WebResponse(200, url("https://jobs.example"), body = source)
    source.fill(0)

    assertEquals("채용", response.bodyText())
    assertContentEquals("채용".encodeToByteArray(), response.body)
  }

  @Test
  fun `scripted client and fake time record deterministic interactions`() = runTest {
    val response = WebResponse(204, url("https://jobs.example"), body = byteArrayOf())
    val client = ScriptedWebClient(listOf(WebResult.Success(response)))
    val request = WebRequest(url("https://jobs.example/robots.txt"))
    val time = FakeMonotonicTime(100)

    assertIs<WebResult.Success>(client.execute(request))
    time.wait(Duration.ofMillis(500))

    assertEquals(listOf(request), client.requests)
    assertEquals(listOf(Duration.ofMillis(500)), time.waits)
    assertEquals(500_000_100, time.nowNanos())
  }

  private fun url(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url
}
