package dev.moreal.finds_team.service.crawler

import dev.moreal.finds_team.model.CareerSite
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlexCrawlerTests {
  private val crawler = FlexCrawler()
  private val careerSite = CareerSite(
    name = "Acme",
    url = "https://acme.careers.team",
  )

  @Test
  fun `extracts customer ID from Next data`() {
    val homepage = """
      <html>
        <body>
          <script id="__NEXT_DATA__" type="application/json">
            {
              "props": {
                "pageProps": {
                  "recruitingSiteResponse": {
                    "customerIdHash": "customer-123",
                    "ignored": true
                  }
                }
              }
            }
          </script>
        </body>
      </html>
    """.trimIndent()

    assertEquals("customer-123", crawler.extractCustomerIdHash(homepage))
  }

  @Test
  fun `parses jobs and falls back when the role is absent`() {
    val response = """
      {
        "jobDescriptions": [
          {
            "jobDescriptionIdHash": "backend-1",
            "title": "Backend Engineer",
            "jobRoleName": "Platform Engineer",
            "recruitingEmploymentContractType": "REGULAR"
          },
          {
            "jobDescriptionIdHash": "product-2",
            "title": "Product Manager",
            "recruitingEmploymentContractType": "CONTRACT"
          },
          {
            "jobDescriptionIdHash": "",
            "title": "Incomplete posting"
          }
        ]
      }
    """.trimIndent()

    val jobs = crawler.parseJobDescriptions(response, careerSite)

    assertEquals(2, jobs.size)
    assertEquals("Backend Engineer", jobs[0].title)
    assertEquals("Platform Engineer", jobs[0].description)
    assertEquals(
      "https://acme.careers.team/job-descriptions/backend-1",
      jobs[0].url,
    )
    assertSame(careerSite, jobs[0].careerSite)
    assertEquals("CONTRACT", jobs[1].description)
  }

  @Test
  fun `matches only direct careers team subdomains`() {
    assertTrue(crawler.matches(careerSite))
    assertTrue(crawler.matches(careerSite.copy(url = "https://ACME.CAREERS.TEAM")))
    assertFalse(crawler.matches(careerSite.copy(url = "https://acme.careers.team.evil.test")))
    assertFalse(crawler.matches(careerSite.copy(url = "https://nested.acme.careers.team")))
    assertFalse(crawler.matches(careerSite.copy(url = "//acme.careers.team")))
    assertFalse(crawler.matches(careerSite.copy(url = "http://acme.careers.team")))
    assertFalse(crawler.matches(careerSite.copy(url = "not a URL")))
  }
}
