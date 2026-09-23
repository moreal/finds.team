package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.*
import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.posting.*
import dev.moreal.finds.domain.search.Filter
import dev.moreal.finds.domain.search.normalize
import dev.moreal.finds.persistence.jooq.generated.tables.records.JobPostingsRecord
import dev.moreal.finds.persistence.jooq.generated.tables.references.CAREER_SITES
import dev.moreal.finds.persistence.jooq.generated.tables.references.JOB_POSTINGS
import dev.moreal.finds.persistence.jooq.generated.tables.references.POSTING_SKILLS
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.format.SignStyle
import java.time.temporal.ChronoField.YEAR_OF_ERA
import java.util.Locale
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

/** Each database page, its count, PageInfo and nested evidence use one statement snapshot. */
class JooqDiscoveryQuery(private val context: DSLContext) : DiscoveryQueryPort, DiscoveryBatchQueryPort {
  override fun findPostings(ids: List<JobPostingId>): List<JobPosting?> {
    if (ids.isEmpty()) return emptyList()
    val rows = context.select(DISCOVERY_POSTING, POSTING_SKILL_MENTIONS).from(JOB_POSTINGS)
      .where(JOB_POSTINGS.ID.`in`(ids.map { it.value })).fetch().map { it.toPosting() }.associateBy { it.id }
    return ids.map { rows[it] }
  }

  override fun findSites(ids: List<CareerSiteId>): List<CareerSite?> {
    if (ids.isEmpty()) return emptyList()
    val rows = context.selectFrom(CAREER_SITES).where(CAREER_SITES.ID.`in`(ids.map { it.value }))
      .fetch().map { it.toDomain() }.associateBy { it.id }
    return ids.map { rows[it] }
  }

  override fun postingPages(keys: List<PostingPageKey>) = batchPages(keys.map { key ->
    { postingField(key.filter, key.page) }
  })
  override fun companyPages(keys: List<SkillPageKey>) = batchPages(keys.map { key ->
    { companyField(key.slug, key.page) }
  })
  override fun relatedSkillPages(keys: List<SkillPageKey>) = batchPages(keys.map { key ->
    { relatedField(key.slug, key.page) }
  })
  override fun requirementCounts(slugs: List<String>): List<SkillRequirementCounts> {
    if (slugs.isEmpty()) return emptyList()
    slugs.forEach { SkillTaxonomy.V1.requireSkill(it) }
    fun count(level: SkillRequirementLevel) = DSL.count().filterWhere(POSTING_SKILLS.REQUIREMENT_LEVEL.eq(level.name)).cast(Long::class.java)
    val rows = context.select(POSTING_SKILLS.CANONICAL_SLUG, count(SkillRequirementLevel.REQUIRED),
      count(SkillRequirementLevel.PREFERRED), count(SkillRequirementLevel.MENTIONED))
      .from(POSTING_SKILLS).join(JOB_POSTINGS).on(JOB_POSTINGS.ID.eq(POSTING_SKILLS.JOB_POSTING_ID))
      .where(POSTING_SKILLS.CANONICAL_SLUG.`in`(slugs)).and(JOB_POSTINGS.STATUS.eq("OPEN"))
      .and(JOB_POSTINGS.TAXONOMY_VERSION.isNotNull).groupBy(POSTING_SKILLS.CANONICAL_SLUG)
      .fetch().associate { it.value1() to SkillRequirementCounts(it.value2(), it.value3(), it.value4()) }
    return slugs.map { rows[it] ?: SkillRequirementCounts(0, 0, 0) }
  }

  // The loop builds bounded SQL expressions; there is a single execute/fetch for the entire family.
  // SQL failures propagate. Only caller-correctable cursor/taxonomy failures become per-key results.
  private fun <T> batchPages(builders: List<() -> Field<ConnectionPage<T>>>): List<Result<ConnectionPage<T>>> {
    if (builders.isEmpty()) return emptyList()
    require(builders.size <= 100) { "Discovery batch exceeds 100 keys" }
    val prepared = builders.map { build ->
      try { Result.success(build()) }
      catch (error: InvalidConnectionCursor) { Result.failure(error) }
      catch (error: UnknownSkillException) { Result.failure(error) }
    }
    val fields = prepared.mapNotNull { it.getOrNull() }
    val record = if (fields.isEmpty()) null else context.select(fields).fetchSingle()
    return prepared.map { result -> result.map { field -> requireNotNull(record!![field]) } }
  }
  override fun findPosting(id: JobPostingId): JobPosting? = context
    .select(DISCOVERY_POSTING, POSTING_SKILL_MENTIONS).from(JOB_POSTINGS)
    .where(JOB_POSTINGS.ID.eq(id.value)).fetchOne()?.toPosting()

