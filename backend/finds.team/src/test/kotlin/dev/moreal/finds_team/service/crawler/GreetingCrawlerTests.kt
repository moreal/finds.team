package dev.moreal.finds_team.service.crawler

import dev.moreal.finds_team.model.CareerSite
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GreetingCrawlerTests {
  private val crawler = GreetingCrawler()
  private val careerSite = CareerSite(
    name = "Acme",
    url = "https://acme.career.greetinghr.com",
  )

  @Test
  fun `keeps only same-host job URLs from sitemap`() {
    val sitemap = """
      <?xml version="1.0" encoding="UTF-8"?>
      <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"
          xmlns:xhtml="http://www.w3.org/1999/xhtml">
        <url>
          <loc>https://acme.career.greetinghr.com/intro</loc>
        </url>
        <url>
          <loc>https://acme.career.greetinghr.com/o/101</loc>
          <xhtml:link rel="alternate" hreflang="en"
              href="https://acme.career.greetinghr.com/en/o/101" />
        </url>
        <url>
          <loc>https://acme.career.greetinghr.com/ko/o/102</loc>
        </url>
        <url>
          <loc>https://acme.career.greetinghr.com.evil.test/o/103</loc>
        </url>
        <url>
          <loc>http://acme.career.greetinghr.com/o/104</loc>
        </url>
        <url>
          <loc>https://acme.career.greetinghr.com/o/101</loc>
        </url>
      </urlset>
    """.trimIndent()

    assertEquals(
      listOf(
        "https://acme.career.greetinghr.com/o/101",
        "https://acme.career.greetinghr.com/ko/o/102",
      ),
      crawler.parseJobUrls(sitemap, careerSite),
    )
  }

  @Test
  fun `parses open job from Next hydration data`() {
    val page = jobPage(
      status = "OPEN",
      title = "Backend Engineer",
      detail = "<p>Build <strong>great products</strong></p><p>Work together</p>",
    )

    val job = crawler.parseJobPage(
      page,
      "https://acme.career.greetinghr.com/o/101",
      careerSite,
    )

    requireNotNull(job)
    assertEquals("Backend Engineer", job.title)
    assertEquals("Build great products Work together", job.description)
    assertEquals("https://acme.career.greetinghr.com/o/101", job.url)
    assertSame(careerSite, job.careerSite)
  }

  @Test
  fun `skips closed and malformed job pages`() {
    assertNull(
      crawler.parseJobPage(
        jobPage(status = "CLOSED", title = "Closed", detail = "<p>Done</p>"),
        "https://acme.career.greetinghr.com/o/101",
        careerSite,
      ),
    )
    assertNull(
      crawler.parseJobPage(
        "<html><body>No Next data</body></html>",
        "https://acme.career.greetinghr.com/o/102",
        careerSite,
      ),
    )
  }

  @Test
  fun `matches only direct greeting subdomains`() {
    assertTrue(crawler.matches(careerSite))
    assertTrue(
      crawler.matches(
        careerSite.copy(url = "https://ACME.CAREER.GREETINGHR.COM"),
      ),
    )
    assertFalse(
      crawler.matches(
        careerSite.copy(url = "https://acme.career.greetinghr.com.evil.test"),
      ),
    )
    assertFalse(
      crawler.matches(
        careerSite.copy(url = "https://nested.acme.career.greetinghr.com"),
      ),
    )
    assertFalse(
      crawler.matches(careerSite.copy(url = "//acme.career.greetinghr.com")),
    )
    assertFalse(
      crawler.matches(careerSite.copy(url = "http://acme.career.greetinghr.com")),
    )
    assertFalse(crawler.matches(careerSite.copy(url = "not a URL")))
  }

  private fun jobPage(
    status: String,
    title: String,
    detail: String,
  ): String = """
    <html>
      <body>
        <script id="__NEXT_DATA__" type="application/json">
          {
            "props": {
              "pageProps": {
                "dehydratedState": {
                  "queries": [
                    {
                      "state": {
                        "data": {
                          "data": []
                        }
                      }
                    },
                    {
                      "state": {
                        "data": {
                          "data": {
                            "openingsInfo": {
                              "status": "$status",
                              "title": "$title",
                              "detail": "$detail"
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
          }
        </script>
      </body>
    </html>
  """.trimIndent()
}
