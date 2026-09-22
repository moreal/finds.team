package dev.moreal.finds.source.provider

import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.domain.career.CareerSite
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup

class GreetingSourceAdapter(
  private val web: WebClient,
  private val robots: RobotsClient,
  private val sitemaps: SitemapCrawler,
  private val clock: ClockPort,
) : SourceAdapter {
  override val provider: SourceProvider = SourceProvider.GREETING
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun fetch(site: CareerSite): SourceFetchResult {
    if (site.provider != provider) return failure("Career site is not a Greeting site")
    val discovered = when (val result = sitemaps.discover(site.canonicalBaseUrl)) {
      is SitemapCrawlResult.Failure -> return SourceFetchResult.Failure(
        CrawlFailure(
          if (result.code == SitemapFailureCode.ROBOTS_UNAVAILABLE) {
            CrawlFailureCode.ROBOTS_UNAVAILABLE
          } else {
            CrawlFailureCode.PARSE_FAILED
          },
          result.message,
        ),
      )
      is SitemapCrawlResult.Success -> result.urls
        .filter { GREETING_PATH.matches(it.url.value.path) }
    }
    val postings = mutableListOf<RawPosting>()
    for (entry in discovered) {
      val body = when (val read = readSource(web, robots, entry.url, accept = "text/html")) {
        is ProviderReadResult.Failure -> return SourceFetchResult.Failure(read.failure)
        is ProviderReadResult.Success -> read.body
      }
      when (val parsed = parsePage(body, entry)) {
        PageResult.Closed -> Unit
        is PageResult.Failed -> return failure(parsed.reason)
        is PageResult.Open -> postings += parsed.posting
      }
    }
    return SourceFetchResult.Success(
      Snapshot(site.id, site.canonicalBaseUrl.host, clock.now(), postings),
    )
  }

  internal fun parsePage(html: String, entry: SitemapUrl): PageResult {
    val data = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")
      ?.data()?.trim().orEmpty()
    if (data.isEmpty()) return PageResult.Failed("Greeting __NEXT_DATA__ is missing")
    val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
      ?: return PageResult.Failed("Greeting hydration is malformed")
    val opening = findOpening(root)
      ?: return PageResult.Failed("Greeting openingsInfo is missing")
    return when (opening.string("status")) {
      "CLOSED" -> PageResult.Closed
      "OPEN" -> {
        val title = opening.string("title")?.trim().orEmpty()
        if (title.isEmpty()) return PageResult.Failed("Greeting title is missing")
        val key = entry.url.value.path.substringAfterLast('/').trim()
        if (!key.all(Char::isDigit)) return PageResult.Failed("Greeting posting key is invalid")
        val description = opening.string("detail")
          ?.let(Jsoup::parseBodyFragment)?.text()?.trim().orEmpty().ifEmpty { title }
        PageResult.Open(
          RawPosting(
            key,
            title,
            description,
            requirePostingUrl(entry.url.value.toString()),
          ),
        )
      }
      else -> PageResult.Failed("Greeting posting status is missing or unsupported")
    }
  }

  internal sealed interface PageResult {
    data class Open(val posting: RawPosting) : PageResult
    data object Closed : PageResult
    data class Failed(val reason: String) : PageResult
  }

  private fun findOpening(root: JsonObject): JsonObject? {
    val props = root["props"] as? JsonObject ?: return null
    val pageProps = props["pageProps"] as? JsonObject ?: return null
    val dehydrated = pageProps["dehydratedState"] as? JsonObject ?: return null
    val queries = dehydrated["queries"] as? JsonArray ?: return null
    return queries.firstNotNullOfOrNull { query ->
      val state = (query as? JsonObject)?.get("state") as? JsonObject
      val data = state?.get("data") as? JsonObject
      val nested = data?.get("data") as? JsonObject
      nested?.get("openingsInfo") as? JsonObject
    }
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

  private fun failure(reason: String) = SourceFetchResult.Failure(
    CrawlFailure(CrawlFailureCode.PARSE_FAILED, reason.take(1_000)),
  )

  private companion object {
    val GREETING_PATH = Regex("^/o/[0-9]+$")
    fun requirePostingUrl(value: String): PostingUrl =
      (PostingUrl.parse(value) as PostingUrlResult.Valid).url
  }
}