  override fun findSiteBySlug(slug: String): CareerSite? = context.selectFrom(CAREER_SITES)
    .where(CAREER_SITES.SLUG.eq(slug)).fetchOne()?.toDomain()

  override fun postings(filter: Filter, page: ConnectionRequest): ConnectionPage<JobPosting> =
    context.select(postingField(filter, page)).fetchSingle().value1()

  private fun postingField(filter: Filter, page: ConnectionRequest): Field<ConnectionPage<JobPosting>> {
    val normalized = filter.normalize()
    val scope = ConnectionCursors.scope("postings", "updated-desc", normalized.cursorKey())
    val position = page.after?.let { cursor ->
      val values = ConnectionCursors.decode(cursor, scope, 2)
      try {
        val time = Instant.parse(values[0])
        require(time.toString() == values[0])
        // JDBC rounds fractional microseconds and accepts a wider year range than PostgreSQL.
        // Reject both before executing SQL; never shift an untrusted keyset boundary.
        require(time >= MIN_POSTGRES_TIMESTAMP && time < END_POSTGRES_TIMESTAMP && time.nano % 1_000 == 0)
        time.atOffset(UTC) to positiveId(values[1])
      } catch (_: java.time.DateTimeException) { throw InvalidConnectionCursor() }
      catch (_: IllegalArgumentException) { throw InvalidConnectionCursor() }
    }
    val condition = normalized.toCondition()
    val after = position?.let { (time, id) ->
      JOB_POSTINGS.UPDATED_AT.lt(time).or(JOB_POSTINGS.UPDATED_AT.eq(time).and(JOB_POSTINGS.ID.lt(id)))
    } ?: DSL.trueCondition()
    val previous = if (position == null) DSL.falseCondition() else after.not()
    return DSL.multiset(DSL.select(
      DSL.field(DSL.select(DSL.count().cast(Long::class.java)).from(JOB_POSTINGS).where(condition)),
      DSL.field(DSL.exists(DSL.selectOne().from(JOB_POSTINGS).where(condition.and(previous)))),
      DSL.multiset(DSL.select(DISCOVERY_POSTING, POSTING_SKILL_MENTIONS).from(JOB_POSTINGS)
        .where(condition.and(after)).orderBy(JOB_POSTINGS.UPDATED_AT.desc(), JOB_POSTINGS.ID.desc())
        .limit(page.first + 1)),
    )).convertFrom { results ->
      val result = results.single()
      connection(result.value3().map { it.toPosting() }, page, result.value1(), result.value2(), scope) {
        listOf(it.updatedAt.toString(), it.id.value.toString())
      }
    }
  }

  override fun careerSites(page: ConnectionRequest): ConnectionPage<CareerSite> =
    context.select(siteField(DSL.trueCondition(), page, ConnectionCursors.scope("sites", "id-asc"))).fetchSingle().value1()

  override fun skillCompanies(slug: String, page: ConnectionRequest): ConnectionPage<CareerSite> =
    context.select(companyField(slug, page)).fetchSingle().value1()

  private fun companyField(slug: String, page: ConnectionRequest): Field<ConnectionPage<CareerSite>> {
    val filter = openSkill(slug)
    val condition = DSL.exists(DSL.selectOne().from(JOB_POSTINGS)
      .where(JOB_POSTINGS.CAREER_SITE_ID.eq(CAREER_SITES.ID)).and(filter))
    return siteField(condition, page, ConnectionCursors.scope("skill-companies", "id-asc", slug))
  }

