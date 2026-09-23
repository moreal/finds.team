package dev.moreal.finds.persistence

import dev.moreal.finds.application.audit.*
import dev.moreal.finds.application.model.*
import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.crawl.CrawlOutcome
import dev.moreal.finds.persistence.jooq.generated.tables.references.*
import dev.moreal.finds.persistence.jooq.generated.tables.records.*
import kotlinx.serialization.json.*
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.SelectField
import org.jooq.SortField
import org.jooq.Table
import org.jooq.impl.DSL
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.UUID

/** Counts, keyset page, and previous-page existence share one statement snapshot per batch. */
class JooqOperationsQuery(private val context: DSLContext) : OperationsQueryPort {
  override fun crawlSummaries(ids: List<CareerSiteId>): List<CrawlSummary?> {
    if (ids.isEmpty()) return emptyList()
    val r = CRAWL_RUNS
    val rows = context.select(r.CAREER_SITE_ID, r.OUTCOME, r.FINISHED_AT).distinctOn(r.CAREER_SITE_ID)
      .from(r).where(r.CAREER_SITE_ID.`in`(ids.map { it.value })).and(r.FINISHED_AT.isNotNull)
      .orderBy(r.CAREER_SITE_ID, r.FINISHED_AT.desc(), r.ID.desc()).fetch()
      .associate { CareerSiteId(it.value1()!!) to CrawlSummary(it.value2()?.let(CrawlOutcome::valueOf), it.value3()?.toInstant()) }
    return ids.map { rows[it] }
  }

  override fun crawlRuns(ids: List<CrawlRunId>): List<CrawlRunView?> {
    if (ids.isEmpty()) return emptyList()
    val rows = context.selectFrom(CRAWL_RUNS).where(CRAWL_RUNS.ID.`in`(ids.map { it.value }))
      .fetch().map(::crawl).associateBy { it.id }
    return ids.map { rows[it] }
  }

  override fun crawlHistory(keys: List<CrawlHistoryKey>) = boundedBatch(context, keys.map { key -> {
    val r = CRAWL_RUNS
    val scope = ConnectionCursors.scope("crawl-history", "id-desc", key.siteId.value.toString())
    val id = key.page.after?.let { positiveCursorId(it, scope) }
    val condition = r.CAREER_SITE_ID.eq(key.siteId.value)
    pageField(r, r, condition, id?.let { r.ID.lt(it) } ?: DSL.trueCondition(), id != null,
      listOf(r.ID.desc()), key.page, scope, ::crawl) { listOf(it.id.value.toString()) }
  } })

  override fun crawlStatuses(pages: List<ConnectionRequest>) = boundedBatch(context, pages.map { page -> {
    val s = CAREER_SITES
    val r = CRAWL_RUNS
    val scope = ConnectionCursors.scope("crawl-statuses", "site-id-asc")
    val id = page.after?.let { positiveCursorId(it, scope) }
    val latest = DSL.multiset(DSL.select(r).from(r).where(r.CAREER_SITE_ID.eq(s.ID)).orderBy(r.ID.desc()).limit(1))
    val selected = DSL.row(s.ID, latest).mapping { siteId, runs ->
      val run = runs.singleOrNull()?.value1()
      CrawlStatus(CareerSiteId(siteId!!), run?.id?.let(::CrawlRunId), run?.outcome?.let(CrawlOutcome::valueOf), run?.finishedAt?.toInstant(),
        run?.failureCode?.let { CrawlFailure(CrawlFailureCode.valueOf(it), "Crawl failed") })
    }
    pageField(s, selected, DSL.trueCondition(), id?.let { s.ID.gt(it) } ?: DSL.trueCondition(), id != null,
      listOf(s.ID.asc()), page, scope, { it }) { listOf(it.careerSiteId.value.toString()) }
  } })

  override fun auditPages(keys: List<AuditPageKey>) = boundedBatch(context, keys.map { key -> {
    val a = AUDIT_EVENTS
    val f = key.filter
    val scope = ConnectionCursors.scope("audit", "occurred-desc-event-desc", f.actorUserId?.toString().orEmpty(),
      f.actorKind?.name.orEmpty(), f.action?.name.orEmpty(), f.targetType.orEmpty(), f.targetId.orEmpty(),
      f.from?.toString().orEmpty(), f.until?.toString().orEmpty())
    val position = key.page.after?.let { cursor ->
      val values = ConnectionCursors.decode(cursor, scope, 2)
      try {
        val time = Instant.parse(values[0]); val id = UUID.fromString(values[1])
        require(time.toString() == values[0] && DiscoveryTimestamp.supports(time) && id.toString() == values[1])
        time.atOffset(UTC) to id
      } catch (_: IllegalArgumentException) { throw InvalidConnectionCursor() }
      catch (_: java.time.DateTimeException) { throw InvalidConnectionCursor() }
    }
    var condition: Condition = DSL.trueCondition()
    f.actorUserId?.let { condition = condition.and(a.ACTOR_USER_ID.eq(it)) }
    f.actorKind?.let { condition = condition.and(a.ACTOR_KIND.eq(it.name)) }
    f.action?.let { condition = condition.and(a.ACTION.eq(it.wireName)) }
    f.targetType?.let { condition = condition.and(a.TARGET_TYPE.eq(it)) }
    f.targetId?.let { condition = condition.and(a.TARGET_ID.eq(it)) }
    f.from?.let { condition = condition.and(a.OCCURRED_AT.ge(it.atOffset(UTC))) }
    f.until?.let { condition = condition.and(a.OCCURRED_AT.lt(it.atOffset(UTC))) }
    val after = position?.let { (time, id) -> DSL.row(a.OCCURRED_AT, a.EVENT_ID).lt(DSL.row(time, id)) } ?: DSL.trueCondition()
    pageField(a, a, condition, after, position != null, listOf(a.OCCURRED_AT.desc(), a.EVENT_ID.desc()), key.page, scope, ::audit) {
      listOf(it.occurredAt.toString(), it.id.toString())
    }
  } })

