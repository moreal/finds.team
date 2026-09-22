package dev.moreal.finds.domain.search

import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.posting.JobPostingId
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.posting.PostingUrl
import dev.moreal.finds.domain.posting.PostingUrlResult
import dev.moreal.finds.domain.posting.RawPosting
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FilterTest {
  @Test
  fun `empty Boolean filters have algebraic identities`() {
    assertTrue(Filter.And(emptyList()).matches(posting()))
    assertFalse(Filter.Or(emptyList()).matches(posting()))
  }

  @Test
  fun `double negation and nested Boolean groups normalize`() {
    val leaf = Filter.TextContains("kotlin")

    assertEquals(leaf, Filter.Not(Filter.Not(leaf)).normalize())
    assertEquals(
      Filter.And(listOf(leaf, Filter.HasStatus(PostingStatus.OPEN))),
      Filter.And(
        listOf(Filter.And(listOf(leaf)), Filter.HasStatus(PostingStatus.OPEN)),
      ).normalize(),
    )
  }

  @Test
  fun `normalization flattens and removes duplicates in first-seen order`() {
    val kotlin = Filter.TextContains("kotlin")
    val open = Filter.HasStatus(PostingStatus.OPEN)

    assertEquals(
      Filter.Or(listOf(kotlin, open)),
      Filter.Or(listOf(kotlin, Filter.Or(listOf(open, kotlin)))).normalize(),
    )
  }

  @Test
  fun `normalization preserves matches semantics`() {
    val random = Random(20260922)
    repeat(1_000) {
      val filter = generatedFilter(random, depth = 4)
      val posting = generatedPosting(random)
      assertEquals(filter.matches(posting), filter.normalize().matches(posting))
    }
  }

  @Test
  fun `leaf filters match observable posting values`() {
    val posting = posting(
      siteId = CareerSiteId(2),
      title = "KOTLIN Backend",
      description = "Remote-friendly APIs",
      updatedAt = Instant.parse("2026-09-22T00:00:00Z"),
    )

    assertTrue(Filter.AtSite(CareerSiteId(2)).matches(posting))
    assertTrue(Filter.TextContains("kotlin").matches(posting))
    assertTrue(Filter.TextContains("REMOTE").matches(posting))
    assertTrue(Filter.HasStatus(PostingStatus.OPEN).matches(posting))
    assertTrue(
      Filter.UpdatedAfter(Instant.parse("2026-09-21T23:59:59Z")).matches(posting),
    )
    assertFalse(Filter.UpdatedAfter(posting.updatedAt).matches(posting))
    assertFalse(Filter.Not(Filter.AtSite(CareerSiteId(2))).matches(posting))
  }

  @Test
  fun `text filter rejects blank query`() {
    assertFailsWith<IllegalArgumentException> { Filter.TextContains(" ") }
  }

  private fun generatedFilter(random: Random, depth: Int): Filter {
    if (depth == 0) {
      return when (random.nextInt(4)) {
        0 -> Filter.AtSite(CareerSiteId(random.nextLong(1, 4)))
        1 -> Filter.TextContains(listOf("kotlin", "java", "remote")[random.nextInt(3)])
        2 -> Filter.HasStatus(PostingStatus.entries[random.nextInt(PostingStatus.entries.size)])
        else -> Filter.UpdatedAfter(Instant.ofEpochSecond(random.nextLong(0, 2_000_000_000)))
      }
    }
    return when (random.nextInt(3)) {
      0 -> Filter.Not(generatedFilter(random, depth - 1))
      1 -> Filter.And(List(random.nextInt(0, 4)) { generatedFilter(random, depth - 1) })
      else -> Filter.Or(List(random.nextInt(0, 4)) { generatedFilter(random, depth - 1) })
    }
  }

  private fun generatedPosting(random: Random): JobPosting {
    val id = random.nextLong(1, 1_000_000)
    val status = PostingStatus.entries[random.nextInt(PostingStatus.entries.size)]
    val seen = Instant.ofEpochSecond(random.nextLong(0, 2_000_000_000))
    return posting(
      id = id,
      siteId = CareerSiteId(random.nextLong(1, 4)),
      title = listOf("Kotlin backend", "Java platform", "Remote data")[random.nextInt(3)],
      description = listOf("Build APIs", "Operate services", "Analyze data")[random.nextInt(3)],
      status = status,
      updatedAt = seen,
      closedAt = seen.takeIf { status == PostingStatus.CLOSED },
    )
  }

  private fun posting(
    id: Long = 1,
    siteId: CareerSiteId = CareerSiteId(1),
    title: String = "Backend Engineer",
    description: String = "Build APIs",
    status: PostingStatus = PostingStatus.OPEN,
    updatedAt: Instant = Instant.parse("2026-09-22T00:00:00Z"),
    closedAt: Instant? = null,
  ): JobPosting {
    val key = "posting-$id"
    val raw = RawPosting(
      externalKey = key,
      title = title,
      descriptionText = description,
      canonicalUrl = validPostingUrl("https://jobs.example/postings/$key"),
    )
    return JobPosting(
      id = JobPostingId(id),
      careerSiteId = siteId,
      raw = raw,
      contentHash = raw.contentHash(),
      status = status,
      consecutiveMisses = 0,
      firstSeenAt = updatedAt,
      lastSeenAt = updatedAt,
      updatedAt = updatedAt,
      closedAt = closedAt,
    )
  }

  private fun validPostingUrl(value: String): PostingUrl =
    assertIs<PostingUrlResult.Valid>(PostingUrl.parse(value)).url
}
