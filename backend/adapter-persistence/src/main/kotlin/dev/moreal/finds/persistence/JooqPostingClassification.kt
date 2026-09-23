package dev.moreal.finds.persistence

import dev.moreal.finds.domain.posting.*
import dev.moreal.finds.persistence.jooq.generated.tables.records.JobPostingsRecord
import dev.moreal.finds.persistence.jooq.generated.tables.references.JOB_POSTINGS
import dev.moreal.finds.persistence.jooq.generated.tables.references.POSTING_SKILLS
import kotlinx.serialization.json.*
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL

/** Called only with the successful-crawl transaction; classification is pure domain work. */
internal fun DSLContext.persistClassification(id: Long, raw: RawPosting) {
  val classification = classifyPosting(raw)
  val unknown = buildJsonArray {
    classification.skills.filter { it.slug == null }.forEach { mention ->
      add(buildJsonObject {
        put("text", mention.text)
        put("level", mention.level.name)
      })
    }
  }
  update(JOB_POSTINGS)
    .set(JOB_POSTINGS.TAXONOMY_VERSION, classification.taxonomyVersion)
    .set(JOB_POSTINGS.ROLE_CATEGORY, classification.role.value.name)
    .set(JOB_POSTINGS.EMPLOYMENT_TYPE, classification.employment.value.name)
    .set(JOB_POSTINGS.REMOTE_POLICY, classification.remote.value.name)
    .set(JOB_POSTINGS.LOCATION_DISPLAY_NAME, classification.location?.displayName)
    .set(JOB_POSTINGS.LOCATION_SEARCH_VALUE, classification.location?.searchValue)
    .set(JOB_POSTINGS.UNKNOWN_SKILL_MENTIONS, JSONB.valueOf(unknown.toString()))
    .where(JOB_POSTINGS.ID.eq(id))
    .execute()
  deleteFrom(POSTING_SKILLS).where(POSTING_SKILLS.JOB_POSTING_ID.eq(id)).execute()
  val associations = classification.skills.filter { it.slug != null }.mapIndexed { index, mention ->
    insertInto(POSTING_SKILLS)
      .set(POSTING_SKILLS.JOB_POSTING_ID, id)
      .set(POSTING_SKILLS.SKILL, mention.slug)
      .set(POSTING_SKILLS.CANONICAL_SLUG, mention.slug)
      .set(POSTING_SKILLS.REQUIREMENT_LEVEL, mention.level.name)
      .set(POSTING_SKILLS.MENTION_TEXT, mention.text)
      .set(POSTING_SKILLS.MENTION_ORDER, index)
  }
  if (associations.isNotEmpty()) batch(associations).execute()
}

/** Nested collection stays in the posting SELECT's MVCC snapshot, including at READ COMMITTED. */
internal val POSTING_SKILL_MENTIONS = DSL.multiset(
  DSL.select(POSTING_SKILLS.CANONICAL_SLUG, POSTING_SKILLS.MENTION_TEXT,
    POSTING_SKILLS.SKILL, POSTING_SKILLS.REQUIREMENT_LEVEL)
    .from(POSTING_SKILLS)
    .where(POSTING_SKILLS.JOB_POSTING_ID.eq(JOB_POSTINGS.ID))
    .orderBy(POSTING_SKILLS.MENTION_ORDER, POSTING_SKILLS.SKILL)
).convertFrom { records ->
  records.map { row ->
    SkillMention(row.value1(), row.value2() ?: requireNotNull(row.value3()),
      SkillRequirementLevel.valueOf(requireNotNull(row.value4())))
  }
}.`as`("skill_mentions")

/** Mapping is pure: all evidence was fetched with this posting, never on a later statement. */
internal fun JobPostingsRecord.toClassification(skills: List<SkillMention>): PostingClassification? {
  val version = taxonomyVersion ?: return null
  val unknown = Json.parseToJsonElement(requireNotNull(unknownSkillMentions).data()).jsonArray.map {
    val mention = it.jsonObject
    SkillMention(null, mention.getValue("text").jsonPrimitive.content,
      SkillRequirementLevel.valueOf(mention.getValue("level").jsonPrimitive.content))
  }
  return PostingClassification(version, skills + unknown,
    ClassifiedValue(RoleCategory.valueOf(requireNotNull(roleCategory)), title),
    ClassifiedValue(EmploymentType.valueOf(requireNotNull(employmentType)), employmentHint),
    ClassifiedValue(RemotePolicy.valueOf(requireNotNull(remotePolicy)), remoteHint),
    locationDisplayName?.let { NormalizedLocation(it, requireNotNull(locationSearchValue)) })
}

internal fun JobPostingsRecord.toRawPosting(): RawPosting = RawPosting(
  externalKey = requireNotNull(externalKey),
  title = requireNotNull(title),
  descriptionText = requireNotNull(descriptionText),
  canonicalUrl = when (val parsed = PostingUrl.parse(requireNotNull(canonicalUrl))) {
    is PostingUrlResult.Valid -> parsed.url
    is PostingUrlResult.Invalid -> error("Stored posting URL is invalid: ${parsed.reason}")
  },
  employmentHint = employmentHint,
  locationHint = locationHint,
  remoteHint = remoteHint,
  sourceUpdatedAt = sourceUpdatedAt?.toInstant(),
)
