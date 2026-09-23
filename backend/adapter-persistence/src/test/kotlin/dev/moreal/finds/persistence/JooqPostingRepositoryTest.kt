package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.model.PageRequest
import dev.moreal.finds.application.model.SearchCursor
import dev.moreal.finds.application.port.InsertCareerSiteResult
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.posting.PostingUrl
import dev.moreal.finds.domain.posting.PostingUrlResult
import dev.moreal.finds.domain.posting.RawPosting
import dev.moreal.finds.domain.search.Filter
import dev.moreal.finds.domain.search.matches
import dev.moreal.finds.persistence.jooq.generated.tables.references.JOB_POSTINGS
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.jooq.DSLContext

class JooqPostingRepositoryTest : PostgresIntegrationTest() {
  @Test
  fun `posting mapping search count and descending cursor are stable`() {
    val (_, context) = migratedContext()
    val siteId = insertSite(context)
    insertPosting(context, siteId, "a", "Backend Kotlin", "Platform", OPEN, NOW)
    insertPosting(context, siteId, "b", "Designer", "Product", OPEN, NOW)
    insertPosting(context, siteId, "c", "Closed", "Old role", CLOSED, NOW.minusSeconds(1))
    insertPosting(context, siteId, "d", "100%_ Engineer", "Literal", OPEN, NOW.minusSeconds(2))
    val repository = JooqPostingRepository(context)

    assertEquals(4, repository.findByCareerSite(siteId).size)
    val first = repository.search(Filter.And(emptyList()), PageRequest(2))
    val second = repository.search(Filter.And(emptyList()), PageRequest(2, first.next))

    assertEquals(4, first.totalCount)
    assertEquals(listOf("b", "a"), first.postings.map { it.raw.externalKey })
    assertEquals(listOf("c", "d"), second.postings.map { it.raw.externalKey })
    assertEquals(null, second.next)
    val beyondLast = repository.search(Filter.And(emptyList()),
      PageRequest(2, SearchCursor(NOW.minusSeconds(3), second.postings.last().id)))
    assertEquals(4, beyondLast.totalCount, "An empty cursor page must retain the total before cursor filtering")
    assertEquals(emptyList(), beyondLast.postings)
    assertEquals(null, beyondLast.next)
    assertEquals(
      listOf("d"),
      repository.search(Filter.TextContains("%_"), PageRequest(100)).postings
        .map { it.raw.externalKey },
    )
  }

  @Test
  fun `SQL filter algebra equals in-memory domain semantics`() {
    val (_, context) = migratedContext()
    val firstSite = insertSite(context, "https://one.example")
    val secondSite = insertSite(context, "https://two.example", "Two")
    insertPosting(context, firstSite, "a", "Kotlin Backend", "Cloud", OPEN, NOW)
    insertPosting(context, firstSite, "b", "Designer", "Kotlin UI", CLOSED, NOW.minusSeconds(5))
    insertPosting(context, secondSite, "c", "Data", "Pipelines", OPEN, NOW.plusSeconds(5))
    val repository = JooqPostingRepository(context)
    val all = repository.search(Filter.And(emptyList()), PageRequest(100)).postings
    val atoms = listOf<Filter>(
      Filter.AtSite(firstSite),
      Filter.TextContains("kOtLiN"),
      Filter.HasStatus(OPEN),
      Filter.UpdatedAfter(NOW),
    )
    val filters = buildList {
      addAll(atoms)
      addAll(atoms.map(Filter::Not))
      add(Filter.And(emptyList()))
      add(Filter.Or(emptyList()))
      atoms.forEach { left ->
        atoms.forEach { right ->
          add(Filter.And(listOf(left, right)))
          add(Filter.Or(listOf(left, Filter.Not(right))))
          add(Filter.Not(Filter.Or(listOf(left, right))))
        }
      }
      add(Filter.Not(Filter.Or(listOf(Filter.AtSite(secondSite), Filter.HasStatus(CLOSED)))))
    }

    filters.forEach { filter ->
      val expected = all.filter(filter::matches).map(JobPosting::id).toSet()
      val actual = repository.search(filter, PageRequest(100)).postings.map(JobPosting::id).toSet()
      assertEquals(expected, actual, filter.toString())
    }
  }

  private fun insertSite(
    context: DSLContext,
    value: String = "https://jobs.example",
    name: String = "Acme",
  ): CareerSiteId = assertIs<InsertCareerSiteResult.Inserted>(
    JooqCareerSiteRepository(context).insert(
      NewCareerSite(url(value), SourceProvider.NINEHIRE, name),
    ),
  ).site.id

  private fun insertPosting(
    context: DSLContext,
    siteId: CareerSiteId,
    key: String,
    title: String,
    description: String,
    status: PostingStatus,
    updatedAt: Instant,
  ) {
    val raw = RawPosting(key, title, description, postingUrl("https://jobs.example/job/$key"))
    val firstSeen = NOW.minusSeconds(100)
    context.insertInto(JOB_POSTINGS)
      .set(JOB_POSTINGS.CAREER_SITE_ID, siteId.value)
      .set(JOB_POSTINGS.EXTERNAL_KEY, key)
      .set(JOB_POSTINGS.TITLE, title)
      .set(JOB_POSTINGS.DESCRIPTION_TEXT, description)
      .set(JOB_POSTINGS.CANONICAL_URL, raw.canonicalUrl.value.toString())
      .set(JOB_POSTINGS.CONTENT_HASH, raw.contentHash())
      .set(JOB_POSTINGS.STATUS, status.name)
      .set(JOB_POSTINGS.CONSECUTIVE_MISSES, if (status == CLOSED) 2 else 0)
      .set(JOB_POSTINGS.FIRST_SEEN_AT, firstSeen.atOffset(ZoneOffset.UTC))
      .set(JOB_POSTINGS.LAST_SEEN_AT, NOW.atOffset(ZoneOffset.UTC))
      .set(JOB_POSTINGS.UPDATED_AT, updatedAt.atOffset(ZoneOffset.UTC))
      .set(
        JOB_POSTINGS.CLOSED_AT,
        if (status == CLOSED) NOW.atOffset(ZoneOffset.UTC) else null,
      )
      .execute()
  }

  private fun url(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url
  private fun postingUrl(value: String): PostingUrl =
    assertIs<PostingUrlResult.Valid>(PostingUrl.parse(value)).url

  private companion object {
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
    val OPEN = PostingStatus.OPEN
    val CLOSED = PostingStatus.CLOSED
  }
}
