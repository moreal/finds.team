package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.model.CrawlStatus
import dev.moreal.finds.application.port.CrawlRunRepository
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.crawl.CrawlHistory
import dev.moreal.finds.domain.crawl.CrawlOutcome
import dev.moreal.finds.persistence.jooq.generated.tables.records.CrawlRunsRecord
import dev.moreal.finds.persistence.jooq.generated.tables.references.CRAWL_RUNS
import java.time.Instant
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.impl.DSL.max
import org.jooq.impl.DSL.row

class JooqCrawlRunRepository(
  private val context: DSLContext,
) : CrawlRunRepository {
  override fun latestHistory(siteId: CareerSiteId): CrawlHistory? {
    val latest = context.selectFrom(CRAWL_RUNS)
      .where(CRAWL_RUNS.CAREER_SITE_ID.eq(siteId.value))
      .and(CRAWL_RUNS.OUTCOME.isNotNull)
      .orderBy(CRAWL_RUNS.FINISHED_AT.desc(), CRAWL_RUNS.ID.desc())
      .limit(1)
      .fetchOne() ?: return null
    val outcome = CrawlOutcome.valueOf(requireNotNull(latest.outcome))
    val failures = if (outcome == CrawlOutcome.FAILED) {
      val latestSuccess = context.select(CRAWL_RUNS.FINISHED_AT, CRAWL_RUNS.ID)
        .from(CRAWL_RUNS)
        .where(CRAWL_RUNS.CAREER_SITE_ID.eq(siteId.value))
        .and(CRAWL_RUNS.OUTCOME.eq(CrawlOutcome.SUCCESS.name))
        .orderBy(CRAWL_RUNS.FINISHED_AT.desc(), CRAWL_RUNS.ID.desc())
        .limit(1)
        .fetchOne()
      var condition: org.jooq.Condition = CRAWL_RUNS.CAREER_SITE_ID.eq(siteId.value)
        .and(CRAWL_RUNS.OUTCOME.eq(CrawlOutcome.FAILED.name))
      if (latestSuccess != null) {
        condition = condition.and(
          row(CRAWL_RUNS.FINISHED_AT, CRAWL_RUNS.ID).gt(
            row(
              requireNotNull(latestSuccess.value1()),
              requireNotNull(latestSuccess.value2()),
            ),
          ),
        )
      }
      context.fetchCount(CRAWL_RUNS, condition)
    } else {
      0
    }
    return CrawlHistory(
      outcome,
      requireNotNull(latest.finishedAt).toInstant(),
      failures,
    )
  }

  override fun start(siteId: CareerSiteId, startedAt: Instant): CrawlRunId {
    val record = requireNotNull(
      context.insertInto(CRAWL_RUNS)
        .set(CRAWL_RUNS.CAREER_SITE_ID, siteId.value)
        .set(CRAWL_RUNS.STARTED_AT, startedAt.atOffset(ZoneOffset.UTC))
        .returning(CRAWL_RUNS.ID)
        .fetchOne(),
    ) { "Crawl-run insert returned no row" }
    return CrawlRunId(requireNotNull(record.id))
  }

  override fun fail(runId: CrawlRunId, failure: CrawlFailure, finishedAt: Instant) {
    val updated = context.update(CRAWL_RUNS)
      .set(CRAWL_RUNS.FINISHED_AT, finishedAt.atOffset(ZoneOffset.UTC))
      .set(CRAWL_RUNS.OUTCOME, CrawlOutcome.FAILED.name)
      .set(CRAWL_RUNS.FAILURE_CODE, failure.code.name)
      .set(CRAWL_RUNS.FAILURE_MESSAGE, failure.message)
      .where(CRAWL_RUNS.ID.eq(runId.value))
      .and(CRAWL_RUNS.FINISHED_AT.isNull)
      .execute()
    check(updated == 1) { "Crawl run ${runId.value} was missing or already completed" }
  }

  override fun latestStatuses(): List<CrawlStatus> {
    val latestIds = context.select(max(CRAWL_RUNS.ID))
      .from(CRAWL_RUNS)
      .groupBy(CRAWL_RUNS.CAREER_SITE_ID)
    return context.selectFrom(CRAWL_RUNS)
      .where(CRAWL_RUNS.ID.`in`(latestIds))
      .orderBy(CRAWL_RUNS.CAREER_SITE_ID.asc(), CRAWL_RUNS.ID.desc())
      .fetch()
      .map { it.toStatus() }
  }

  private fun CrawlRunsRecord.toStatus(): CrawlStatus {
    val code = failureCode?.let(CrawlFailureCode::valueOf)
    val message = failureMessage
    return CrawlStatus(
      careerSiteId = CareerSiteId(requireNotNull(careerSiteId)),
      runId = CrawlRunId(requireNotNull(id)),
      outcome = outcome?.let(CrawlOutcome::valueOf),
      finishedAt = finishedAt?.toInstant(),
      failure = if (code != null && message != null) CrawlFailure(code, message) else null,
    )
  }
}
