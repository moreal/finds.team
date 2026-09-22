package dev.moreal.finds.source.provider

import dev.moreal.finds.application.port.ProviderDiscoveryResult
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.source.protocol.SourceProtocolSettings
import dev.moreal.finds.source.protocol.WebFailure
import dev.moreal.finds.source.protocol.WebFailureCode
import dev.moreal.finds.source.protocol.WebResponse
import dev.moreal.finds.source.protocol.WebResult
import dev.moreal.finds.source.robots.RobotsClient
import dev.moreal.finds.source.testing.FakeMonotonicTime
import dev.moreal.finds.source.testing.ScriptedWebClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProviderDetectorTest {
  @Test
  fun `managed hosts are detected without network access`() = runTest {
    val web = ScriptedWebClient()
    val detector = detector(web)

    assertDetected(SourceProvider.FLEX, detector.detect(url("https://acme.careers.team")))
    assertDetected(
      SourceProvider.GREETING,
      detector.detect(url("https://acme.career.greetinghr.com")),
    )
    assertDetected(SourceProvider.NINEHIRE, detector.detect(url("https://acme.ninehire.site")))
    assertEquals(emptyList(), web.requests)
  }

  @Test
  fun `custom domains use strict fixture fingerprints`() = runTest {
    SourceProvider.entries.forEach { provider ->
      val fixture = provider.name.lowercase()
      val web = ScriptedWebClient(
        listOf(
          success("user-agent: *\nallow: /", "https://jobs.example/robots.txt"),
          success(resource("/fixtures/discovery/$fixture.html"), "https://jobs.example"),
        ),
      )

      assertDetected(provider, detector(web).detect(url("https://jobs.example/supplied/path")))
      assertEquals(2, web.requests.size)
      assertEquals("https://jobs.example", web.requests.last().url.value.toString())
    }
  }

  @Test
  fun `zero multiple and protocol failures have typed results`() = runTest {
    val unsupportedWeb = scriptedHomepage("<html><title>Jobs</title></html>")
    assertEquals(
      ProviderDiscoveryResult.Unsupported,
      detector(unsupportedWeb).detect(url("https://jobs.example")),
    )

    val ambiguousHtml = resource("/fixtures/discovery/flex.html") +
      resource("/fixtures/discovery/ninehire.html")
    val ambiguous = assertIs<ProviderDiscoveryResult.Ambiguous>(
      detector(scriptedHomepage(ambiguousHtml)).detect(url("https://jobs.example")),
    )
    assertEquals(setOf(SourceProvider.FLEX, SourceProvider.NINEHIRE), ambiguous.providers)

    val failedWeb = ScriptedWebClient(
      listOf(WebResult.Failure(WebFailure(WebFailureCode.TIMEOUT, "robots timeout"))),
    )
    val failed = assertIs<ProviderDiscoveryResult.Failed>(
      detector(failedWeb).detect(url("https://jobs.example")),
    )
    assertEquals("robots timeout", failed.reason)
  }

  private fun detector(web: ScriptedWebClient): ProviderDetector {
    val settings = SourceProtocolSettings()
    return ProviderDetector(
      web,
      RobotsClient(web, settings, "finds_team", FakeMonotonicTime()),
    )
  }

  private fun scriptedHomepage(html: String) = ScriptedWebClient(
    listOf(
      success("user-agent: *\nallow: /", "https://jobs.example/robots.txt"),
      success(html, "https://jobs.example"),
    ),
  )

  private fun success(body: String, value: String) = WebResult.Success(
    WebResponse(200, url(value), body = body.encodeToByteArray()),
  )

  private fun resource(path: String): String = requireNotNull(javaClass.getResource(path)).readText()

  private fun assertDetected(provider: SourceProvider, result: ProviderDiscoveryResult) {
    assertEquals(provider, assertIs<ProviderDiscoveryResult.Detected>(result).provider)
  }

  private fun url(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url
}
