package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import java.time.Instant
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class OtpResponseTimingTest {
  @Test fun `floor subtracts elapsed work uses both jitter bounds and also covers rejection`() {
    for (jitter in listOf(0, 20)) for (work in listOf(0L, 75_000_000L, 250_000_000L)) for (reject in listOf(false, true)) {
      var nanos = 5_000_000_000L
      val sleeps = mutableListOf<Long>()
      val random = object : SecureRandomPort by JvmSecureRandom() { override fun nextInt(bound: Int): Int {
        assertEquals(21, bound); return jitter
      } }
      val boundary = OtpHttpBoundary(mock(AuthRateLimitPort::class.java), mock(KeyedIdentityHashPort::class.java),
        random, ClockPort { Instant.EPOCH }, SecurityProperties(),
        nanoTime = { nanos }, sleepNanos = { sleeps += it; nanos += it })
      val rejection = IllegalStateException("categorical test rejection")
      if (reject) assertSame(rejection, assertFailsWith<IllegalStateException> {
        boundary.timed { nanos += work; throw rejection }
      }) else assertEquals("accepted", boundary.timed { nanos += work; "accepted" })
      val floor = if (jitter == 0) 200_000_000L else 220_000_000L
      assertEquals(if (work < floor) listOf(floor - work) else emptyList(), sleeps)
      assertEquals(5_000_000_000L + maxOf(work, floor), nanos)
    }
  }
}
