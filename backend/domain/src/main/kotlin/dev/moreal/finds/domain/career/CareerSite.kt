package dev.moreal.finds.domain.career

import java.time.Duration

@JvmInline
value class CareerSiteId(val value: Long) {
  init {
    require(value > 0) { "Career-site ID must be positive" }
  }
}

enum class SourceProvider {
  FLEX,
  GREETING,
  NINEHIRE,
}

data class CrawlSettings(
  val successfulInterval: Duration = Duration.ofHours(6),
  val enabled: Boolean = true,
) {
  init {
    require(!successfulInterval.isZero && !successfulInterval.isNegative) {
      "Successful crawl interval must be positive"
    }
    require(successfulInterval <= MAX_SUCCESSFUL_INTERVAL) {
      "Successful crawl interval must not exceed 365 days"
    }
  }

  private companion object {
    val MAX_SUCCESSFUL_INTERVAL: Duration = Duration.ofDays(365)
  }
}

data class CareerSite(
  val id: CareerSiteId,
  val canonicalBaseUrl: SiteUrl,
  val provider: SourceProvider,
  val displayName: String,
  val crawlSettings: CrawlSettings = CrawlSettings(),
  /** Assigned by persistence once; null only for non-persisted fixtures/adapters. */
  val slug: String? = null,
) {
  init {
    require(displayName.isNotBlank()) { "Career-site display name must not be blank" }
  }
}
