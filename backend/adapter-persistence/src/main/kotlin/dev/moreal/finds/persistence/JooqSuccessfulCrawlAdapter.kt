package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.CrawlChangeCounts
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.port.SuccessfulCrawlPort
import dev.moreal.finds.domain.crawl.SyncPlan
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.persistence.jooq.generated.tables.references.CRAWL_RUNS
import dev.moreal.finds.persistence.jooq.generated.tables.references.JOB_POSTINGS
import java.time.Instant
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.impl.DSL

class JooqSuccessfulCrawlAdapter(
  private val context: DSLContext,
) : SuccessfulCrawlPort {
  override fun applyAndComplete(
    runId: CrawlRunId,
    plan: SyncPlan,
    fetched: Int,
    finishedAt: Instant,
  ): CrawlChangeCounts = context.transactionResult { configuration ->
    val transaction = DSL.using(configuration)
    val run = transaction.selectFrom(CRAWL_RUNS)
      .where(CRAWL_RUNS.ID.eq(runId.value))
      .forUpdate()
      .fetchOne() ?: error("Crawl run ${runId.value} does not exist")
    if (run.outcome == "SUCCESS") {
      return@transactionResult run.toCounts()
    }
    check(run.outcome == null && run.finishedAt == null) {
      "Crawl run ${runId.value} is already completed as ${run.outcome}"
    }
    val siteId = requireNotNull(run.careerSiteId)

    plan.insert.forEach { insertion ->
      val raw = insertion.raw
      transaction.insertInto(JOB_POSTINGS)
        .set(JOB_POSTINGS.CAREER_SITE_ID, siteId)
        .set(JOB_POSTINGS.EXTERNAL_KEY, raw.externalKey)
        .set(JOB_POSTINGS.TITLE, raw.title)
        .set(JOB_POSTINGS.DESCRIPTION_TEXT, raw.descriptionText)
        .set(JOB_POSTINGS.CANONICAL_URL, raw.canonicalUrl.value.toString())
        .set(JOB_POSTINGS.EMPLOYMENT_HINT, raw.employmentHint)
        .set(JOB_POSTINGS.LOCATION_HINT, raw.locationHint)
        .set(JOB_POSTINGS.REMOTE_HINT, raw.remoteHint)
        .set(JOB_POSTINGS.SOURCE_UPDATED_AT, raw.sourceUpdatedAt?.utc())
        .set(JOB_POSTINGS.CONTENT_HASH, insertion.contentHash)
        .set(JOB_POSTINGS.STATUS, PostingStatus.OPEN.name)
        .set(JOB_POSTINGS.CONSECUTIVE_MISSES, 0)
        .set(JOB_POSTINGS.FIRST_SEEN_AT, insertion.observedAt.utc())
        .set(JOB_POSTINGS.LAST_SEEN_AT, insertion.observedAt.utc())
        .set(JOB_POSTINGS.UPDATED_AT, insertion.observedAt.utc())
        .execute()
    }
    plan.update.forEach { update ->
      val changed = transaction.update(JOB_POSTINGS)
        .set(JOB_POSTINGS.TITLE, update.raw.title)
        .set(JOB_POSTINGS.DESCRIPTION_TEXT, update.raw.descriptionText)
        .set(JOB_POSTINGS.CANONICAL_URL, update.raw.canonicalUrl.value.toString())
        .set(JOB_POSTINGS.EMPLOYMENT_HINT, update.raw.employmentHint)
        .set(JOB_POSTINGS.LOCATION_HINT, update.raw.locationHint)
        .set(JOB_POSTINGS.REMOTE_HINT, update.raw.remoteHint)
        .set(JOB_POSTINGS.SOURCE_UPDATED_AT, update.raw.sourceUpdatedAt?.utc())
        .set(JOB_POSTINGS.CONTENT_HASH, update.contentHash)
        .set(JOB_POSTINGS.LAST_SEEN_AT, update.observedAt.utc())
        .set(JOB_POSTINGS.UPDATED_AT, update.observedAt.utc())
        .set(JOB_POSTINGS.CONSECUTIVE_MISSES, update.consecutiveMisses)
        .whereOwned(siteId, update.ref.id.value, update.ref.externalKey)
        .and(JOB_POSTINGS.STATUS.eq(PostingStatus.OPEN.name))
        .execute()
      requireOne(changed, "update", update.ref.externalKey)
    }
    plan.touch.forEach { touch ->
      val changed = transaction.update(JOB_POSTINGS)
        .set(JOB_POSTINGS.LAST_SEEN_AT, touch.observedAt.utc())
        .set(JOB_POSTINGS.CONSECUTIVE_MISSES, touch.consecutiveMisses)
        .whereOwned(siteId, touch.ref.id.value, touch.ref.externalKey)
        .and(JOB_POSTINGS.STATUS.eq(PostingStatus.OPEN.name))
        .execute()
      requireOne(changed, "touch", touch.ref.externalKey)
    }
    plan.markMissing.forEach { miss ->
      val changed = transaction.update(JOB_POSTINGS)
        .set(JOB_POSTINGS.CONSECUTIVE_MISSES, miss.consecutiveMisses)
        .whereOwned(siteId, miss.ref.id.value, miss.ref.externalKey)
        .and(JOB_POSTINGS.STATUS.eq(PostingStatus.OPEN.name))
        .execute()
      requireOne(changed, "mark missing", miss.ref.externalKey)
    }
    plan.close.forEach { closure ->
      val changed = transaction.update(JOB_POSTINGS)
        .set(JOB_POSTINGS.STATUS, PostingStatus.CLOSED.name)
        .set(JOB_POSTINGS.CLOSED_AT, closure.closedAt.utc())
        .set(JOB_POSTINGS.CONSECUTIVE_MISSES, closure.consecutiveMisses)
        .whereOwned(siteId, closure.ref.id.value, closure.ref.externalKey)
        .and(JOB_POSTINGS.STATUS.eq(PostingStatus.OPEN.name))
        .execute()
      requireOne(changed, "close", closure.ref.externalKey)
    }
    plan.reopen.forEach { reopen ->
      val changed = transaction.update(JOB_POSTINGS)
        .set(JOB_POSTINGS.TITLE, reopen.raw.title)
        .set(JOB_POSTINGS.DESCRIPTION_TEXT, reopen.raw.descriptionText)
        .set(JOB_POSTINGS.CANONICAL_URL, reopen.raw.canonicalUrl.value.toString())
        .set(JOB_POSTINGS.EMPLOYMENT_HINT, reopen.raw.employmentHint)
        .set(JOB_POSTINGS.LOCATION_HINT, reopen.raw.locationHint)
        .set(JOB_POSTINGS.REMOTE_HINT, reopen.raw.remoteHint)
        .set(JOB_POSTINGS.SOURCE_UPDATED_AT, reopen.raw.sourceUpdatedAt?.utc())
        .set(JOB_POSTINGS.CONTENT_HASH, reopen.contentHash)
        .set(JOB_POSTINGS.STATUS, PostingStatus.OPEN.name)
        .set(JOB_POSTINGS.CLOSED_AT, null as java.time.OffsetDateTime?)
        .set(JOB_POSTINGS.LAST_SEEN_AT, reopen.observedAt.utc())
        .set(JOB_POSTINGS.UPDATED_AT, reopen.observedAt.utc())
        .set(JOB_POSTINGS.CONSECUTIVE_MISSES, reopen.consecutiveMisses)
        .whereOwned(siteId, reopen.ref.id.value, reopen.ref.externalKey)
        .and(JOB_POSTINGS.STATUS.eq(PostingStatus.CLOSED.name))
        .execute()
      requireOne(changed, "reopen", reopen.ref.externalKey)
    }

    val counts = CrawlChangeCounts(
      fetched = fetched,
      inserted = plan.insert.size,
      updated = plan.update.size,
      touched = plan.touch.size,
      missing = plan.markMissing.size,
      closed = plan.close.size,
      reopened = plan.reopen.size,
    )
    val completed = transaction.update(CRAWL_RUNS)
      .set(CRAWL_RUNS.FINISHED_AT, finishedAt.utc())
      .set(CRAWL_RUNS.OUTCOME, "SUCCESS")
      .set(CRAWL_RUNS.FETCHED_COUNT, counts.fetched)
      .set(CRAWL_RUNS.INSERTED_COUNT, counts.inserted)
      .set(CRAWL_RUNS.UPDATED_COUNT, counts.updated)
      .set(CRAWL_RUNS.TOUCHED_COUNT, counts.touched)
      .set(CRAWL_RUNS.MISSING_COUNT, counts.missing)
      .set(CRAWL_RUNS.CLOSED_COUNT, counts.closed)
      .set(CRAWL_RUNS.REOPENED_COUNT, counts.reopened)
      .where(CRAWL_RUNS.ID.eq(runId.value))
      .and(CRAWL_RUNS.FINISHED_AT.isNull)
      .execute()
    check(completed == 1) { "Crawl run ${runId.value} changed during completion" }
    counts
  }

  private fun org.jooq.UpdateSetMoreStep<dev.moreal.finds.persistence.jooq.generated.tables.records.JobPostingsRecord>.whereOwned(
    siteId: Long,
    postingId: Long,
    externalKey: String,
  ) = where(JOB_POSTINGS.ID.eq(postingId))
    .and(JOB_POSTINGS.CAREER_SITE_ID.eq(siteId))
    .and(JOB_POSTINGS.EXTERNAL_KEY.eq(externalKey))

  private fun dev.moreal.finds.persistence.jooq.generated.tables.records.CrawlRunsRecord.toCounts() =
    CrawlChangeCounts(
      requireNotNull(fetchedCount),
      requireNotNull(insertedCount),
      requireNotNull(updatedCount),
      requireNotNull(touchedCount),
      requireNotNull(missingCount),
      requireNotNull(closedCount),
      requireNotNull(reopenedCount),
    )

  private fun Instant.utc() = atOffset(ZoneOffset.UTC)

  private fun requireOne(changed: Int, operation: String, key: String) {
    check(changed == 1) { "Could not $operation posting $key with expected ownership/state" }
  }
}
