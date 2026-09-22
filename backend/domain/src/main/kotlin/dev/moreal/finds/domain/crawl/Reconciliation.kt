package dev.moreal.finds.domain.crawl

import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.posting.JobPostingId
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.posting.RawPosting
import java.time.Instant

data class Snapshot(
  val careerSiteId: CareerSiteId,
  val fetchedAt: Instant,
  val postings: List<RawPosting>,
  val sourceRevision: String? = null,
)

data class ClosePolicy(val missesBeforeClose: Int) {
  init {
    require(missesBeforeClose >= 1) { "Close policy must allow at least one miss" }
  }
}

data class PostingRef(
  val id: JobPostingId,
  val externalKey: String,
)

data class NewPosting(
  val raw: RawPosting,
  val contentHash: String,
  val observedAt: Instant,
)

data class PostingUpdate(
  val ref: PostingRef,
  val raw: RawPosting,
  val contentHash: String,
  val observedAt: Instant,
  val consecutiveMisses: Int = 0,
)

data class PostingTouch(
  val ref: PostingRef,
  val observedAt: Instant,
  val consecutiveMisses: Int = 0,
)

data class PostingMiss(
  val ref: PostingRef,
  val consecutiveMisses: Int,
)

data class PostingClosure(
  val ref: PostingRef,
  val closedAt: Instant,
  val consecutiveMisses: Int,
)

data class SyncPlan(
  val insert: List<NewPosting> = emptyList(),
  val update: List<PostingUpdate> = emptyList(),
  val touch: List<PostingTouch> = emptyList(),
  val markMissing: List<PostingMiss> = emptyList(),
  val close: List<PostingClosure> = emptyList(),
  val reopen: List<PostingUpdate> = emptyList(),
) {
  fun operationCount(): Int =
    insert.size + update.size + touch.size + markMissing.size + close.size + reopen.size

  fun isEmpty(): Boolean = operationCount() == 0
}

sealed interface ReconciliationResult {
  data class Success(val plan: SyncPlan) : ReconciliationResult

  data object SuspiciousEmptySnapshot : ReconciliationResult

  data class DuplicateExternalKey(val externalKey: String) : ReconciliationResult

  data class WrongCareerSite(
    val expected: CareerSiteId,
    val actual: CareerSiteId,
  ) : ReconciliationResult
}

fun reconcile(
  existing: List<JobPosting>,
  snapshot: Snapshot,
  policy: ClosePolicy,
  now: Instant,
): ReconciliationResult {
  snapshot.postings
    .groupingBy(RawPosting::externalKey)
    .eachCount()
    .entries
    .firstOrNull { it.value > 1 }
    ?.let { return ReconciliationResult.DuplicateExternalKey(it.key) }

  existing
    .firstOrNull { it.careerSiteId != snapshot.careerSiteId }
    ?.let { posting ->
      return ReconciliationResult.WrongCareerSite(
        expected = snapshot.careerSiteId,
        actual = posting.careerSiteId,
      )
    }

  if (snapshot.postings.isEmpty() && existing.any { it.status == PostingStatus.OPEN }) {
    return ReconciliationResult.SuspiciousEmptySnapshot
  }

  val existingByKey = existing.associateBy { it.raw.externalKey }
  val snapshotByKey = snapshot.postings.associateBy(RawPosting::externalKey)
  val insert = mutableListOf<NewPosting>()
  val update = mutableListOf<PostingUpdate>()
  val touch = mutableListOf<PostingTouch>()
  val markMissing = mutableListOf<PostingMiss>()
  val close = mutableListOf<PostingClosure>()
  val reopen = mutableListOf<PostingUpdate>()

  snapshotByKey.forEach { (externalKey, raw) ->
    val persisted = existingByKey[externalKey]
    val contentHash = raw.contentHash()
    if (persisted == null) {
      insert += NewPosting(raw, contentHash, now)
      return@forEach
    }

    val ref = persisted.ref()
    if (persisted.status == PostingStatus.CLOSED) {
      reopen += PostingUpdate(ref, raw, contentHash, now)
    } else if (persisted.contentHash != contentHash) {
      update += PostingUpdate(ref, raw, contentHash, now)
    } else {
      touch += PostingTouch(ref, now)
    }
  }

  existing.forEach { persisted ->
    if (persisted.raw.externalKey in snapshotByKey || persisted.status == PostingStatus.CLOSED) {
      return@forEach
    }

    val misses = persisted.consecutiveMisses + 1
    if (misses >= policy.missesBeforeClose) {
      close += PostingClosure(persisted.ref(), now, misses)
    } else {
      markMissing += PostingMiss(persisted.ref(), misses)
    }
  }

  return ReconciliationResult.Success(
    SyncPlan(
      insert = insert.sortedBy { it.raw.externalKey },
      update = update.sortedBy { it.ref.externalKey },
      touch = touch.sortedBy { it.ref.externalKey },
      markMissing = markMissing.sortedBy { it.ref.externalKey },
      close = close.sortedBy { it.ref.externalKey },
      reopen = reopen.sortedBy { it.ref.externalKey },
    ),
  )
}

private fun JobPosting.ref(): PostingRef = PostingRef(id, raw.externalKey)
