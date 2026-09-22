package dev.moreal.finds.graphql

import dev.moreal.finds.application.model.SearchCursor
import dev.moreal.finds.application.model.SearchPage
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

class PostingGraphqlMappingTest {
  @Test fun `omitted filter defaults to open and recursive operators map exactly`() {
    assertEquals(Filter.HasStatus(PostingStatus.OPEN), PostingGraphqlMapping.filter(null))
    val mapped = PostingGraphqlMapping.filter(
      PostingFilterInput(
        all = listOf(
          PostingFilterInput(atSite = "7"),
          PostingFilterInput(not = PostingFilterInput(hasStatus = PostingStatus.CLOSED)),
          PostingFilterInput(any = emptyList()),
        ),
      ),
    )
    assertEquals(
      Filter.And(listOf(
        Filter.AtSite(CareerSiteId(7)),
        Filter.Not(Filter.HasStatus(PostingStatus.CLOSED)),
        Filter.Or(emptyList()),
      )),
      mapped,
    )
    assertFailsWith<IllegalArgumentException> { PostingGraphqlMapping.filter(PostingFilterInput()) }
    assertFailsWith<IllegalArgumentException> {
      PostingGraphqlMapping.filter(PostingFilterInput(atSite = "1", textContains = "x"))
    }
  }

  @Test fun `cursor is opaque versioned and rejects malformed values`() {
    val cursor = SearchCursor(NOW, JobPostingId(42))
    assertEquals(cursor, CursorCodec.decode(CursorCodec.encode(cursor)))
    assertFailsWith<IllegalArgumentException> { CursorCodec.decode("not-base64!") }
  }

  @Test fun `search page maps connection edges and page info`() {
    val posting = posting()
    val dto = PostingGraphqlMapping.connection(
      SearchPage(listOf(posting), SearchCursor(posting.updatedAt, posting.id), 1),
    )
    assertEquals(1, dto.totalCount)
    assertEquals(true, dto.pageInfo.hasNextPage)
    assertEquals(posting.raw.title, dto.edges.single().node.title)
    assertEquals(dto.edges.single().cursor, dto.pageInfo.endCursor)
  }

  private fun posting(): JobPosting {
    val raw = RawPosting("key", "Title", "Description", postingUrl("https://jobs.example/key"))
    return JobPosting(
      JobPostingId(1), CareerSiteId(1), raw, raw.contentHash(), PostingStatus.OPEN,
      0, NOW, NOW, NOW, null,
    )
  }
  private fun postingUrl(value: String): PostingUrl =
    assertIs<PostingUrlResult.Valid>(PostingUrl.parse(value)).url
  private companion object { val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z") }
}
