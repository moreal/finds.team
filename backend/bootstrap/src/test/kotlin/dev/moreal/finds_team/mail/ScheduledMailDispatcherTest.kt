package dev.moreal.finds_team.mail

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ScheduledMailDispatcherTest {
  @Test
  fun `overlapping scans skip and failure permits another scan without logging payload`() = runTest {
    val release = CompletableDeferred<Unit>()
    val registry = SimpleMeterRegistry()
    var calls = 0
    val scheduler = ScheduledMailDispatcher({ calls++; release.await(); error("private@example.test OTP=01234567") }, this, registry)
    val first = assertNotNull(scheduler.scan())
    assertNull(scheduler.scan())
    release.complete(Unit)
    first.join()
    assertNotNull(scheduler.scan()).join()
    assertEquals(2, calls)
    assertEquals(2.0, registry.get("finds.mail.scans").tag("outcome", "failed").counter().count())
    assertFalse(registry.meters.toString().contains("01234567"))
  }
}
