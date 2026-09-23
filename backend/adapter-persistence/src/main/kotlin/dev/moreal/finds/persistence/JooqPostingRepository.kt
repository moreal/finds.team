package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.PageRequest
import dev.moreal.finds.application.model.SearchCursor
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.port.PostingRepository
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.posting.JobPostingId
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.posting.PostingUrl
import dev.moreal.finds.domain.posting.PostingUrlResult
import dev.moreal.finds.domain.posting.RawPosting
import dev.moreal.finds.domain.search.Filter
import dev.moreal.finds.domain.search.normalize
import dev.moreal.finds.persistence.jooq.generated.tables.records.JobPostingsRecord
import dev.moreal.finds.persistence.jooq.generated.tables.references.JOB_POSTINGS
import java.time.ZoneOffset
import java.util.Locale
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

class JooqPostingRepository(
  private val context: DSLContext,
) : PostingRepository {
  override fun findByCareerSite(id: CareerSiteId): List<JobPosting> =
    context.selectFrom(JOB_POSTINGS)
      .where(JOB_POSTINGS.CAREER_SITE_ID.eq(id.value))
      .orderBy(JOB_POSTINGS.ID.asc())
      .fetch()
      .map { it.toDomain() }

  override fun search(filter: Filter, page: PageRequest): SearchPage {
    val condition = filter.normalize().toCondition()
    val totalCount = context.selectCount()
      .from(JOB_POSTINGS)
      .where(condition)
      .fetchOne(0, Long::class.java) ?: 0L
    val cursorCondition = page.after?.let(::afterCondition) ?: DSL.trueCondition()
    val records = context.selectFrom(JOB_POSTINGS)
      .where(condition.and(cursorCondition))
      .orderBy(JOB_POSTINGS.UPDATED_AT.desc(), JOB_POSTINGS.ID.desc())
      .limit(page.size + 1)
      .fetch()
    val selected = records.take(page.size).map { it.toDomain() }
    val next = if (records.size > page.size) {
      selected.lastOrNull()?.let { SearchCursor(it.updatedAt, it.id) }
    } else {
      null
    }
    return SearchPage(selected, next, totalCount)
  }

  private fun afterCondition(cursor: SearchCursor): Condition {
    val updatedAt = cursor.updatedAt.atOffset(ZoneOffset.UTC)
    return JOB_POSTINGS.UPDATED_AT.lt(updatedAt).or(
      JOB_POSTINGS.UPDATED_AT.eq(updatedAt).and(JOB_POSTINGS.ID.lt(cursor.id.value)),
    )
  }

  private fun JobPostingsRecord.toDomain(): JobPosting {
    val postingUrl = when (val parsed = PostingUrl.parse(requireNotNull(canonicalUrl))) {
      is PostingUrlResult.Valid -> parsed.url
      is PostingUrlResult.Invalid -> error("Stored posting URL is invalid: ${parsed.reason}")
    }
    return JobPosting(
      id = JobPostingId(requireNotNull(id)),
      careerSiteId = CareerSiteId(requireNotNull(careerSiteId)),
      raw = RawPosting(
        externalKey = requireNotNull(externalKey),
        title = requireNotNull(title),
        descriptionText = requireNotNull(descriptionText),
        canonicalUrl = postingUrl,
        employmentHint = employmentHint,
        locationHint = locationHint,
        remoteHint = remoteHint,
        sourceUpdatedAt = sourceUpdatedAt?.toInstant(),
      ),
      contentHash = requireNotNull(contentHash).trim(),
      status = PostingStatus.valueOf(requireNotNull(status)),
      consecutiveMisses = requireNotNull(consecutiveMisses),
      firstSeenAt = requireNotNull(firstSeenAt).toInstant(),
      lastSeenAt = requireNotNull(lastSeenAt).toInstant(),
      updatedAt = requireNotNull(updatedAt).toInstant(),
      closedAt = closedAt?.toInstant(),
    )
  }
}

internal fun Filter.toCondition(): Condition = when (this) {
  is Filter.AtSite -> JOB_POSTINGS.CAREER_SITE_ID.eq(siteId.value)
  is Filter.TextContains -> {
    val query = text.trim().lowercase(Locale.ROOT)
    DSL.lower(JOB_POSTINGS.TITLE).contains(query)
      .or(DSL.lower(JOB_POSTINGS.DESCRIPTION_TEXT).contains(query))
  }
  is Filter.HasStatus -> JOB_POSTINGS.STATUS.eq(status.name)
  is Filter.UpdatedAfter -> JOB_POSTINGS.UPDATED_AT.gt(instant.atOffset(ZoneOffset.UTC))
  is Filter.HasSkill, is Filter.HasRole, is Filter.HasEmployment,
  is Filter.HasRemotePolicy, is Filter.AtLocation -> throw UnsupportedOperationException(
    "Enrichment filters require persisted classification support",
  )
  is Filter.Not -> inner.toCondition().not()
  is Filter.And -> all.fold(DSL.trueCondition() as Condition) { result, child ->
    result.and(child.toCondition())
  }
  is Filter.Or -> any.fold(DSL.falseCondition() as Condition) { result, child ->
    result.or(child.toCondition())
  }
}
