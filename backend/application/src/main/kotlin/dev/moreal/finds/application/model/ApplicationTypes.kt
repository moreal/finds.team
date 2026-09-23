package dev.moreal.finds.application.model

import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.CrawlSettings
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.crawl.CrawlOutcome
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.posting.JobPostingId
import java.time.Instant

@JvmInline
value class CrawlRunId(val value: Long) {
  init {
    require(value > 0) { "Crawl-run ID must be positive" }
  }
}

data class PageRequest(
  val size: Int = 20,
  val after: SearchCursor? = null,
) {
  init {
    require(size in 1..100) { "Page size must be between 1 and 100" }
  }
}

data class SearchCursor(
  val updatedAt: Instant,
  val id: JobPostingId,
)

data class SearchPage(
  val postings: List<JobPosting>,
  val next: SearchCursor?,
  val totalCount: Long,
) {
  init {
    require(totalCount >= 0) { "Total count must not be negative" }
  }
}

data class NewCareerSite(
  val canonicalBaseUrl: SiteUrl,
  val provider: SourceProvider,
  val displayName: String,
  val crawlSettings: CrawlSettings = CrawlSettings(),
) {
  init {
    require(displayName.isNotBlank()) { "Career-site display name must not be blank" }
    require(displayName == displayName.trim()) { "Career-site display name must be trimmed" }
  }
}

enum class CrawlFailureCode {
  LEASE_EXPIRED,
  CANCELLED,
  SOURCE_FETCH_FAILED,
  ROBOTS_DENIED,
  ROBOTS_UNAVAILABLE,
  TIMEOUT,
  PARSE_FAILED,
  SUSPICIOUS_SNAPSHOT,
  WRONG_SITE,
  WRONG_POSTING_HOST,
  DUPLICATE_EXTERNAL_KEY,
  PERSISTENCE_FAILED,
}

class CrawlFailure(
  val code: CrawlFailureCode,
  message: String,
) {
  val message: String = message.replace(Regex("[\\r\\n]+"), " ").trim()

  init {
    require(message.length <= MAX_MESSAGE_LENGTH) {
      "Crawl failure message must not exceed $MAX_MESSAGE_LENGTH characters"
    }
  }

  override fun equals(other: Any?): Boolean =
    other is CrawlFailure && code == other.code && message == other.message

  override fun hashCode(): Int = 31 * code.hashCode() + message.hashCode()

  override fun toString(): String = "CrawlFailure(code=$code, message=$message)"

  private companion object {
    const val MAX_MESSAGE_LENGTH = 1_000
  }
}

data class CrawlChangeCounts(
  val fetched: Int,
  val inserted: Int,
  val updated: Int,
  val touched: Int,
  val missing: Int,
  val closed: Int,
  val reopened: Int,
) {
  init {
    require(
      listOf(fetched, inserted, updated, touched, missing, closed, reopened)
        .all { it >= 0 },
    ) { "Crawl change counts must not be negative" }
  }
}

data class CrawlStatus(
  val careerSiteId: CareerSiteId,
  val runId: CrawlRunId?,
  val outcome: CrawlOutcome?,
  val finishedAt: Instant?,
  val failure: CrawlFailure?,
)
