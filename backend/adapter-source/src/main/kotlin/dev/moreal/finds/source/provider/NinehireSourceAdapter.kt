package dev.moreal.finds.source.provider

import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.crawl.Snapshot
import dev.moreal.finds.domain.posting.PostingUrl
import dev.moreal.finds.domain.posting.PostingUrlResult
import dev.moreal.finds.domain.posting.RawPosting
import dev.moreal.finds.source.protocol.WebClient
import dev.moreal.finds.source.robots.RobotsClient
import dev.moreal.finds.source.sitemap.SitemapCrawlResult
import dev.moreal.finds.source.sitemap.SitemapCrawler
import dev.moreal.finds.source.sitemap.SitemapFailureCode
import dev.moreal.finds.source.sitemap.SitemapUrl
import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup

class NinehireSourceAdapter(
  private val web: WebClient,
  private val robots: RobotsClient,
  private val sitemaps: SitemapCrawler,
  private val clock: ClockPort,
) : SourceAdapter {
  override val provider: SourceProvider = SourceProvider.NINEHIRE
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun fetch(site: CareerSite): SourceFetchResult {
    if (site.provider != provider) return failure("Career site is not a Ninehire site")
    val discovered = when (val result = sitemaps.discover(site.canonicalBaseUrl)) {
      is SitemapCrawlResult.Failure -> return SourceFetchResult.Failure(
        CrawlFailure(
          when (result.code) {
            SitemapFailureCode.ROBOTS_DENIED -> CrawlFailureCode.ROBOTS_DENIED
            SitemapFailureCode.ROBOTS_UNAVAILABLE -> CrawlFailureCode.ROBOTS_UNAVAILABLE
            SitemapFailureCode.FETCH_FAILED -> CrawlFailureCode.SOURCE_FETCH_FAILED
            SitemapFailureCode.MALFORMED,
            SitemapFailureCode.LIMIT_EXCEEDED,
            -> CrawlFailureCode.PARSE_FAILED
          },
          result.message,
        ),
      )
      is SitemapCrawlResult.Success -> result.urls.filter { entry ->
        NINEHIRE_PATH.matches(entry.url.value.path)
      }
    }
    val postings = mutableListOf<RawPosting>()
    for (entry in discovered) {
      val read = when (val result = readSource(web, robots, entry.url, accept = "text/html")) {
        is ProviderReadResult.Failure -> return SourceFetchResult.Failure(result.failure)
        is ProviderReadResult.Success -> result
      }
      if (read.finalUrl.value.path == "/invalid") continue
      when (val parsed = parsePage(read.body, entry)) {
        PageResult.Closed -> Unit
        is PageResult.Failed -> return failure(parsed.reason)
        is PageResult.Open -> postings += parsed.posting
      }
    }
    val duplicate = postings.groupingBy(RawPosting::externalKey).eachCount()
      .entries.firstOrNull { it.value > 1 }?.key
    if (duplicate != null) {
      return SourceFetchResult.Failure(
        CrawlFailure(CrawlFailureCode.DUPLICATE_EXTERNAL_KEY, "Duplicate Ninehire key $duplicate"),
      )
    }
    return SourceFetchResult.Success(
      Snapshot(site.id, site.canonicalBaseUrl.host, clock.now(), postings),
    )
  }

  internal fun parsePage(html: String, entry: SitemapUrl): PageResult {
    val keyFromPath = externalKey(entry.url)
      ?: return PageResult.Failed("Ninehire posting path is invalid")
    val document = Jsoup.parse(html)
    val data = document.selectFirst("script#__NEXT_DATA__")?.data()?.trim().orEmpty()
    if (data.isEmpty()) return parseFallback(document, entry, keyFromPath)
    val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
      ?: return PageResult.Failed("Ninehire bootstrap is malformed")
    val pageProps = ((root["props"] as? JsonObject)?.get("pageProps") as? JsonObject)
      ?: return PageResult.Failed("Ninehire pageProps is missing")
    val recruitment = pageProps["recruitment"] as? JsonObject
      ?: return PageResult.Failed("Ninehire recruitment is missing")
    val status = recruitment.string("status")
      ?: return PageResult.Failed("Ninehire status is missing")
    if (status != "in_progress") return PageResult.Closed
    val key = recruitment.string("addressKey")?.trim().orEmpty()
    if (key != keyFromPath || !EXTERNAL_KEY.matches(key)) {
      return PageResult.Failed("Ninehire address key does not match URL")
    }
    val title = (recruitment.string("externalTitle") ?: recruitment.string("title"))
      ?.trim().orEmpty()
    if (title.isEmpty()) return PageResult.Failed("Ninehire title is missing")
    val posting = pageProps["jobPosting"] as? JsonObject
      ?: return PageResult.Failed("Ninehire jobPosting is missing")
    val content = posting.string("content")
      ?: return PageResult.Failed("Ninehire posting content is missing")
    val employment = (recruitment["employmentType"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      ?.joinToString(", ")?.takeIf(String::isNotEmpty)
    val location = (recruitment["jobLocations"] as? JsonArray)
      ?.mapNotNull { (it as? JsonObject)?.string("placeName") }
      ?.joinToString(", ")?.takeIf(String::isNotEmpty)
    return PageResult.Open(
      RawPosting(
        externalKey = key,
        title = title,
        descriptionText = Jsoup.parseBodyFragment(content).text().trim().ifEmpty { title },
        canonicalUrl = canonicalUrl(entry.url, key),
        employmentHint = employment,
        locationHint = location,
        sourceUpdatedAt = recruitment.string("updatedAt")?.let(::parseInstant),
      ),
    )
  }

  private fun parseFallback(
    document: org.jsoup.nodes.Document,
    entry: SitemapUrl,
    key: String,
  ): PageResult {
    val title = document.selectFirst("[data-testid=job-title]")?.text()?.trim().orEmpty()
    val body = document.selectFirst("[data-testid=job-description]")?.text()?.trim().orEmpty()
    if (title.isEmpty() || body.isEmpty()) {
      return PageResult.Failed("Ninehire structured data and fallback fields are missing")
    }
    return PageResult.Open(RawPosting(key, title, body, canonicalUrl(entry.url, key)))
  }

  private fun canonicalUrl(source: SiteUrl, key: String): PostingUrl {
    val uri = URI("https", null, source.host.value, -1, "/job_posting/$key", null, null)
    return (PostingUrl.parse(uri.toString()) as PostingUrlResult.Valid).url
  }

  private fun externalKey(url: SiteUrl): String? {
    val match = NINEHIRE_PATH.matchEntire(url.value.path) ?: return null
    return match.groupValues[1].takeIf(EXTERNAL_KEY::matches)
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

  private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

  private fun failure(reason: String) = SourceFetchResult.Failure(
    CrawlFailure(CrawlFailureCode.PARSE_FAILED, reason.take(1_000)),
  )

  internal sealed interface PageResult {
    data class Open(val posting: RawPosting) : PageResult
    data object Closed : PageResult
    data class Failed(val reason: String) : PageResult
  }

  private companion object {
    val NINEHIRE_PATH = Regex("^/job_posting/([A-Za-z0-9_-]+)(?:/apply)?$")
    val EXTERNAL_KEY = Regex("[A-Za-z0-9_-]{1,200}")
  }
}
