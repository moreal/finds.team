package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.PageRequest
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.testing.FakePostingRepository
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.posting.JobPostingId
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.posting.PostingUrl
import dev.moreal.finds.domain.posting.PostingUrlResult
import dev.moreal.finds.domain.posting.RawPosting
import dev.moreal.finds.domain.search.Filter
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SearchPostingsTest {
  @Test
  fun `normalizes filter and delegates bounded page request`() {
    val repository = FakePostingRepository()
    val page = PageRequest(20)
    val kotlin = Filter.TextContains("kotlin")
    val filter = Filter.And(
      listOf(Filter.And(listOf(kotlin)), Filter.HasStatus(PostingStatus.OPEN)),
    )
    val expected = SearchPage(
      postings = listOf(posting(1), posting(2)),
      next = null,
      totalCount = 2,
    )
    repository.searchResult = expected

    val result = SearchPostings(repository).execute(filter, page)

    assertEquals(expected, result)
    assertEquals(
      listOf<Pair<Filter, PageRequest>>(
        Filter.And(listOf(kotlin, Filter.HasStatus(PostingStatus.OPEN))) to page,
      ),
      repository.searchRequests,
    )
  }

  @Test
  fun `invalid page size fails before repository call`() {
    val repository = FakePostingRepository()
    val useCase = SearchPostings(repository)

    assertFailsWith<IllegalArgumentException> {
      useCase.execute(Filter.And(emptyList()), PageRequest(0))
    }
    assertFailsWith<IllegalArgumentException> {
      useCase.execute(Filter.And(emptyList()), PageRequest(101))
    }
    assertTrue(repository.searchRequests.isEmpty())
  }

  private fun posting(id: Long): JobPosting {
    val raw = RawPosting(
      externalKey = "posting-$id",
      title = "Kotlin Engineer $id",
      descriptionText = "Build APIs",
      canonicalUrl = assertIs<PostingUrlResult.Valid>(
        PostingUrl.parse("https://jobs.example/postings/$id"),
      ).url,
    )
    return JobPosting(
      id = JobPostingId(id),
      careerSiteId = CareerSiteId(1),
      raw = raw,
      contentHash = raw.contentHash(),
      status = PostingStatus.OPEN,
      consecutiveMisses = 0,
      firstSeenAt = NOW,
      lastSeenAt = NOW,
      updatedAt = NOW,
      closedAt = null,
    )
  }

  private companion object {
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
  }
}
