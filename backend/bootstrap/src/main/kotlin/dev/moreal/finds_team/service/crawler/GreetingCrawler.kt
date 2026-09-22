package dev.moreal.finds_team.service.crawler

import dev.moreal.finds_team.model.CareerSite
import dev.moreal.finds_team.model.Job
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import java.net.URI

@Component
class GreetingCrawler : JobCrawler {
  private val json = Json { ignoreUnknownKeys = true }

  override fun crawlJobs(careerSite: CareerSite): List<Job> = runBlocking {
    val host = requireNotNull(siteHost(careerSite.url)) {
      "Career site URL must contain a host"
    }
    val sitemapUrl = URI("https", host, "/sitemap.xml", null).toString()

    HttpClient(CIO).use { client ->
      parseJobUrls(
        client.get(sitemapUrl).bodyAsText(),
        careerSite,
      ).mapNotNull { jobUrl ->
        parseJobPage(
          client.get(jobUrl).bodyAsText(),
          jobUrl,
          careerSite,
        )
      }
    }
  }

  internal fun parseJobUrls(
    sitemapXml: String,
    careerSite: CareerSite,
  ): List<String> {
    val expectedHost = siteHost(careerSite.url) ?: return emptyList()

    return SitemapXmlSerializer.deserialize(sitemapXml).urls
      .asSequence()
      .mapNotNull { sitemapUrl ->
        runCatching { URI(sitemapUrl.location) }.getOrNull()
      }
      .filter { uri ->
        uri.scheme.equals("https", ignoreCase = true) &&
          uri.host.equals(expectedHost, ignoreCase = true) &&
          uri.userInfo == null &&
          uri.port == -1 &&
          GREETING_JOB_PATH_PATTERN.matches(uri.path.orEmpty())
      }
      .map { uri -> URI("https", expectedHost, uri.path, null).toString() }
      .distinct()
      .toList()
  }

  internal fun parseJobPage(
    pageHtml: String,
    jobUrl: String,
    careerSite: CareerSite,
  ): Job? {
    val nextData = Jsoup.parse(pageHtml)
      .selectFirst("script#__NEXT_DATA__")
      ?.data()
      ?.trim()
      .orEmpty()
    if (nextData.isEmpty()) {
      return null
    }

    val root = runCatching {
      json.parseToJsonElement(nextData) as? JsonObject
    }.getOrNull() ?: return null
    val opening = findOpening(root) ?: return null
    if (opening.stringValue("status") != "OPEN") {
      return null
    }

    val title = opening.stringValue("title")?.trim().orEmpty()
    if (title.isEmpty()) {
      return null
    }
    val description = opening.stringValue("detail")
      ?.let(Jsoup::parseBodyFragment)
      ?.text()
      ?.trim()
      .orEmpty()
      .ifEmpty { title }

    return Job(
      title = title,
      description = description,
      url = jobUrl,
      careerSite = careerSite,
    )
  }

  override fun matches(careerSite: CareerSite): Boolean {
    val host = siteHost(careerSite.url) ?: return false

    return GREETING_HOST_PATTERN.matches(host)
  }

  private fun findOpening(root: JsonObject): JsonObject? {
    val props = root["props"] as? JsonObject ?: return null
    val pageProps = props["pageProps"] as? JsonObject ?: return null
    val dehydratedState = pageProps["dehydratedState"] as? JsonObject ?: return null
    val queries = dehydratedState["queries"] as? JsonArray ?: return null

    return queries.firstNotNullOfOrNull { query ->
      val state = (query as? JsonObject)?.get("state") as? JsonObject
      val data = state?.get("data") as? JsonObject
      val nestedData = data?.get("data") as? JsonObject

      nestedData?.get("openingsInfo") as? JsonObject
    }
  }

  private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

  private companion object {
    val GREETING_HOST_PATTERN = Regex("^[^.]+\\.career\\.greetinghr\\.com$")
    val GREETING_JOB_PATH_PATTERN = Regex("^/o/[0-9]+/?$")

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
