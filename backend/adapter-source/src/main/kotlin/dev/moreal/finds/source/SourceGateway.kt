package dev.moreal.finds.source

import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.port.SourceFetchPort
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.source.protocol.SourceProtocolSettings
import dev.moreal.finds.source.provider.SourceAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class SourceGateway(
  adapters: List<SourceAdapter>,
  private val settings: SourceProtocolSettings,
) : SourceFetchPort {
  private val adapters: Map<SourceProvider, SourceAdapter>

  init {
    val duplicates = adapters.groupingBy(SourceAdapter::provider).eachCount()
      .filterValues { it > 1 }.keys
    require(duplicates.isEmpty()) { "Duplicate source adapters: $duplicates" }
    this.adapters = adapters.associateBy(SourceAdapter::provider)
  }

  override suspend fun fetch(site: CareerSite): SourceFetchResult {
    val adapter = adapters[site.provider] ?: return failed(
      CrawlFailureCode.SOURCE_FETCH_FAILED,
      "No source adapter for ${site.provider}",
    )
    return try {
      withTimeout(settings.siteTimeout.toMillis()) {
        adapter.fetch(site)
      }
    } catch (error: TimeoutCancellationException) {
      failed(CrawlFailureCode.TIMEOUT, "Site crawl timed out")
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      failed(CrawlFailureCode.SOURCE_FETCH_FAILED, error.safeMessage())
    }
  }

  private fun failed(code: CrawlFailureCode, message: String) = SourceFetchResult.Failure(
    CrawlFailure(code, message.replace(Regex("[\\r\\n]+"), " ").trim().take(1_000)),
  )

  private fun Throwable.safeMessage(): String =
    (message ?: this::class.simpleName ?: "Source adapter failed")
      .replace(Regex("[\\r\\n]+"), " ").trim().take(1_000)
}
