package dev.moreal.finds.source.provider

import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.crawl.Snapshot
import dev.moreal.finds.domain.posting.PostingUrl
import dev.moreal.finds.domain.posting.PostingUrlResult
import dev.moreal.finds.domain.posting.RawPosting
import dev.moreal.finds.source.protocol.WebClient
import dev.moreal.finds.source.robots.RobotsClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

class FlexSourceAdapter(
  private val web: WebClient,
  private val robots: RobotsClient,
  private val clock: ClockPort,
) : SourceAdapter {
  override val provider: SourceProvider = SourceProvider.FLEX
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun fetch(site: CareerSite): SourceFetchResult {
    if (site.provider != provider) return parseFailure("Career site is not a Flex site")
    val homepage = requireUrl("https://${site.canonicalBaseUrl.host.value}")
    val homepageBody = when (val read = readSource(web, robots, homepage, accept = "text/html")) {
      is ProviderReadResult.Failure -> return SourceFetchResult.Failure(read.failure)
      is ProviderReadResult.Success -> read.body
    }
    val customerId = runCatching { extractCustomerId(homepageBody) }
      .getOrElse { return parseFailure(it.safeMessage()) }
    val apiUrl = requireUrl(
      "https://flex.team/api-public/v2/recruiting/customers/$customerId/sites/job-descriptions",
    )
    val responseBody = when (
      val read = readSource(web, robots, apiUrl, accept = "application/json")
    ) {
      is ProviderReadResult.Failure -> return SourceFetchResult.Failure(read.failure)
      is ProviderReadResult.Success -> read.body
    }
    return runCatching {
      val descriptions = json.decodeFromString<JobDescriptionsResponse>(responseBody)
      val postings = descriptions.jobDescriptions.mapNotNull { description ->
        description.toRawPosting(site)
      }
      val duplicate = postings.groupingBy(RawPosting::externalKey).eachCount()
        .entries.firstOrNull { it.value > 1 }?.key
      if (duplicate != null) {
        return SourceFetchResult.Failure(
          CrawlFailure(CrawlFailureCode.DUPLICATE_EXTERNAL_KEY, "Duplicate Flex key $duplicate"),
        )
      }
      SourceFetchResult.Success(
        Snapshot(site.id, site.canonicalBaseUrl.host, clock.now(), postings),
      )
    }.getOrElse { parseFailure(it.safeMessage()) }
  }

  internal fun extractCustomerId(homepageHtml: String): String {
    val data = Jsoup.parse(homepageHtml).selectFirst("script#__NEXT_DATA__")
      ?.data()?.trim().orEmpty()
    require(data.isNotEmpty()) { "Flex __NEXT_DATA__ is missing" }
    return json.decodeFromString<FlexNextData>(data)
      .props.pageProps.recruitingSiteResponse.customerIdHash.trim()
      .also { require(CUSTOMER_ID.matches(it)) { "Flex customer ID is invalid" } }
  }

  private fun JobDescription.toRawPosting(site: CareerSite): RawPosting? {
    val key = jobDescriptionIdHash.trim()
    val normalizedTitle = title.trim()
    if (!EXTERNAL_KEY.matches(key) || normalizedTitle.isEmpty()) return null
    val contract = recruitingEmploymentContractType?.trim()?.takeIf(String::isNotEmpty)
    val description = jobRoleName?.trim()?.takeIf(String::isNotEmpty) ?: contract ?: normalizedTitle
    return RawPosting(
      externalKey = key,
      title = normalizedTitle,
      descriptionText = Jsoup.parseBodyFragment(description).text().trim(),
      canonicalUrl = requirePostingUrl(
        "https://${site.canonicalBaseUrl.host.value}/job-descriptions/$key",
      ),
      employmentHint = contract,
    )
  }

  private fun parseFailure(message: String) = SourceFetchResult.Failure(
    CrawlFailure(CrawlFailureCode.PARSE_FAILED, message.take(1_000)),
  )

  @Serializable private data class FlexNextData(val props: FlexProps)
  @Serializable private data class FlexProps(val pageProps: FlexPageProps)
  @Serializable private data class FlexPageProps(val recruitingSiteResponse: RecruitingSite)
  @Serializable private data class RecruitingSite(val customerIdHash: String)
  @Serializable private data class JobDescriptionsResponse(
    val jobDescriptions: List<JobDescription> = emptyList(),
  )
  @Serializable private data class JobDescription(
    val jobDescriptionIdHash: String = "",
    val title: String = "",
    val jobRoleName: String? = null,
    val recruitingEmploymentContractType: String? = null,
  )

  private companion object {
    val CUSTOMER_ID = Regex("[A-Za-z0-9_-]{1,200}")
    val EXTERNAL_KEY = Regex("[A-Za-z0-9_-]{1,200}")

    fun requireUrl(value: String): SiteUrl =
      (SiteUrl.parse(value) as SiteUrlResult.Valid).url

    fun requirePostingUrl(value: String): PostingUrl =
      (PostingUrl.parse(value) as PostingUrlResult.Valid).url

    fun Throwable.safeMessage(): String =
      (message ?: this::class.simpleName ?: "Flex source shape is invalid")
        .replace(Regex("[\\r\\n]+"), " ").trim().take(1_000)
  }
}
