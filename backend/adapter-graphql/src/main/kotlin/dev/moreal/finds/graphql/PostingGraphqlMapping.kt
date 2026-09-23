package dev.moreal.finds.graphql

import dev.moreal.finds.application.model.PageRequest
import dev.moreal.finds.application.model.SearchCursor
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.search.Filter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

data class PostingFilterInput(
  val atSite: String? = null,
  val textContains: String? = null,
  val hasStatus: PostingStatus? = null,
  val updatedAfter: String? = null,
  val not: PostingFilterInput? = null,
  val all: List<PostingFilterInput>? = null,
  val any: List<PostingFilterInput>? = null,
)

data class JobPostingDto(
  val id: String,
  val careerSiteId: String,
  val externalKey: String,
  val title: String,
  val descriptionText: String,
  val canonicalUrl: String,
  val status: PostingStatus,
  val employmentHint: String?,
  val locationHint: String?,
  val remoteHint: String?,
  val sourceUpdatedAt: String?,
  val firstSeenAt: String,
  val lastSeenAt: String,
  val updatedAt: String,
  val closedAt: String?,
)

data class JobPostingEdgeDto(val cursor: String, val node: JobPostingDto)
data class PageInfoDto(
  val hasNextPage: Boolean,
  val endCursor: String?,
  val hasPreviousPage: Boolean = false,
  val startCursor: String? = null,
)
data class JobPostingConnectionDto(
  val edges: List<JobPostingEdgeDto>,
  val pageInfo: PageInfoDto,
  val totalCount: Int,
)

object PostingGraphqlMapping {
  fun filter(input: PostingFilterInput?): Filter =
    input?.toDomain() ?: Filter.HasStatus(PostingStatus.OPEN)

  fun page(first: Int?, after: String?): PageRequest = PageRequest(
    size = first ?: 20,
    after = after?.let(CursorCodec::decode),
  )

  fun connection(page: SearchPage): JobPostingConnectionDto {
    val edges = page.postings.map { posting ->
      JobPostingEdgeDto(
        CursorCodec.encode(SearchCursor(posting.updatedAt, posting.id)),
        posting.toDto(),
      )
    }
    return JobPostingConnectionDto(
      edges,
      // The current forward-only repository cannot efficiently prove a previous edge.
      // Relay permits false in this case; Tasks 4–5 add richer connection metadata.
      PageInfoDto(page.next != null, edges.lastOrNull()?.cursor, startCursor = edges.firstOrNull()?.cursor),
      Math.toIntExact(page.totalCount),
    )
  }

  private fun PostingFilterInput.toDomain(): Filter {
    val operators = listOfNotNull(
      atSite?.let { "atSite" }, textContains?.let { "textContains" },
      hasStatus?.let { "hasStatus" }, updatedAfter?.let { "updatedAfter" },
      not?.let { "not" }, all?.let { "all" }, any?.let { "any" },
    )
    require(operators.size == 1) { "Posting filter must specify exactly one operator" }
    return when (operators.single()) {
      "atSite" -> Filter.AtSite(CareerSiteId(GlobalIdCodec.decode(NodeType.CareerSite, requireNotNull(atSite)).toLong()))
      "textContains" -> Filter.TextContains(requireNotNull(textContains))
      "hasStatus" -> Filter.HasStatus(requireNotNull(hasStatus))
      "updatedAfter" -> Filter.UpdatedAfter(Instant.parse(requireNotNull(updatedAfter)))
      "not" -> Filter.Not(requireNotNull(not).toDomain())
      "all" -> Filter.And(requireNotNull(all).map { it.toDomain() })
      "any" -> Filter.Or(requireNotNull(any).map { it.toDomain() })
      else -> error("Unreachable filter operator")
    }
  }

  private fun JobPosting.toDto() = JobPostingDto(
    GlobalIdCodec.encode(NodeType.JobPosting, id.value), GlobalIdCodec.encode(NodeType.CareerSite, careerSiteId.value), raw.externalKey, raw.title,
    raw.descriptionText, raw.canonicalUrl.value.toString(), status,
    raw.employmentHint, raw.locationHint, raw.remoteHint, raw.sourceUpdatedAt?.toString(),
    firstSeenAt.toString(), lastSeenAt.toString(), updatedAt.toString(), closedAt?.toString(),
  )

}

object CursorCodec {
  private const val VERSION = "v1"

  fun encode(cursor: SearchCursor): String {
    val payload = "$VERSION\n${cursor.updatedAt}\n${cursor.id.value}"
    return Base64.getUrlEncoder().withoutPadding()
      .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
  }

  fun decode(value: String): SearchCursor = try {
    val parts = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split('\n')
    require(parts.size == 3 && parts[0] == VERSION) { "Unsupported cursor format" }
    val id = parts[2].toLong()
    require(id > 0) { "Cursor ID must be positive" }
    SearchCursor(Instant.parse(parts[1]), dev.moreal.finds.domain.posting.JobPostingId(id))
  } catch (error: Exception) {
    throw IllegalArgumentException("Invalid posting cursor", error)
  }
}
