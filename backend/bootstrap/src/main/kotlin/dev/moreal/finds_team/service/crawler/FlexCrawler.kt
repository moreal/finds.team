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
  private val json = Json { ignoreUnknownKeys = true }

  override fun crawlJobs(careerSite: CareerSite): List<Job> = runBlocking {
    val host = requireNotNull(siteHost(careerSite.url)) {
      "Career site URL must contain a host"
    }
    val homepageUrl = URI("https", host, "/", null).toString()

    HttpClient(CIO).use { client ->
      val customerIdHash = extractCustomerIdHash(
        client.get(homepageUrl).bodyAsText(),
      )
      val jobDescriptionsUrl =
        "https://flex.team/api-public/v2/recruiting/customers/$customerIdHash/sites/job-descriptions"

      parseJobDescriptions(
        client.get(jobDescriptionsUrl).bodyAsText(),
        careerSite,
      )
    }
  }

  internal fun extractCustomerIdHash(homepageHtml: String): String {
    val nextData = Jsoup.parse(homepageHtml)
      .selectFirst("script#__NEXT_DATA__")
      ?.data()
      ?.trim()
      .orEmpty()
    require(nextData.isNotEmpty()) { "Failed to find __NEXT_DATA__ script tag" }

    return json.decodeFromString<FlexNextData>(nextData)
      .props.pageProps.recruitingSiteResponse.customerIdHash
      .trim()
      .also { require(it.isNotEmpty()) { "Flex customer ID must not be empty" } }
  }

  internal fun parseJobDescriptions(
    responseBody: String,
    careerSite: CareerSite,
  ): List<Job> {
    val host = requireNotNull(siteHost(careerSite.url)) {
      "Career site URL must contain a host"
    }
    val response = json.decodeFromString<JobDescriptionsResponse>(responseBody)

    return response.jobDescriptions.mapNotNull { jobDescription ->
      val id = jobDescription.jobDescriptionIdHash.trim()
      val title = jobDescription.title.trim()
      if (id.isEmpty() || title.isEmpty()) {
        return@mapNotNull null
      }

      val description = jobDescription.jobRoleName
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: jobDescription.recruitingEmploymentContractType
          ?.trim()
          ?.takeIf(String::isNotEmpty)
        ?: title

      Job(
        title = title,
        description = description,
        url = URI(
          "https",
          host,
          "/job-descriptions/$id",
          null,
        ).toString(),
        careerSite = careerSite,
      )
    }
  }

  override fun matches(careerSite: CareerSite): Boolean {
    val host = siteHost(careerSite.url) ?: return false

    return FLEX_HOST_PATTERN.matches(host)
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
    val jobDescriptions: List<JobDescription> = emptyList(),
  )

  @Serializable
  private data class JobDescription(
    val jobDescriptionIdHash: String = "",
    val title: String = "",
    val jobRoleName: String? = null,
    val recruitingEmploymentContractType: String? = null,
  )

  private companion object {
    val FLEX_HOST_PATTERN = Regex("^[^.]+\\.careers\\.team$")

    fun siteHost(url: String): String? = runCatching {
      val uri = URI(url)
      if (
        !uri.isAbsolute ||
        !uri.scheme.equals("https", ignoreCase = true) ||
        uri.userInfo != null ||
        uri.port != -1
      ) {
        return@runCatching null
      }

      uri.host?.lowercase()?.takeIf(String::isNotEmpty)
    }.getOrNull()
  }
}
