package dev.moreal.finds.domain.crawl

import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.posting.JobPostingId
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.posting.PostingUrl
import dev.moreal.finds.domain.posting.PostingUrlResult
import dev.moreal.finds.domain.posting.RawPosting
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReconciliationTest {
  @Test
  fun `new posting is inserted and unchanged posting is touched`() {
    val result = reconcile(
      existing = listOf(openPosting("same", misses = 1)),
      snapshot = snapshot(raw("same"), raw("new")),
      policy = ClosePolicy(2),
      now = NOW,
    )

    val plan = assertIs<ReconciliationResult.Success>(result).plan
    assertEquals(listOf("new"), plan.insert.map { it.raw.externalKey })
    assertEquals(listOf("same"), plan.touch.map { it.ref.externalKey })
    assertEquals(0, plan.touch.single().consecutiveMisses)
    assertTrue(plan.markMissing.isEmpty())
  }

  @Test
  fun `first miss is persisted and second miss closes`() {
    val first = planFor(
      listOf(openPosting("gone", misses = 0), openPosting("present")),
      snapshot(raw("present")),
    )
    assertEquals("gone", first.markMissing.single().ref.externalKey)
    assertEquals(1, first.markMissing.single().consecutiveMisses)

    val second = planFor(
      listOf(openPosting("gone", misses = 1), openPosting("present")),
      snapshot(raw("present")),
    )
    assertEquals(listOf("gone"), second.close.map { it.ref.externalKey })
    assertEquals(2, second.close.single().consecutiveMisses)
    assertEquals(NOW, second.close.single().closedAt)
  }

  @Test
  fun `closed posting reopens and resets misses`() {
    val plan = planFor(
      listOf(closedPosting("back", misses = 2)),
      snapshot(raw("back")),
    )

    assertEquals("back", plan.reopen.single().raw.externalKey)
    assertEquals(0, plan.reopen.single().consecutiveMisses)
    assertEquals(NOW, plan.reopen.single().observedAt)
  }

  @Test
  fun `changed open posting is updated and resets misses`() {
    val plan = planFor(
      listOf(openPosting("changed", description = "Old", misses = 1)),
      snapshot(raw("changed", description = "New")),
    )

    val update = plan.update.single()
    assertEquals("New", update.raw.descriptionText)
    assertEquals(0, update.consecutiveMisses)
    assertEquals(NOW, update.observedAt)
  }

  @Test
  fun `closed posting absent from snapshot produces no operation`() {
    val plan = planFor(
      listOf(closedPosting("closed", misses = 2)),
      snapshot(),
    )

    assertTrue(plan.isEmpty())
  }

  @Test
  fun `duplicate keys and suspicious empty snapshots are rejected`() {
    assertEquals(
      ReconciliationResult.DuplicateExternalKey("dup"),
      reconcile(emptyList(), snapshot(raw("dup"), raw("dup")), ClosePolicy(2), NOW),
    )
    assertEquals(
      ReconciliationResult.SuspiciousEmptySnapshot,
      reconcile(listOf(openPosting("existing")), snapshot(), ClosePolicy(2), NOW),
    )
  }

  @Test
  fun `posting from another career site is rejected`() {
    assertEquals(
      ReconciliationResult.WrongCareerSite(expected = SITE_ID, actual = CareerSiteId(2)),
      reconcile(
        existing = listOf(openPosting("other", siteId = CareerSiteId(2))),
        snapshot = snapshot(raw("other")),
        policy = ClosePolicy(2),
        now = NOW,
      ),
    )
  }

  @Test
  fun `posting URL from another host is rejected`() {
    assertEquals(
      ReconciliationResult.WrongPostingHost(
        expected = SITE_HOST,
        actual = SiteHost("foreign.example"),
        externalKey = "foreign",
      ),
      reconcile(
        existing = emptyList(),
        snapshot = snapshot(raw("foreign", host = "foreign.example")),
        policy = ClosePolicy(2),
        now = NOW,
      ),
    )
  }

  @Test
  fun `operations are ordered by external key`() {
    val plan = planFor(
      emptyList(),
      snapshot(raw("z-last"), raw("a-first"), raw("middle")),
    )

    assertEquals(
      listOf("a-first", "middle", "z-last"),
      plan.insert.map { it.raw.externalKey },
    )
  }

  @Test
  fun `applying a plan then reconciling same snapshot produces only touches`() {
    val existing = listOf(
      openPosting("changed", description = "Old"),
      openPosting("same"),
      closedPosting("back", misses = 2),
    )
    val snapshot = snapshot(
      raw("new"),
      raw("changed", description = "New"),
      raw("same"),
      raw("back"),
    )
    val applied = applyPlan(existing, planFor(existing, snapshot))

    val second = planFor(applied, snapshot)

    assertEquals(
      listOf("back", "changed", "new", "same"),
      second.touch.map { it.ref.externalKey },
    )
    assertEquals(4, second.operationCount())
  }

  @Test
  fun `close policy requires at least one miss`() {
    assertFailsWith<IllegalArgumentException> { ClosePolicy(0) }
  }

  @Test
  fun `miss count saturates and closes instead of overflowing`() {
    val plan = planFor(
      listOf(
        openPosting("gone", misses = Int.MAX_VALUE),
        openPosting("present"),
      ),
      snapshot(raw("present")),
    )

    assertEquals(Int.MAX_VALUE, plan.close.single().consecutiveMisses)
  }

  private fun raw(
    key: String,
    description: String = "Description $key",
    host: String = SITE_HOST.value,
  ): RawPosting = RawPosting(
    externalKey = key,
    title = "Title $key",
    descriptionText = description,
    canonicalUrl = validPostingUrl("https://$host/postings/$key"),
  )

  private fun snapshot(vararg postings: RawPosting) = Snapshot(
    careerSiteId = SITE_ID,
    siteHost = SITE_HOST,
    fetchedAt = NOW,
    postings = postings.toList(),
  )

  private fun openPosting(
    key: String,
    description: String = "Description $key",
    misses: Int = 0,
    siteId: CareerSiteId = SITE_ID,
  ): JobPosting {
    val raw = raw(key, description)
    return JobPosting(
      id = JobPostingId(key.sumOf(Char::code).toLong() + 1),
      careerSiteId = siteId,
      raw = raw,
      contentHash = raw.contentHash(),
      status = PostingStatus.OPEN,
      consecutiveMisses = misses,
      firstSeenAt = NOW.minus(Duration.ofDays(1)),
      lastSeenAt = NOW.minus(Duration.ofHours(6)),
      updatedAt = NOW.minus(Duration.ofDays(1)),
      closedAt = null,
    )
  }

  private fun closedPosting(key: String, misses: Int): JobPosting =
    openPosting(key, misses = misses).copy(
      status = PostingStatus.CLOSED,
      closedAt = NOW.minus(Duration.ofHours(1)),
    )

  private fun planFor(existing: List<JobPosting>, snapshot: Snapshot): SyncPlan =
    assertIs<ReconciliationResult.Success>(
      reconcile(existing, snapshot, ClosePolicy(2), NOW),
    ).plan

  private fun applyPlan(existing: List<JobPosting>, plan: SyncPlan): List<JobPosting> {
    val byKey = existing.associateBy { it.raw.externalKey }.toMutableMap()
    var nextId = (existing.maxOfOrNull { it.id.value } ?: 0) + 1
    plan.insert.forEach { insert ->
      byKey[insert.raw.externalKey] = JobPosting(
        id = JobPostingId(nextId++),
        careerSiteId = SITE_ID,
        raw = insert.raw,
        contentHash = insert.contentHash,
        status = PostingStatus.OPEN,
        consecutiveMisses = 0,
        firstSeenAt = insert.observedAt,
        lastSeenAt = insert.observedAt,
        updatedAt = insert.observedAt,
        closedAt = null,
      )
    }
    plan.update.forEach { update ->
      byKey.computeValue(update.ref.externalKey) {
        copy(
          raw = update.raw,
          contentHash = update.contentHash,
          consecutiveMisses = update.consecutiveMisses,
          lastSeenAt = update.observedAt,
          updatedAt = update.observedAt,
        )
      }
    }
    plan.touch.forEach { touch ->
      byKey.computeValue(touch.ref.externalKey) {
        copy(lastSeenAt = touch.observedAt, consecutiveMisses = touch.consecutiveMisses)
      }
    }
    plan.markMissing.forEach { miss ->
      byKey.computeValue(miss.ref.externalKey) {
        copy(consecutiveMisses = miss.consecutiveMisses)
      }
    }
    plan.close.forEach { close ->
      byKey.computeValue(close.ref.externalKey) {
        copy(
          status = PostingStatus.CLOSED,
          consecutiveMisses = close.consecutiveMisses,
          closedAt = close.closedAt,
        )
      }
    }
    plan.reopen.forEach { reopen ->
      byKey.computeValue(reopen.ref.externalKey) {
        copy(
          raw = reopen.raw,
          contentHash = reopen.contentHash,
          status = PostingStatus.OPEN,
          consecutiveMisses = reopen.consecutiveMisses,
          lastSeenAt = reopen.observedAt,
          updatedAt = reopen.observedAt,
          closedAt = null,
        )
      }
    }
    return byKey.values.toList()
  }

  private fun MutableMap<String, JobPosting>.computeValue(
    key: String,
    update: JobPosting.() -> JobPosting,
  ) {
    this[key] = requireNotNull(this[key]).update()
  }

  private fun validPostingUrl(value: String): PostingUrl =
    assertIs<PostingUrlResult.Valid>(PostingUrl.parse(value)).url

  private companion object {
    val SITE_ID = CareerSiteId(1)
    val SITE_HOST = SiteHost("jobs.example")
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
  }
}
