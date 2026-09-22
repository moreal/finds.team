package dev.moreal.finds.source.robots

import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.source.protocol.SourceProtocolSettings
import dev.moreal.finds.source.protocol.WebFailure
import dev.moreal.finds.source.protocol.WebFailureCode
import dev.moreal.finds.source.protocol.WebRequest
import dev.moreal.finds.source.protocol.WebResponse
import dev.moreal.finds.source.protocol.WebResult
import dev.moreal.finds.source.testing.FakeMonotonicTime
import dev.moreal.finds.source.testing.ScriptedWebClient
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RobotsClientTest {
  @Test
  fun `fetches robots once and returns allow deny plus same-origin sitemaps`() = runTest {
    val web = ScriptedWebClient(
      listOf(
        success(
          200,
          """
          user-agent: finds_team
          disallow: /private
          sitemap: https://jobs.example/jobs.xml
          sitemap: https://foreign.example/jobs.xml
          """.trimIndent(),
        ),
      ),
    )
    val client = client(web)

    val allowed = assertIs<RobotsDecision.Allowed>(client.evaluate(url("https://jobs.example/jobs")))
    val denied = assertIs<RobotsDecision.Denied>(client.evaluate(url("https://jobs.example/private/1")))

    assertEquals(listOf(url("https://jobs.example/jobs.xml")), allowed.sitemaps)
    assertEquals("/private/1", denied.path)
    assertEquals(1, web.requests.size)
    assertEquals("https://jobs.example/robots.txt", web.requests.single().url.value.toString())
  }

  @Test
  fun `successful and unavailable cache entries use separate bounded ttls`() = runTest {
    val time = FakeMonotonicTime()
    val web = ScriptedWebClient(
      listOf(
        success(200, "user-agent: *\nallow: /"),
        WebResult.Failure(WebFailure(WebFailureCode.TIMEOUT, "slow")),
        success(200, "user-agent: *\nallow: /"),
      ),
    )
    val client = client(web, time)
    val target = url("https://jobs.example/jobs")

    assertIs<RobotsDecision.Allowed>(client.evaluate(target))
    time.advance(Duration.ofHours(23))
    assertIs<RobotsDecision.Allowed>(client.evaluate(target))
    time.advance(Duration.ofHours(2))
    assertIs<RobotsDecision.Unavailable>(client.evaluate(target))
    time.advance(Duration.ofMinutes(4))
    assertIs<RobotsDecision.Unavailable>(client.evaluate(target))
    time.advance(Duration.ofMinutes(2))
    assertIs<RobotsDecision.Allowed>(client.evaluate(target))
    assertEquals(3, web.requests.size)
  }

  @Test
  fun `status handling allows missing robots and fails closed on server errors`() = runTest {
    val missing = client(ScriptedWebClient(listOf(success(404, "missing"))))
    val unavailable = client(ScriptedWebClient(listOf(success(503, "down"))))

    assertIs<RobotsDecision.Allowed>(missing.evaluate(url("https://jobs.example/jobs")))
    assertIs<RobotsDecision.Unavailable>(
      unavailable.evaluate(url("https://jobs.example/jobs")),
    )
  }

  @Test
  fun `concurrent first reads coalesce per origin`() = runTest {
    val web = ScriptedWebClient(listOf(success(200, "user-agent: *\nallow: /")))
    val client = client(web)
    val target = url("https://jobs.example/jobs")

    val first = async { client.evaluate(target) }
    val second = async { client.evaluate(target) }

    assertIs<RobotsDecision.Allowed>(first.await())
    assertIs<RobotsDecision.Allowed>(second.await())
    assertEquals(1, web.requests.size)
  }

  private fun client(
    web: ScriptedWebClient,
    time: FakeMonotonicTime = FakeMonotonicTime(),
  ) = RobotsClient(
    web = web,
    settings = SourceProtocolSettings(),
    productToken = "finds_team",
    clock = time,
  )

  private fun success(status: Int, body: String): WebResult.Success = WebResult.Success(
    WebResponse(status, url("https://jobs.example/robots.txt"), body = body.encodeToByteArray()),
  )

  private fun url(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url
}
