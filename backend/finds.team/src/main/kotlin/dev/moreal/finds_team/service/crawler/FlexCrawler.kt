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
class FlexCrawler : JobCrawler {
  override fun crawlJobs(careerSite: CareerSite): List<Job> {
    val jobs = mutableListOf<Job>()
    val uri = URI("https", URI(careerSite.url).host, "/", "")

    val client = HttpClient(CIO)
    val body = runBlocking { client.get(uri.toURL()).bodyAsText() }
    val document = Jsoup.parse(body)
    val nextDataScript =
      document.body().select("script[id=__NEXT_DATA__]").first()
        ?: throw Exception("Failed to find __NEXT_DATA__ script tag")

    val nextDataScriptText = nextDataScript.html()
    val json = Json { ignoreUnknownKeys = true }
    val flexNextData = json.decodeFromString<FlexNextData>(nextDataScriptText)
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
    val pattern = Regex(pattern = "^[^.]+.careers.team$")

    return pattern.find(URI(careerSite.url).host) != null
  }

  @Serializable
  private data class FlexNextData(
    val props: FlexProps,
  )

  @Serializable
  private data class FlexProps(
    val pageProps: FlexPageProps,
  )

  @Serializable
  private data class FlexPageProps(
    val recruitingSiteResponse: FlexRecruitingSiteResponse,
  )

  @Serializable
  private data class FlexRecruitingSiteResponse(
    val customerIdHash: String,
  )

  @Serializable
  private data class JobDescriptionsResponse(
    val jobDescriptions: List<JobDescription>,
  )

  @Serializable
  private data class JobDescription(
    val jobDescriptionIdHash: String,
    val customerJobGroupIdHash: String? = null, // Made nullable as some entries don't have this field
    val title: String,
    val jobRoleName: String,
    val recruitingEmploymentContractType: String,
    val isOccasionalRecruitment: Boolean,
    val recruitingStartDate: String? = null, // Made nullable as some entries don't have this field
    val recruitingEndDate: String? = null, // Made nullable as some entries don't have this field
  )
}