  private fun siteField(condition: Condition, page: ConnectionRequest, scope: String): Field<ConnectionPage<CareerSite>> {
    val id = page.after?.let { positiveId(ConnectionCursors.decode(it, scope, 1).single()) }
    return DSL.multiset(DSL.select(
      DSL.field(DSL.select(DSL.count().cast(Long::class.java)).from(CAREER_SITES).where(condition)),
      DSL.field(DSL.exists(DSL.selectOne().from(CAREER_SITES).where(condition)
        .and(id?.let { CAREER_SITES.ID.le(it) } ?: DSL.falseCondition()))),
      DSL.multiset(DSL.select(CAREER_SITES).from(CAREER_SITES).where(condition)
        .and(id?.let { CAREER_SITES.ID.gt(it) } ?: DSL.trueCondition())
        .orderBy(CAREER_SITES.ID.asc()).limit(page.first + 1)),
    )).convertFrom { results ->
      val result = results.single()
      connection(result.value3().map { it.value1().toDomain() }, page, result.value1(), result.value2(), scope) {
        listOf(it.id.value.toString())
      }
    }
  }

  override fun skills(query: String?, page: ConnectionRequest): ConnectionPage<SkillDefinition> {
    val search = query.orEmpty().trim().lowercase(Locale.ROOT)
    val scope = ConnectionCursors.scope("skills", "slug-asc", search)
    val after = page.after?.let { skillPosition(it, scope) }
    val catalog = SkillTaxonomy.V1.skills.filter { skill ->
      (skill.aliases + skill.slug).any { it.lowercase(Locale.ROOT).contains(search) }
    }.sortedBy { it.slug }
    return connection(catalog.filter { after == null || it.slug > after }.take(page.first + 1),
      page, catalog.size.toLong(), after != null && catalog.any { it.slug <= after }, scope) { listOf(it.slug) }
  }

  override fun relatedSkills(slug: String, page: ConnectionRequest): ConnectionPage<SkillDefinition> =
    context.select(relatedField(slug, page)).fetchSingle().value1()

  private fun relatedField(slug: String, page: ConnectionRequest): Field<ConnectionPage<SkillDefinition>> {
    val filter = openSkill(slug)
    val scope = ConnectionCursors.scope("related-skills", "slug-asc", slug)
    val after = page.after?.let { skillPosition(it, scope) }
    val related = POSTING_SKILLS.`as`("related")
    // DISTINCT prevents one related skill becoming one edge per matching posting.
    val candidates = DSL.selectDistinct(related.CANONICAL_SLUG.`as`("slug"))
      .from(related).join(JOB_POSTINGS).on(related.JOB_POSTING_ID.eq(JOB_POSTINGS.ID))
      .where(filter).and(related.CANONICAL_SLUG.ne(slug))
      .and(related.CANONICAL_SLUG.`in`(SkillTaxonomy.V1.skills.map { it.slug })).asTable("candidates")
    val key = candidates.field("slug", String::class.java)!!
    return DSL.multiset(DSL.select(
      DSL.field(DSL.select(DSL.count().cast(Long::class.java)).from(candidates)),
      DSL.field(DSL.exists(DSL.selectOne().from(candidates)
        .where(after?.let { key.le(it) } ?: DSL.falseCondition()))),
      DSL.multiset(DSL.select(key).from(candidates)
        .where(after?.let { key.gt(it) } ?: DSL.trueCondition())
        .orderBy(key.asc()).limit(page.first + 1)),
    )).convertFrom { results ->
      val result = results.single()
      connection(result.value3().map { SkillTaxonomy.V1.requireSkill(it.value1()) },
        page, result.value1(), result.value2(), scope) { listOf(it.slug) }
    }
  }

  override fun skillRequirementCounts(slug: String): SkillRequirementCounts {
    SkillTaxonomy.V1.requireSkill(slug)
    fun count(level: SkillRequirementLevel) = DSL.count().filterWhere(POSTING_SKILLS.REQUIREMENT_LEVEL.eq(level.name))
      .cast(Long::class.java)
    val result = context.select(count(SkillRequirementLevel.REQUIRED), count(SkillRequirementLevel.PREFERRED),
      count(SkillRequirementLevel.MENTIONED)).from(POSTING_SKILLS).join(JOB_POSTINGS)
      .on(JOB_POSTINGS.ID.eq(POSTING_SKILLS.JOB_POSTING_ID))
      .where(POSTING_SKILLS.CANONICAL_SLUG.eq(slug)).and(JOB_POSTINGS.STATUS.eq("OPEN"))
      .and(JOB_POSTINGS.TAXONOMY_VERSION.isNotNull).fetchSingle()
    return SkillRequirementCounts(result.value1(), result.value2(), result.value3())
  }

