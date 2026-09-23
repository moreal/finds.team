package dev.moreal.finds_team.crawl

import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.usecase.CrawlAllDue
import dev.moreal.finds.application.usecase.CrawlSite
import dev.moreal.finds.application.usecase.CrawlSiteCommand
import dev.moreal.finds.application.usecase.CrawlSiteResult
import dev.moreal.finds.application.usecase.CrawlTrigger
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds_team.config.FindsProperties
import dev.moreal.finds_team.runtime.ManagedCoroutineScope
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant
import java.time.Duration
import java.util.UUID
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.security.Actor
import java.util.concurrent.atomic.AtomicBoolean

class ScheduledCrawlDispatcher internal constructor(
  private val selectDue: (Int) -> List<CareerSiteId>,
  private val crawl: suspend (CrawlSiteCommand) -> CrawlSiteResult,
  private val clock: ClockPort,
  private val scope: ManagedCoroutineScope,
  private val dispatchLimit: Int,
  globalConcurrency: Int,
  private val registry: MeterRegistry,
  private val scanInterval: Duration = Duration.ofMinutes(15),
) : HealthIndicator {
  constructor(
    selectDue: CrawlAllDue,
    crawl: CrawlSite,
    clock: ClockPort,
    scope: ManagedCoroutineScope,
    properties: FindsProperties,
    registry: MeterRegistry,
  ) : this(
    selectDue::execute,
    crawl::execute,
    clock,
    scope,
    properties.crawl.dispatchLimit,
    properties.crawl.globalConcurrency,
    registry,
    properties.crawl.scanInterval,
  )

  private val scanning = AtomicBoolean(false)
  private val permits = Semaphore(globalConcurrency)

  @Volatile private var lastStartedAt: Instant? = null
  @Volatile private var lastCompletedAt: Instant? = null
  @Volatile private var lastSelectionFailure: String? = null

  init {
    require(dispatchLimit in 1..1_000) { "Dispatch limit must be between 1 and 1000" }
    require(globalConcurrency >= 1) { "Global concurrency must be positive" }
    require(scanInterval.toMillis() > 0) { "Scan interval must be at least one millisecond" }
  }

  @Scheduled(
    initialDelayString = "\${finds.crawl.scan-interval}",
    fixedDelayString = "\${finds.crawl.scan-interval}",
  )
  fun scheduledScan() {
    dispatch()
  }

  internal fun dispatch(): Job? {
    if (!scanning.compareAndSet(false, true)) {
      registry.counter(METRIC_SCANS, "outcome", "overlap_skipped").increment()
      return null
    }
    lastStartedAt = clock.now()
    val window = Math.floorDiv(checkNotNull(lastStartedAt).toEpochMilli(), scanInterval.toMillis())
    return scope.launch(CoroutineName("scheduled-crawl-scan")) {
      try {
        val due = selectDue(dispatchLimit)
        lastSelectionFailure = null
        registry.counter(METRIC_SCANS, "outcome", "selected").increment()
        registry.summary(METRIC_DUE_SITES).record(due.size.toDouble())
        supervisorScope {
          due.map { siteId ->
            launch(CoroutineName("crawl-${siteId.value}")) {
              permits.withPermit { runSite(siteId, window) }
            }
          }.joinAll()
        }
      } catch (cancelled: CancellationException) {
        registry.counter(METRIC_SCANS, "outcome", "cancelled").increment()
        throw cancelled
      } catch (error: Exception) {
        lastSelectionFailure = error.safeMessage()
        registry.counter(METRIC_SCANS, "outcome", "selection_failure").increment()
        logger.atError()
          .setCause(error)
          .log("Scheduled crawl selection failed")
      } finally {
        lastCompletedAt = clock.now()
        scanning.set(false)
      }
    }
  }

  private suspend fun runSite(siteId: CareerSiteId, window: Long) {
    val result = try {
      val key = UUID.nameUUIDFromBytes("crawl_scheduler:${siteId.value}:${scanInterval.toMillis()}:$window".toByteArray(Charsets.UTF_8))
      crawl(CrawlSiteCommand(siteId, CrawlTrigger.SCHEDULED, Actor.System,
        CommandMetadata(UUID.randomUUID(), UUID.randomUUID(), key)))
    } catch (cancelled: CancellationException) {
      registry.counter(METRIC_RUNS, "outcome", "cancelled").increment()
      throw cancelled
    } catch (error: Exception) {
      registry.counter(METRIC_RUNS, "outcome", "uncaught_failure").increment()
      logger.atError()
        .addKeyValue("careerSiteId", siteId.value)
        .setCause(error)
        .log("Scheduled crawl failed unexpectedly")
      return
    }
    val outcome = result.metricOutcome()
    registry.counter(METRIC_RUNS, "outcome", outcome).increment()
    logger.atInfo()
      .addKeyValue("careerSiteId", siteId.value)
      .addKeyValue("outcome", outcome)
      .log("Scheduled crawl finished")
  }

  override fun health(): Health {
    val builder = if (lastSelectionFailure == null) Health.up() else Health.down()
    return builder
      .withDetail("scanning", scanning.get())
      .withDetail("lastStartedAt", lastStartedAt?.toString() ?: "never")
      .withDetail("lastCompletedAt", lastCompletedAt?.toString() ?: "never")
      .apply { lastSelectionFailure?.let { withDetail("lastSelectionFailure", it) } }
      .build()
  }

  private fun CrawlSiteResult.metricOutcome(): String = when (this) {
    is CrawlSiteResult.Succeeded -> "succeeded"
    is CrawlSiteResult.Failed -> "failed"
    CrawlSiteResult.NotFound -> "not_found"
    is CrawlSiteResult.NotDue -> "not_due"
    CrawlSiteResult.Disabled -> "disabled"
    CrawlSiteResult.Busy -> "busy"
    is CrawlSiteResult.InfrastructureFailure -> "infrastructure_failure"
    is CrawlSiteResult.Triggered -> "replayed"
    CrawlSiteResult.Forbidden -> "forbidden"
    CrawlSiteResult.InvalidIdempotencyKey -> "invalid_key"
    CrawlSiteResult.IdempotencyConflict -> "idempotency_conflict"
  }

  private fun Throwable.safeMessage(): String =
    (message ?: this::class.simpleName ?: "Scheduled crawl selection failed")
      .replace(Regex("[\\r\\n]+"), " ")
      .trim()
      .take(1_000)

  private companion object {
    val logger = LoggerFactory.getLogger(ScheduledCrawlDispatcher::class.java)
    const val METRIC_SCANS = "finds.crawl.scans"
    const val METRIC_DUE_SITES = "finds.crawl.due.sites"
    const val METRIC_RUNS = "finds.crawl.runs"
  }
}
