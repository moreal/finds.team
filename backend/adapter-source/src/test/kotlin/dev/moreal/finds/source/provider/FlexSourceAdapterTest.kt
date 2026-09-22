package dev.moreal.finds.source.provider

import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.source.protocol.SourceProtocolSettings
import dev.moreal.finds.source.protocol.WebResponse
import dev.moreal.finds.source.protocol.WebResult
import dev.moreal.finds.source.robots.RobotsClient
import dev.moreal.finds.source.testing.FakeMonotonicTime
import dev.moreal.finds.source.testing.ScriptedWebClient
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlexSourceAdapterTest {
  @Test
  fun `fetches robots homepage and public API into a complete snapshot`() = runTest {
    val web = ScriptedWebClient(
      listOf(
        response("https://acme.careers.team/robots.txt", "user-agent: *\nallow: /"),
        response("https://acme.careers.team", resource("/fixtures/flex/homepage.html")),
        response("https://flex.team/robots.txt", "user-agent: *\nallow: /"),
        response(
          "https://flex.team/api-public/v2/recruiting/customers/customer-redacted/sites/job-descriptions",
          resource("/fixtures/flex/jobs.json"),
        ),
      ),
    )
    val adapter = adapter(web)

    val snapshot = assertIs<SourceFetchResult.Success>(adapter.fetch(site())).snapshot

    assertEquals(listOf("role-1", "role-2"), snapshot.postings.map { it.externalKey })
    assertEquals("Platform", snapshot.postings[0].descriptionText)
    assertEquals("CONTRACT", snapshot.postings[1].descriptionText)
    assertEquals("https://acme.careers.team/job-descriptions/role-1", snapshot.postings[0].canonicalUrl.value.toString())
    assertEquals(NOW, snapshot.fetchedAt)
  }

  @Test
  fun `missing bootstrap malformed API and duplicate ids are typed failures`() = runTest {
    val cases = listOf(
      "<html></html>" to "{}",
      resource("/fixtures/flex/homepage.html") to "not-json",
      resource("/fixtures/flex/homepage.html") to
        "{\"jobDescriptions\":[{\"jobDescriptionIdHash\":\"dup\",\"title\":\"A\"},{\"jobDescriptionIdHash\":\"dup\",\"title\":\"B\"}]}",
    )
    cases.forEach { (homepage, jobs) ->
      val web = ScriptedWebClient(
        listOf(
          response("https://acme.careers.team/robots.txt", "user-agent: *\nallow: /"),
          response("https://acme.careers.team", homepage),
          response("https://flex.team/robots.txt", "user-agent: *\nallow: /"),
          response("https://flex.team/api-public/v2/recruiting/customers/customer-redacted/sites/job-descriptions", jobs),
        ),
      )
      assertIs<SourceFetchResult.Failure>(adapter(web).fetch(site()))
    }
  }

  private fun adapter(web: ScriptedWebClient): FlexSourceAdapter {
    val settings = SourceProtocolSettings()
    return FlexSourceAdapter(
      web,
      RobotsClient(web, settings, "finds_team", FakeMonotonicTime()),
      ClockPort { NOW },
    )
  }

  private fun site() = CareerSite(
    CareerSiteId(1),
    url("https://acme.careers.team"),
    SourceProvider.FLEX,
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