  private fun openSkill(slug: String): Condition =
    Filter.And(listOf(Filter.HasSkill(slug), Filter.HasStatus(PostingStatus.OPEN))).toCondition()

  private fun skillPosition(cursor: ApplicationCursor, scope: String): String {
    val slug = ConnectionCursors.decode(cursor, scope, 1).single()
    if (SkillTaxonomy.V1.skills.none { it.slug == slug }) throw InvalidConnectionCursor()
    return slug
  }

  private fun positiveId(value: String): Long {
    val id = value.toLongOrNull() ?: throw InvalidConnectionCursor()
    if (id <= 0 || id.toString() != value) throw InvalidConnectionCursor()
    return id
  }

  private fun <T> connection(rows: List<T>, page: ConnectionRequest, total: Long, previous: Boolean,
    scope: String, position: (T) -> List<String>): ConnectionPage<T> {
    val edges = rows.take(page.first).map { ConnectionEdge(it, ConnectionCursors.encode(scope, position(it))) }
    return ConnectionPage(edges, ConnectionPageInfo(rows.size > page.first, previous,
      edges.firstOrNull()?.cursor, edges.lastOrNull()?.cursor), total)
  }
}

// PostgreSQL's exact finite timestamp bounds (ISO/proleptic Gregorian), at microsecond resolution.
// https://github.com/postgres/postgres/blob/REL_17_STABLE/src/include/datatype/timestamp.h
private val MIN_POSTGRES_TIMESTAMP = Instant.parse("-4713-11-24T00:00:00Z")
private val END_POSTGRES_TIMESTAMP = Instant.parse("+294277-01-01T00:00:00Z")

private val POSTGRES_CALENDAR_FORMAT = DateTimeFormatterBuilder()
  .appendValue(YEAR_OF_ERA, 4, 6, SignStyle.NOT_NEGATIVE)
  .appendPattern("-MM-dd'T'HH:mm:ss.SSSSSS G")
  .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)

// MULTISET uses JSON. jOOQ's ISO parser can turn PostgreSQL's extended-year / BC text
// into null. Format UTC calendar fields and era explicitly, retaining all six fractional
// digits; epoch extraction can itself round at PostgreSQL's maximum timestamp.
private val DISCOVERY_POSTING = DSL.row(*JOB_POSTINGS.fields().map { field ->
  if (field.type == OffsetDateTime::class.java) {
    DSL.field("to_char({0} at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US AD')", String::class.java, field)
      .convertFrom(OffsetDateTime::class.java) { text ->
        text?.let { LocalDateTime.parse(it, POSTGRES_CALENDAR_FORMAT).atOffset(UTC) }
      }
  } else field
}.toTypedArray()).convertFrom { record -> JobPostingsRecord().apply { fromArray(*record.intoArray()) } }

/** Length-prefixed structural hashing avoids ambiguous user text/delimiters in filter bindings. */
private fun Filter.cursorKey(): String = when (this) {
  is Filter.AtSite -> ConnectionCursors.scope("site", siteId.value.toString())
  is Filter.TextContains -> ConnectionCursors.scope("text", text.trim().lowercase(Locale.ROOT))
  is Filter.HasStatus -> ConnectionCursors.scope("status", status.name)
  is Filter.UpdatedAfter -> ConnectionCursors.scope("updated-after", instant.toString())
  is Filter.HasSkill -> ConnectionCursors.scope("skill", slug, level?.name.orEmpty())
  is Filter.HasRole -> ConnectionCursors.scope("role", role.name)
  is Filter.HasEmployment -> ConnectionCursors.scope("employment", employment.name)
  is Filter.HasRemotePolicy -> ConnectionCursors.scope("remote", policy.name)
  is Filter.AtLocation -> ConnectionCursors.scope("location", searchValue)
  is Filter.Not -> ConnectionCursors.scope("not", inner.cursorKey())
  is Filter.And -> ConnectionCursors.scope("and", *all.map { it.cursorKey() }.sorted().toTypedArray())
  is Filter.Or -> ConnectionCursors.scope("or", *any.map { it.cursorKey() }.sorted().toTypedArray())
}
