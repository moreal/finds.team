package dev.moreal.finds_team.service.crawler

import dev.moreal.finds_team.model.CareerSite
import dev.moreal.finds_team.model.Job
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import java.net.URI

@Component
class GreetingCrawler : JobCrawler {
  override fun crawlJobs(careerSite: CareerSite): List<Job> {
    val jobs = mutableListOf<Job>()
    val robotsUri = URI("https", URI(careerSite.url).host, "/robots.txt", "")

    // Fetch job description page
    val client = HttpClient(CIO)
    val body = runBlocking { client.get(uri.toURL()).bodyAsText() }
    val document = Jsoup.parse(body)
    val nextDataScript =
      document.body().select("script[id=__NEXT_DATA__]").first()
        ?: throw Exception("Failed to find __NEXT_DATA__ script tag")

    val nextDataScriptText = nextDataScript.html()
    val json = Json { ignoreUnknownKeys = true }
    val flexNextData = json.decodeFromString<Page>(nextDataScriptText)
    val customerIdHash =
      flexNextData.props.pageProps.recruitingSiteResponse.customerIdHash

    val jobDescriptionsUrl =
      "https://flex.team/api-public/v2/recruiting/customers/$customerIdHash/sites/job-descriptions"
    val jobDescriptionsResponse: JobDescriptionsResponse =
      json.decodeFromString(runBlocking {
        client.get(jobDescriptionsUrl).bodyAsText()
      })
    jobs.addAll(
      jobDescriptionsResponse.jobDescriptions.map { jobDescription ->
        Job(
          title = jobDescription.title,
          description = jobDescription.jobRoleName,
          url = URI(
            "https",
            uri.host,
            "/job-descriptions/${jobDescription.jobDescriptionIdHash}",
            "",
          ).toString(),
          careerSite = careerSite,
        )
      },
    )

    return jobs
  }

  override fun matches(careerSite: CareerSite): Boolean {
    val pattern = Regex(pattern = "^[^.]+.career.greetinghr.com$")

    return pattern.find(URI(careerSite.url).host) != null
  }
}
