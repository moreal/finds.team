package dev.moreal.finds.domain.crawl

import dev.moreal.finds.domain.career.CrawlSettings
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CrawlPolicyTest {
  @Test
  fun `never crawled enabled site is due`() {
    assertEquals(
      CrawlEligibility.Due(CrawlDueReason.NEVER_CRAWLED),
      decideCrawlEligibility(settings(), history = null, RETRY_POLICY, NOW),
    )
  }

  @Test
  fun `success waits for configured interval and becomes due at boundary`() {
    val finishedAt = NOW.minusSeconds(60)
    val history = CrawlHistory(CrawlOutcome.SUCCESS, finishedAt, consecutiveFailures = 0)
    val next = finishedAt.plus(Duration.ofHours(6))

    assertEquals(
      CrawlEligibility.NotDue(next),
      decideCrawlEligibility(settings(), history, RETRY_POLICY, NOW),
    )
    assertEquals(
      CrawlEligibility.Due(CrawlDueReason.SUCCESS_INTERVAL_ELAPSED),
      decideCrawlEligibility(settings(), history, RETRY_POLICY, next),
    )
  }

  @Test
  fun `failure backoff is bounded at final delay`() {
    val history = CrawlHistory(
      CrawlOutcome.FAILED,
      NOW.minus(Duration.ofHours(1)),
      consecutiveFailures = 99,
    )

    assertEquals(
      CrawlEligibility.NotDue(NOW.plus(Duration.ofHours(1))),
      decideCrawlEligibility(settings(), history, RETRY_POLICY, NOW),
    )
  }

  @Test
  fun `failure becomes due when selected backoff elapses`() {
    val history = CrawlHistory(
      CrawlOutcome.FAILED,
      NOW.minus(Duration.ofMinutes(5)),
      consecutiveFailures = 1,
    )

    assertEquals(
      CrawlEligibility.Due(CrawlDueReason.RETRY_BACKOFF_ELAPSED),
      decideCrawlEligibility(settings(), history, RETRY_POLICY, NOW),
    )
  }

  @Test
  fun `disabled site is never due`() {
    assertEquals(
      CrawlEligibility.Disabled("Career site is disabled"),
      decideCrawlEligibility(settings(enabled = false), null, RETRY_POLICY, NOW),
    )
  }

  @Test
  fun `next eligible instant saturates instead of overflowing`() {
    val history = CrawlHistory(
      CrawlOutcome.SUCCESS,
      Instant.MAX.minusSeconds(1),
      consecutiveFailures = 0,
    )

    assertEquals(
      CrawlEligibility.NotDue(Instant.MAX),
      decideCrawlEligibility(
        settings(),
        history,
        RETRY_POLICY,
        Instant.MAX.minusNanos(1),
      ),
    )
  }

  @Test
  fun `retry policy and history reject inconsistent values`() {
    assertFailsWith<IllegalArgumentException> { RetryPolicy(emptyList()) }
    assertFailsWith<IllegalArgumentException> {
      RetryPolicy(listOf(Duration.ofMinutes(5), Duration.ofMinutes(1)))
    }
    assertFailsWith<IllegalArgumentException> {
      RetryPolicy(listOf(Duration.ZERO))
    }
    assertFailsWith<IllegalArgumentException> {
      CrawlHistory(CrawlOutcome.FAILED, NOW, consecutiveFailures = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      CrawlHistory(CrawlOutcome.SUCCESS, NOW, consecutiveFailures = 1)
    }
  }

  private fun settings(enabled: Boolean = true) = CrawlSettings(
    successfulInterval = Duration.ofHours(6),
    enabled = enabled,
  )

  private companion object {
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
    val RETRY_POLICY = RetryPolicy(
      listOf(
        Duration.ofMinutes(5),
        Duration.ofMinutes(30),
        Duration.ofHours(2),
      ),
    )
  }
}
