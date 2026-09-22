package dev.moreal.finds.domain.crawl

import dev.moreal.finds.domain.career.CrawlSettings
import java.time.Duration
import java.time.Instant

enum class CrawlOutcome {
  SUCCESS,
  FAILED,
}

data class CrawlHistory(
  val lastOutcome: CrawlOutcome,
  val finishedAt: Instant,
  val consecutiveFailures: Int,
) {
  init {
    when (lastOutcome) {
      CrawlOutcome.SUCCESS -> require(consecutiveFailures == 0) {
        "Successful crawl history must reset consecutive failures"
      }
      CrawlOutcome.FAILED -> require(consecutiveFailures >= 1) {
        "Failed crawl history must have at least one consecutive failure"
      }
    }
  }
}

class RetryPolicy(delays: List<Duration>) {
  val delays: List<Duration> = delays.toList()

  init {
    require(this.delays.isNotEmpty()) { "Retry policy must contain a delay" }
    require(this.delays.all { !it.isZero && !it.isNegative }) {
      "Retry delays must be positive"
    }
    require(this.delays.zipWithNext().all { (earlier, later) -> earlier <= later }) {
      "Retry delays must not decrease"
    }
  }

  fun delayFor(failureCount: Int): Duration {
    require(failureCount >= 1) { "Failure count must be positive" }
    return delays[(failureCount - 1).coerceAtMost(delays.lastIndex)]
  }
}

enum class CrawlDueReason {
  NEVER_CRAWLED,
  SUCCESS_INTERVAL_ELAPSED,
  RETRY_BACKOFF_ELAPSED,
}

sealed interface CrawlEligibility {
  data class Due(val reason: CrawlDueReason) : CrawlEligibility

  data class NotDue(val nextEligibleAt: Instant) : CrawlEligibility

  data class Disabled(val reason: String) : CrawlEligibility
}

fun decideCrawlEligibility(
  settings: CrawlSettings,
  history: CrawlHistory?,
  retryPolicy: RetryPolicy,
  now: Instant,
): CrawlEligibility {
  if (!settings.enabled) {
    return CrawlEligibility.Disabled("Career site is disabled")
  }
  if (history == null) {
    return CrawlEligibility.Due(CrawlDueReason.NEVER_CRAWLED)
  }

  val delay = when (history.lastOutcome) {
    CrawlOutcome.SUCCESS -> settings.successfulInterval
    CrawlOutcome.FAILED -> retryPolicy.delayFor(history.consecutiveFailures)
  }
  val nextEligibleAt = history.finishedAt.saturatingPlus(delay)
  if (now.isBefore(nextEligibleAt)) {
    return CrawlEligibility.NotDue(nextEligibleAt)
  }

  val reason = when (history.lastOutcome) {
    CrawlOutcome.SUCCESS -> CrawlDueReason.SUCCESS_INTERVAL_ELAPSED
    CrawlOutcome.FAILED -> CrawlDueReason.RETRY_BACKOFF_ELAPSED
  }
  return CrawlEligibility.Due(reason)
}

private fun Instant.saturatingPlus(duration: Duration): Instant =
  runCatching { plus(duration) }.getOrDefault(Instant.MAX)
