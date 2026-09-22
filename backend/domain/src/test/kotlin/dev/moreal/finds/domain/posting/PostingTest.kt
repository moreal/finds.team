package dev.moreal.finds.domain.posting

import dev.moreal.finds.domain.career.CareerSiteId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class PostingTest {
  @Test
  fun `content hash is stable across surrounding whitespace and line endings`() {
    val left = raw(title = " Backend Engineer ", description = "Build APIs\r\n")
    val right = raw(title = "Backend Engineer", description = "Build APIs\n")

    assertEquals(left.contentHash(), right.contentHash())
  }

  @Test
  fun `content hash changes with user-visible content`() {
    assertNotEquals(
      raw(title = "Backend", description = "Kotlin").contentHash(),
      raw(title = "Backend", description = "Kotlin and Java").contentHash(),
    )
  }

  @Test
  fun `source update timestamp does not affect content hash`() {
    val before = raw(sourceUpdatedAt = Instant.parse("2026-09-21T00:00:00Z"))
    val after = raw(sourceUpdatedAt = Instant.parse("2026-09-22T00:00:00Z"))

    assertEquals(before.contentHash(), after.contentHash())
  }

  @Test
  fun `raw posting rejects invalid identity and title`() {
    assertFailsWith<IllegalArgumentException> { raw(externalKey = "") }
    assertFailsWith<IllegalArgumentException> { raw(externalKey = " posting-1 ") }
    assertFailsWith<IllegalArgumentException> { raw(title = " ") }
  }

  @Test
  fun `posting URL accepts public HTTPS and rejects unsafe forms`() {
    val valid = PostingUrl.parse("HTTPS://jobs.example/postings/one?lang=ko")
    assertEquals(
      "https://jobs.example/postings/one?lang=ko",
      assertIs<PostingUrlResult.Valid>(valid).url.value.toASCIIString(),
    )

    listOf(
      "http://jobs.example/postings/one",
      "//jobs.example/postings/one",
      "https://user@jobs.example/postings/one",
      "https://jobs.example:8443/postings/one",
      "https://127.0.0.1/postings/one",
      "https://jobs.example/postings/one#fragment",
    ).forEach { value ->
      assertIs<PostingUrlResult.Invalid>(PostingUrl.parse(value), value)
    }
  }

  @Test
  fun `persisted posting enforces lifecycle invariants`() {
    assertFailsWith<IllegalArgumentException> { JobPostingId(0) }
    assertFailsWith<IllegalArgumentException> { posting(consecutiveMisses = -1) }
    assertFailsWith<IllegalArgumentException> {
      posting(status = PostingStatus.OPEN, closedAt = NOW)
    }
    assertFailsWith<IllegalArgumentException> {
      posting(status = PostingStatus.CLOSED, closedAt = null)
    }
  }

  private fun raw(
    externalKey: String = "posting-1",
    title: String = "Backend Engineer",
    description: String = "Build APIs",
    sourceUpdatedAt: Instant? = null,
  ) = RawPosting(
    externalKey = externalKey,
    title = title,
    descriptionText = description,
    canonicalUrl = validPostingUrl("https://jobs.example/postings/posting-1"),
    sourceUpdatedAt = sourceUpdatedAt,
  )

  private fun posting(
    status: PostingStatus = PostingStatus.OPEN,
    consecutiveMisses: Int = 0,
    closedAt: Instant? = null,
  ): JobPosting {
    val raw = raw()
    return JobPosting(
      id = JobPostingId(1),
      careerSiteId = CareerSiteId(1),
      raw = raw,
      contentHash = raw.contentHash(),
      status = status,
      consecutiveMisses = consecutiveMisses,
      firstSeenAt = NOW,
      lastSeenAt = NOW,
      updatedAt = NOW,
      closedAt = closedAt,
    )
  }

  private fun validPostingUrl(value: String): PostingUrl =
    assertIs<PostingUrlResult.Valid>(PostingUrl.parse(value)).url

  private companion object {
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
  }
}
