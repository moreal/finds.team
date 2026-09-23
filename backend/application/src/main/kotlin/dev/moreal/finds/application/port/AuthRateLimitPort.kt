package dev.moreal.finds.application.port

import java.time.Instant

/** Only keyed, purpose-separated digests cross this boundary; never raw email/IP/device values. */
data class AuthRateBucket(val key: KeyedIdentityHash, val limit: Int) {
  init { require(limit in 1..1000) { "Invalid authentication budget" } }
}
sealed interface AuthRateDecision {
  data object Allowed : AuthRateDecision
  data class Limited(val retryAfterSeconds: Int) : AuthRateDecision {
    init { require(retryAfterSeconds in 1..60) }
  }
}
interface AuthRateLimitPort {
  /** Atomically counts every bucket, including denied attempts, in a fixed 60-second window. */
  fun consume(buckets: List<AuthRateBucket>, now: Instant): AuthRateDecision
  fun purgeExpired(now: Instant, limit: Int): Int
}
