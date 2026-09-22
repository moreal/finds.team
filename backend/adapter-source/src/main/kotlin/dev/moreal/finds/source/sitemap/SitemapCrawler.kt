package dev.moreal.finds.source.sitemap

import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.source.protocol.SourceProtocolSettings
import dev.moreal.finds.source.protocol.WebClient
import dev.moreal.finds.source.protocol.WebFailureCode
import dev.moreal.finds.source.protocol.WebRequest
import dev.moreal.finds.source.protocol.WebResult
import dev.moreal.finds.source.robots.RobotsClient
import dev.moreal.finds.source.robots.RobotsDecision

data class SitemapUrl(
  val url: SiteUrl,
  val lastModified: String?,
)

enum class SitemapFailureCode {
  ROBOTS_DENIED,
  ROBOTS_UNAVAILABLE,
  FETCH_FAILED,
  MALFORMED,
  LIMIT_EXCEEDED,
}

sealed interface SitemapCrawlResult {
  data class Success(val urls: List<SitemapUrl>) : SitemapCrawlResult

  data class Failure(
    val code: SitemapFailureCode,
    val message: String,
  ) : SitemapCrawlResult
}

class SitemapCrawler(
  private val web: WebClient,
  private val robots: RobotsClient,
  private val settings: SourceProtocolSettings,
) {
  suspend fun discover(site: SiteUrl): SitemapCrawlResult {
    val conventional = requireUrl("https://${site.host.value}/sitemap.xml")
    val initialRobots = robots.evaluate(conventional)
    val initial = when (initialRobots) {
      is RobotsDecision.Unavailable -> return failure(
        SitemapFailureCode.ROBOTS_UNAVAILABLE,
        initialRobots.failure.message,
      )
      is RobotsDecision.Allowed -> initialRobots.sitemaps.ifEmpty { listOf(conventional) }
      is RobotsDecision.Denied -> initialRobots.sitemaps
    }
    if (initial.isEmpty()) {
      return failure(
        SitemapFailureCode.ROBOTS_DENIED,
        "robots.txt denied the conventional sitemap",
      )
    }

    val queue = ArrayDeque(initial.map { PendingDocument(it, depth = 1) })
    val visited = mutableSetOf<SiteUrl>()
    val urls = linkedMapOf<SiteUrl, SitemapUrl>()
    var totalBytes = 0L

    while (queue.isNotEmpty()) {
      val pending = queue.removeFirst()
      if (pending.url.host != site.host || !visited.add(pending.url)) continue
      if (visited.size > settings.maxSitemapDocuments) {
        return limitFailure("Sitemap document limit exceeded")
      }
      when (val decision = robots.evaluate(pending.url)) {
        is RobotsDecision.Unavailable -> return failure(
          SitemapFailureCode.ROBOTS_UNAVAILABLE,
          decision.failure.message,
        )
        is RobotsDecision.Denied -> return failure(
          SitemapFailureCode.ROBOTS_DENIED,
          "robots.txt denied sitemap ${pending.url.value.path}",
        )
        is RobotsDecision.Allowed -> Unit
      }

      val response = when (
        val result = web.execute(
          WebRequest(pending.url, setOf(site.host), "application/xml,text/xml"),
        )
      ) {
        is WebResult.Failure -> return failure(
          SitemapFailureCode.FETCH_FAILED,
          result.failure.message,
        )
        is WebResult.Success -> result.response
      }
      if (response.status !in 200..299) {
        return failure(
          SitemapFailureCode.FETCH_FAILED,
          "Sitemap returned HTTP ${response.status}",
        )
      }
      totalBytes += response.body.size
      if (totalBytes > settings.maxSitemapTotalBytes) {
        return limitFailure("Sitemap byte limit exceeded")
      }
      val document = when (val parsed = SitemapParser.parse(response.body)) {
        is SitemapParseResult.Failure -> return failure(
          SitemapFailureCode.MALFORMED,
          parsed.reason,
        )
        is SitemapParseResult.Success -> parsed.document
      }
      when (document) {
        is SitemapDocument.UrlSet -> document.locations.forEach { location ->
          val parsed = parseUrl(location.location) ?: return@forEach
          if (parsed.host != site.host) return@forEach
          urls.putIfAbsent(parsed, SitemapUrl(parsed, location.lastModified))
          if (urls.size > settings.maxSitemapUrls) {
            return limitFailure("Sitemap URL limit exceeded")
          }
        }
        is SitemapDocument.Index -> {
          if (pending.depth >= settings.maxSitemapDepth) {
            return limitFailure("Sitemap nesting limit exceeded")
          }
          document.locations.forEach { location ->
            val parsed = parseUrl(location.location) ?: return@forEach
            if (parsed.host == site.host && parsed !in visited) {
              queue += PendingDocument(parsed, pending.depth + 1)
            }
          }
        }
      }
    }
    return SitemapCrawlResult.Success(urls.values.toList())
  }

  private fun failure(code: SitemapFailureCode, message: String) =
    SitemapCrawlResult.Failure(
      code,
      message.replace(Regex("[\\r\\n]+"), " ").trim().take(1_000),
    )

  private fun limitFailure(message: String) =
    failure(SitemapFailureCode.LIMIT_EXCEEDED, message)

  private data class PendingDocument(val url: SiteUrl, val depth: Int)

  private companion object {
    fun parseUrl(value: String): SiteUrl? = when (val parsed = SiteUrl.parse(value.trim())) {
      is SiteUrlResult.Valid -> parsed.url
      is SiteUrlResult.Invalid -> null
    }

    fun requireUrl(value: String): SiteUrl = requireNotNull(parseUrl(value))
  }
}
