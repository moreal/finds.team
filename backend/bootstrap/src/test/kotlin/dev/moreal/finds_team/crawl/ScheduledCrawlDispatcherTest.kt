package dev.moreal.finds_team.crawl

import dev.moreal.finds.application.model.CrawlChangeCounts
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.usecase.CrawlSiteCommand
import dev.moreal.finds.application.usecase.CrawlSiteResult
import dev.moreal.finds.application.usecase.CrawlTrigger
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds_team.runtime.ManagedCoroutineScope
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduledCrawlDispatcherTest {
  private val scope = ManagedCoroutineScope()
  private val registry = SimpleMeterRegistry()

  @AfterEach
  fun tearDown() {
    scope.close()
    registry.close()
  }

  @Test
  fun `dispatch selects due sites with a bound and uses scheduled trigger`() = runBlocking {
    val commands = mutableListOf<CrawlSiteCommand>()
    var selectedLimit = 0
    val dispatcher = dispatcher(
      select = { limit ->
        selectedLimit = limit
        listOf(CareerSiteId(1), CareerSiteId(2))
      },
      crawl = { command ->
        synchronized(commands) { commands += command }
        success(command.siteId)
      },
      limit = 12,
    )

    assertNotNull(dispatcher.dispatch()).join()

    assertEquals(12, selectedLimit)
    assertEquals(setOf(CareerSiteId(1), CareerSiteId(2)), commands.map { it.siteId }.toSet())
    assertTrue(commands.all { it.trigger == CrawlTrigger.SCHEDULED })
    assertEquals(2.0, registry.counter("finds.crawl.runs", "outcome", "succeeded").count())
  }

  @Test
  fun `dispatch respects global concurrency and skips overlapping scans`() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val active = AtomicInteger()
    val maximum = AtomicInteger()
    val dispatcher = dispatcher(
      select = { listOf(CareerSiteId(1), CareerSiteId(2), CareerSiteId(3)) },
      crawl = { command ->
        val now = active.incrementAndGet()
        maximum.accumulateAndGet(now, ::maxOf)
        try {
          gate.await()
          success(command.siteId)
        } finally {
          active.decrementAndGet()
        }
      },
      concurrency = 2,
    )

    val first = assertNotNull(dispatcher.dispatch())
    while (active.get() < 2) Thread.yield()
    assertNull(dispatcher.dispatch())
    gate.complete(Unit)
    first.join()

    assertEquals(2, maximum.get())
    assertEquals(1.0, registry.counter("finds.crawl.scans", "outcome", "overlap_skipped").count())
  }

  @Test
  fun `site failure is isolated and selection failure changes health`() = runBlocking {
    val completed = mutableListOf<CareerSiteId>()
    var selectionFails = false
    val dispatcher = dispatcher(
      select = {
        if (selectionFails) error("database unavailable")
        listOf(CareerSiteId(1), CareerSiteId(2))
      },
      crawl = { command ->
        if (command.siteId == CareerSiteId(1)) error("unexpected")
        synchronized(completed) { completed += command.siteId }
        success(command.siteId)
      },
    )

    assertNotNull(dispatcher.dispatch()).join()
    assertEquals(listOf(CareerSiteId(2)), completed)
    assertEquals("UP", dispatcher.health().status.code)

    selectionFails = true
    assertNotNull(dispatcher.dispatch()).join()
    assertEquals("DOWN", dispatcher.health().status.code)
    assertFalse(dispatcher.health().details["scanning"] as Boolean)
  }

  @Test
  fun `closing application scope cancels an in-flight crawl`() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val dispatcher = dispatcher(
      select = { listOf(CareerSiteId(1)) },
      crawl = {
        started.complete(Unit)
        try {
          awaitCancellation()
        } finally {
          cancelled.complete(Unit)
        }
      },
    )

    val job = assertNotNull(dispatcher.dispatch())
    started.await()
    scope.close()
    job.join()

    assertTrue(cancelled.isCompleted)
  }

  private fun dispatcher(
    select: (Int) -> List<CareerSiteId>,
    crawl: suspend (CrawlSiteCommand) -> CrawlSiteResult,
    limit: Int = 100,
    concurrency: Int = 4,
  ) = ScheduledCrawlDispatcher(
    select,
    crawl,
    ClockPort { Instant.parse("2026-09-22T00:00:00Z") },
    scope,
    limit,
    concurrency,
    registry,
  )

  private fun success(siteId: CareerSiteId) = CrawlSiteResult.Succeeded(
    CrawlRunId(siteId.value),
    CrawlChangeCounts(1, 1, 0, 0, 0, 0, 0),
  )
}
