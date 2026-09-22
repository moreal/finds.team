package dev.moreal.finds.source.provider

import dev.moreal.finds.application.port.ProviderDiscoveryResult
import dev.moreal.finds.application.port.SourceDiscoveryPort
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.source.protocol.WebClient
import dev.moreal.finds.source.protocol.WebRequest
import dev.moreal.finds.source.protocol.WebResult
import dev.moreal.finds.source.robots.RobotsClient
import dev.moreal.finds.source.robots.RobotsDecision
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup

class ProviderDetector(
  private val web: WebClient,
  private val robots: RobotsClient,
) : SourceDiscoveryPort {
  override suspend fun detect(url: SiteUrl): ProviderDiscoveryResult {
    detectManagedHost(url)?.let { return ProviderDiscoveryResult.Detected(it) }
    val homepage = origin(url)

    when (val decision = robots.evaluate(homepage)) {
      is RobotsDecision.Unavailable -> {
        return ProviderDiscoveryResult.Failed(decision.failure.message)
      }
      is RobotsDecision.Denied -> {
        return ProviderDiscoveryResult.Failed("robots.txt denied homepage discovery")
      }
      is RobotsDecision.Allowed -> Unit
    }

    val html = try {
      when (val result = web.execute(WebRequest(homepage, accept = "text/html"))) {
        is WebResult.Failure -> return ProviderDiscoveryResult.Failed(result.failure.message)
        is WebResult.Success -> {
          if (result.response.status !in 200..299) {
            return ProviderDiscoveryResult.Failed(
              "Homepage returned HTTP ${result.response.status}",
            )
          }
          result.response.bodyText()
        }
      }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      return ProviderDiscoveryResult.Failed(error.safeMessage())
    }

    val matches = fingerprint(html)
    return when (matches.size) {
      0 -> ProviderDiscoveryResult.Unsupported
      1 -> ProviderDiscoveryResult.Detected(matches.single())
      else -> ProviderDiscoveryResult.Ambiguous(matches)
    }
  }

  internal fun fingerprint(html: String): Set<SourceProvider> {
    val document = Jsoup.parse(html)
    val nextData = document.selectFirst("script#__NEXT_DATA__")?.data().orEmpty()
    return buildSet {
      if (
        nextData.contains("recruitingSiteResponse") &&
        nextData.contains("customerIdHash")
      ) {
        add(SourceProvider.FLEX)
      }
      if (
        document.select("script[src]").any {
          it.attr("abs:src").contains("greetinghr.com", ignoreCase = true) ||
            it.attr("src").contains("greetinghr.com", ignoreCase = true)
        } && nextData.contains("dehydratedState")
      ) {
        add(SourceProvider.GREETING)
      }
      val hasNinehireAsset = document.select("[src], [content]").any { element ->
        element.attr("src").contains("ninehire.com", ignoreCase = true) ||
          element.attr("content").contains("ninehire.com", ignoreCase = true)
      }
      val hasNinehireRoute = document.select("script[src]").any { element ->
        val source = element.attr("src")
        source.contains("/_next/static/") &&
          source.contains("chunks/pages/")
      }
      if (hasNinehireAsset && hasNinehireRoute) {
        add(SourceProvider.NINEHIRE)
      }
    }
  }

  private fun detectManagedHost(url: SiteUrl): SourceProvider? = when {
    FLEX_HOST.matches(url.host.value) -> SourceProvider.FLEX
    GREETING_HOST.matches(url.host.value) -> SourceProvider.GREETING
    NINEHIRE_HOST.matches(url.host.value) -> SourceProvider.NINEHIRE
    else -> null
  }

  private fun origin(url: SiteUrl): SiteUrl = when (
    val parsed = SiteUrl.parse("https://${url.host.value}")
  ) {
    is SiteUrlResult.Valid -> parsed.url
    is SiteUrlResult.Invalid -> error(parsed.reason)
  }

  private fun Throwable.safeMessage(): String =
    (message ?: this::class.simpleName ?: "Homepage discovery failed")
      .replace(Regex("[\\r\\n]+"), " ")
      .trim()
      .take(1_000)

  private companion object {
    val FLEX_HOST = Regex("^[^.]+\\.careers\\.team$")
    val GREETING_HOST = Regex("^[^.]+\\.career\\.greetinghr\\.com$")
    val NINEHIRE_HOST = Regex("^[^.]+\\.ninehire\\.site$")
  }
}
