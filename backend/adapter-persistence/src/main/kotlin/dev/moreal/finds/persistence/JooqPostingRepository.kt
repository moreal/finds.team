package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.PageRequest
import dev.moreal.finds.application.model.SearchCursor
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.port.PostingRepository
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.posting.JobPostingId
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.posting.PostingClassification
import dev.moreal.finds.domain.posting.SkillMention
import dev.moreal.finds.domain.search.Filter
import dev.moreal.finds.domain.search.normalize
import dev.moreal.finds.persistence.jooq.generated.tables.records.JobPostingsRecord
import dev.moreal.finds.persistence.jooq.generated.tables.references.JOB_POSTINGS
import dev.moreal.finds.persistence.jooq.generated.tables.references.POSTING_SKILLS
import java.time.ZoneOffset
import java.util.Locale
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record2
import org.jooq.impl.DSL

class JooqPostingRepository(
  private val context: DSLContext,
) : PostingRepository {
  override fun findByCareerSite(id: CareerSiteId): List<JobPosting> {
    return context.select(JOB_POSTINGS, POSTING_SKILL_MENTIONS)
      .from(JOB_POSTINGS)
      .where(JOB_POSTINGS.CAREER_SITE_ID.eq(id.value))
      .orderBy(JOB_POSTINGS.ID.asc())
      .fetch()
      .map { it.toDomain() }
  }

  override fun search(filter: Filter, page: PageRequest): SearchPage {
    val condition = filter.normalize().toCondition()
    val cursorCondition = page.after?.let(::afterCondition) ?: DSL.trueCondition()
    // One outer row keeps the total available even for an empty page. Count, bounded page,
    // and nested associations all share one statement snapshot on the caller's connection.
    val result = context.select(
      DSL.field(DSL.select(DSL.count().cast(Long::class.java)).from(JOB_POSTINGS).where(condition)),
      DSL.multiset(DSL.select(JOB_POSTINGS, POSTING_SKILL_MENTIONS)
        .from(JOB_POSTINGS)
        .where(condition.and(cursorCondition))
        .orderBy(JOB_POSTINGS.UPDATED_AT.desc(), JOB_POSTINGS.ID.desc())
        .limit(page.size + 1)),
    ).fetchSingle()
    val records = result.value2()
    val selected = records.take(page.size).map { it.toDomain() }
    val next = if (records.size > page.size) {
      selected.lastOrNull()?.let { SearchCursor(it.updatedAt, it.id) }
    } else {
      null
    }
    return SearchPage(selected, next, result.value1())
  }

  private fun Record2<JobPostingsRecord, List<SkillMention>>.toDomain(): JobPosting {
    val posting = value1()
    return posting.toDomain(posting.toClassification(value2()))
  }

  private fun afterCondition(cursor: SearchCursor): Condition {
    val updatedAt = cursor.updatedAt.atOffset(ZoneOffset.UTC)
    return JOB_POSTINGS.UPDATED_AT.lt(updatedAt).or(
      JOB_POSTINGS.UPDATED_AT.eq(updatedAt).and(JOB_POSTINGS.ID.lt(cursor.id.value)),
    )
  }

  private fun JobPostingsRecord.toDomain(classification: PostingClassification?): JobPosting {
    return JobPosting(
      id = JobPostingId(requireNotNull(id)),
      careerSiteId = CareerSiteId(requireNotNull(careerSiteId)),
      raw = toRawPosting(),
      contentHash = requireNotNull(contentHash).trim(),
      status = PostingStatus.valueOf(requireNotNull(status)),
      consecutiveMisses = requireNotNull(consecutiveMisses),
      firstSeenAt = requireNotNull(firstSeenAt).toInstant(),
      lastSeenAt = requireNotNull(lastSeenAt).toInstant(),
      updatedAt = requireNotNull(updatedAt).toInstant(),
      closedAt = closedAt?.toInstant(),
      classification = classification,
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
  is Filter.HasSkill -> JOB_POSTINGS.TAXONOMY_VERSION.isNotNull.and(DSL.exists(
    DSL.selectOne().from(POSTING_SKILLS)
      .where(POSTING_SKILLS.JOB_POSTING_ID.eq(JOB_POSTINGS.ID))
      .and(POSTING_SKILLS.CANONICAL_SLUG.eq(slug))
      .and(level?.let { POSTING_SKILLS.REQUIREMENT_LEVEL.eq(it.name) } ?: DSL.trueCondition()),
  ))
  // SQL NULL must be false at every leaf so NOT has the domain's two-valued semantics.
  is Filter.HasRole -> JOB_POSTINGS.ROLE_CATEGORY.isNotNull.and(JOB_POSTINGS.ROLE_CATEGORY.eq(role.name))
  is Filter.HasEmployment -> JOB_POSTINGS.EMPLOYMENT_TYPE.isNotNull.and(JOB_POSTINGS.EMPLOYMENT_TYPE.eq(employment.name))
  is Filter.HasRemotePolicy -> JOB_POSTINGS.REMOTE_POLICY.isNotNull.and(JOB_POSTINGS.REMOTE_POLICY.eq(policy.name))
  is Filter.AtLocation -> JOB_POSTINGS.LOCATION_SEARCH_VALUE.isNotNull.and(JOB_POSTINGS.LOCATION_SEARCH_VALUE.eq(searchValue))
  is Filter.Not -> inner.toCondition().not()
  is Filter.And -> all.fold(DSL.trueCondition() as Condition) { result, child ->
    result.and(child.toCondition())
  }
  is Filter.Or -> any.fold(DSL.falseCondition() as Condition) { result, child ->
    result.or(child.toCondition())
  }
}