  override fun auditEvents(ids: List<UUID>): List<AuditRecord?> {
    if (ids.isEmpty()) return emptyList()
    val rows = context.selectFrom(AUDIT_EVENTS).where(AUDIT_EVENTS.EVENT_ID.`in`(ids)).fetch().map(::audit).associateBy { it.id }
    return ids.map { rows[it] }
  }

  private fun crawl(r: CrawlRunsRecord) = CrawlRunView(CrawlRunId(r.id!!), CareerSiteId(r.careerSiteId!!), r.startedAt!!.toInstant(),
    r.finishedAt?.toInstant(), r.outcome?.let(CrawlOutcome::valueOf), CrawlChangeCounts(r.fetchedCount!!, r.insertedCount!!,
      r.updatedCount!!, r.touchedCount!!, r.missingCount!!, r.closedCount!!, r.reopenedCount!!))
  private fun audit(r: AuditEventsRecord): AuditRecord {
    val action = AuditAction.fromWireName(r.action!!)
    val details = Json.parseToJsonElement(r.details!!.data()).jsonObject.mapValues { (_, value) ->
      value.jsonPrimitive.also { require(it.isString) }.content
    }
    return AuditRecord(r.eventId!!, r.schemaVersion!!, r.occurredAt!!.toInstant(), AuditActorKind.valueOf(r.actorKind!!), r.actorUserId,
      action, r.targetType!!, r.targetId!!, r.requestId!!, r.correlationId!!,
      AuditOutcome.entries.single { it.wireName == r.outcome }, AuditDetails.from(action, details))
  }
}

internal fun positiveCursorId(cursor: ApplicationCursor, scope: String): Long {
  val value = ConnectionCursors.decode(cursor, scope, 1).single()
  return value.toLongOrNull()?.takeIf { it > 0 && it.toString() == value } ?: throw InvalidConnectionCursor()
}
internal fun uuidCursorId(cursor: ApplicationCursor, scope: String): UUID {
  val value = ConnectionCursors.decode(cursor, scope, 1).single()
  return try { UUID.fromString(value).also { require(it.toString() == value) } }
    catch (_: IllegalArgumentException) { throw InvalidConnectionCursor() }
}

internal fun <T> boundedBatch(context: DSLContext, builders: List<() -> Field<ConnectionPage<T>>>): List<Result<ConnectionPage<T>>> {
  if (builders.isEmpty()) return emptyList()
  require(builders.size <= 100)
  val fields = builders.map { build -> try { Result.success(build()) } catch (error: InvalidConnectionCursor) { Result.failure(error) } }
  val valid = fields.mapNotNull { it.getOrNull() }
  val result = if (valid.isEmpty()) null else context.select(valid).fetchSingle()
  return fields.map { prepared -> prepared.map { result!![it]!! } }
}

internal fun <R, T> pageField(table: Table<*>, row: SelectField<R>, condition: Condition, after: Condition, hasPosition: Boolean,
  order: List<SortField<*>>, page: ConnectionRequest, scope: String, map: (R) -> T, cursor: (T) -> List<String>): Field<ConnectionPage<T>> =
  DSL.multiset(DSL.select(
    DSL.field(DSL.select(DSL.count().cast(Long::class.java)).from(table).where(condition)),
    DSL.field(DSL.exists(DSL.selectOne().from(table).where(condition).and(if (hasPosition) after.not() else DSL.falseCondition()))),
    DSL.multiset(DSL.select(row).from(table).where(condition.and(after)).orderBy(order).limit(page.first + 1)),
  )).convertFrom { rows ->
    val result = rows.single()
    val nodes = result.value3().map { map(it.value1()) }
    val edges = nodes.take(page.first).map { ConnectionEdge(it, ConnectionCursors.encode(scope, cursor(it))) }
    ConnectionPage(edges, ConnectionPageInfo(nodes.size > page.first, result.value2(), edges.firstOrNull()?.cursor,
      edges.lastOrNull()?.cursor), result.value1())
  }
