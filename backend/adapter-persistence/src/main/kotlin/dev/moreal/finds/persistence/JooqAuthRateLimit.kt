package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.*
import java.time.Instant
import java.time.OffsetDateTime
import java.time.Duration
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.exception.DataAccessException

class JooqAuthRateLimit(private val context: DSLContext) : AuthRateLimitPort {
  override fun consume(buckets: List<AuthRateBucket>, now: Instant): AuthRateDecision {
    require(buckets.size in 1..3) { "Invalid authentication budget set" }
    val keyed = buckets.map { bucket ->
      "v${bucket.key.pepperVersion}:" + bucket.key.bytes.joinToString("") { "%02x".format(it) } to bucket.limit
    }.sortedBy { it.first }
    require(keyed.map { it.first }.distinct().size == keyed.size) { "Duplicate authentication budget" }
    return transaction { db ->
      var retry = 0
      // Global key order prevents deadlocks when independent dimensions overlap across requests.
      keyed.forEach { (key, limit) ->
        val row = db.fetchOne("""
          INSERT INTO auth_rate_buckets (bucket_key, attempts, expires_at) VALUES (?, 1, ?::timestamptz)
          ON CONFLICT (bucket_key) DO UPDATE SET
            attempts = CASE WHEN auth_rate_buckets.expires_at <= ?::timestamptz THEN 1 ELSE least(auth_rate_buckets.attempts + 1, 1001) END,
            expires_at = CASE WHEN auth_rate_buckets.expires_at <= ?::timestamptz THEN EXCLUDED.expires_at ELSE auth_rate_buckets.expires_at END
          RETURNING attempts, expires_at
        """.trimIndent(), key, now.plusSeconds(60).sqlTime(), now.sqlTime(), now.sqlTime())!!
        if (row.get("attempts", Int::class.java)!! > limit) {
          val expiry = row.get("expires_at", OffsetDateTime::class.java)!!.toInstant()
          retry = maxOf(retry, ((Duration.between(now, expiry).toMillis() + 999) / 1000).toInt().coerceIn(1, 60))
        }
      }
      if (retry == 0) AuthRateDecision.Allowed else AuthRateDecision.Limited(retry)
    }
  }
  override fun purgeExpired(now: Instant, limit: Int): Int {
    require(limit in 1..1000) { "Invalid authentication cleanup limit" }
    return transaction { db -> db.execute("""
      DELETE FROM auth_rate_buckets WHERE bucket_key IN (
        SELECT bucket_key FROM auth_rate_buckets WHERE expires_at <= ?::timestamptz
        ORDER BY expires_at, bucket_key LIMIT ? FOR UPDATE SKIP LOCKED
      )
    """.trimIndent(), now.sqlTime(), limit) }
  }
  private fun <T> transaction(block: (DSLContext) -> T): T = try {
    context.transactionResult { config ->
      val settings = (config.settings().clone() as org.jooq.conf.Settings).withExecuteLogging(false)
      block(DSL.using(config.derive(settings)))
    }
  } catch (failure: DataAccessException) { throw IllegalStateException("Authentication budget persistence failed (${failure.sqlState()})") }
}
