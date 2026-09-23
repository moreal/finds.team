package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.jooq.SQLDialect
import org.jooq.impl.DSL

class JooqAuthRateLimitTest : PostgresIntegrationTest() {
  private val now = Instant.parse("2026-09-23T00:00:00Z")
  private fun bucket(value: Int, limit: Int = 3) = AuthRateBucket(KeyedIdentityHash(1, ByteArray(32) { value.toByte() }), limit)

  @Test fun `independent connections share atomic budget across service instances and restarts`() {
    val (source, db) = migratedContext()
    val a = JooqAuthRateLimit(DSL.using(source, SQLDialect.POSTGRES))
    val b = JooqAuthRateLimit(DSL.using(source, SQLDialect.POSTGRES))
    val index = AtomicInteger()
    val outcomes = race(8) { (if (index.getAndIncrement() % 2 == 0) a else b).consume(listOf(bucket(1)), now) }
    assertEquals(3, outcomes.count { it == AuthRateDecision.Allowed })
    assertEquals(5, outcomes.count { it == AuthRateDecision.Limited(60) })
    assertEquals(AuthRateDecision.Limited(1), JooqAuthRateLimit(db).consume(listOf(bucket(1)), now.plusSeconds(59)))
    assertEquals(AuthRateDecision.Allowed, b.consume(listOf(bucket(1)), now.plusSeconds(60)))
    assertEquals(1, db.fetchValue("select attempts from auth_rate_buckets"))
  }
  @Test fun `all independent budgets count blocked attempts and cleanup is bounded`() {
    val (_, db) = migratedContext(); val store = JooqAuthRateLimit(db)
    assertEquals(AuthRateDecision.Allowed, store.consume(listOf(bucket(1, 1), bucket(2, 2)), now))
    assertEquals(AuthRateDecision.Limited(60), store.consume(listOf(bucket(1, 1), bucket(2, 2)), now))
    assertEquals(AuthRateDecision.Limited(60), store.consume(listOf(bucket(3, 1), bucket(2, 2)), now))
    assertEquals(0, store.purgeExpired(now, 2))
    assertEquals(2, store.purgeExpired(now.plusSeconds(60), 2))
    assertEquals(1, store.purgeExpired(now.plusSeconds(60), 2))
    assertFailsWith<IllegalArgumentException> { store.purgeExpired(now, 1001) }
  }
}
