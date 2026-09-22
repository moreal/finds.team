package dev.moreal.finds.source.provider

import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.source.protocol.WebClient
import dev.moreal.finds.source.protocol.WebFailureCode
import dev.moreal.finds.source.protocol.WebRequest
import dev.moreal.finds.source.protocol.WebResult
import dev.moreal.finds.source.robots.RobotsClient
import dev.moreal.finds.source.robots.RobotsDecision

interface SourceAdapter {
  val provider: SourceProvider

  suspend fun fetch(site: CareerSite): SourceFetchResult
}

internal sealed interface ProviderReadResult {
  data class Success(val body: String) : ProviderReadResult

  data class Failure(val failure: CrawlFailure) : ProviderReadResult
}

internal suspend fun readSource(
  web: WebClient,
  robots: RobotsClient,
  url: SiteUrl,
  allowedHosts: Set<SiteHost> = setOf(url.host),
  accept: String? = null,
): ProviderReadResult {
  when (val decision = robots.evaluate(url)) {
    is RobotsDecision.Denied -> return ProviderReadResult.Failure(
      CrawlFailure(CrawlFailureCode.ROBOTS_DENIED, "robots.txt denied ${decision.path}"),
    )
    is RobotsDecision.Unavailable -> return ProviderReadResult.Failure(
      CrawlFailure(CrawlFailureCode.ROBOTS_UNAVAILABLE, decision.failure.message),
    )
    is RobotsDecision.Allowed -> Unit
  }
  return when (val result = web.execute(WebRequest(url, allowedHosts, accept))) {
    is WebResult.Failure -> ProviderReadResult.Failure(
      CrawlFailure(
        if (result.failure.code == WebFailureCode.TIMEOUT) {
          CrawlFailureCode.TIMEOUT
        } else {
          CrawlFailureCode.SOURCE_FETCH_FAILED
        },
        result.failure.message,
      ),
    )
    is WebResult.Success -> if (result.response.status in 200..299) {
      ProviderReadResult.Success(result.response.bodyText())
    } else {
      ProviderReadResult.Failure(
        CrawlFailure(
          CrawlFailureCode.SOURCE_FETCH_FAILED,
          "Source returned HTTP ${result.response.status}",
        ),
      )
    }
  }
}
