package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.CrawlChangeCounts
import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.application.testing.FakeCareerSiteRepository
import dev.moreal.finds.application.testing.FakeClock
import dev.moreal.finds.application.testing.FakeCrawlLeasePort
import dev.moreal.finds.application.testing.FakeCrawlRunRepository
import dev.moreal.finds.application.testing.FakePostingRepository
import dev.moreal.finds.application.testing.FakeSourceFetchPort
import dev.moreal.finds.application.testing.FakeSuccessfulCrawlPort
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.CrawlSettings
import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.crawl.ClosePolicy
import dev.moreal.finds.domain.crawl.CrawlHistory
import dev.moreal.finds.domain.crawl.CrawlOutcome
import dev.moreal.finds.domain.crawl.RetryPolicy
import dev.moreal.finds.domain.crawl.Snapshot
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.posting.JobPostingId
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.posting.PostingUrl
import dev.moreal.finds.domain.posting.PostingUrlResult
import dev.moreal.finds.domain.posting.RawPosting
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CrawlSiteTest {
  @Test
  fun `missing site stops before lease run and fetch`() = runTest {
    val fixture = fixture(includeSite = false)

    assertEquals(
      CrawlSiteResult.NotFound,
      fixture.useCase.execute(command()),
    )
    assertTrue(fixture.leases.attempts.isEmpty())
    assertTrue(fixture.runs.startedSites.isEmpty())
    assertTrue(fixture.source.fetchedSites.isEmpty())
  }

  @Test
  fun `scheduled not due stops before lease and manual bypasses cadence`() = runTest {
    val scheduled = fixture()
    scheduled.runs.histories[SITE_ID] = CrawlHistory(
      CrawlOutcome.SUCCESS,
      NOW.minus(Duration.ofHours(1)),
      consecutiveFailures = 0,
    )

    assertEquals(
      CrawlSiteResult.NotDue(NOW.plus(Duration.ofHours(5))),
      scheduled.useCase.execute(command(CrawlTrigger.SCHEDULED)),
    )
    assertTrue(scheduled.leases.attempts.isEmpty())

    val manual = fixture()
    manual.runs.histories[SITE_ID] = scheduled.runs.histories[SITE_ID]
    assertIs<CrawlSiteResult.Succeeded>(
      manual.useCase.execute(command(CrawlTrigger.MANUAL)),
    )
    assertEquals(1, manual.source.fetchedSites.size)
  }

  @Test
  fun `manual crawl does not bypass disabled setting`() = runTest {
    val fixture = fixture(site = site(enabled = false))

    assertEquals(
      CrawlSiteResult.Disabled,
      fixture.useCase.execute(command(CrawlTrigger.MANUAL)),
    )
    assertTrue(fixture.leases.attempts.isEmpty())
  }

  @Test
  fun `busy lease stops before run and fetch`() = runTest {
    val fixture = fixture()
    fixture.leases.acquireResult = false

    assertEquals(CrawlSiteResult.Busy, fixture.useCase.execute(command()))
    assertTrue(fixture.runs.startedSites.isEmpty())
    assertTrue(fixture.source.fetchedSites.isEmpty())
  }

  @Test
  fun `source failure records failed run without applying plan and releases lease`() = runTest {
    val fixture = fixture()
    val failure = CrawlFailure(CrawlFailureCode.ROBOTS_DENIED, "/private denied")
    fixture.source.result = SourceFetchResult.Failure(failure)

    val result = assertIs<CrawlSiteResult.Failed>(fixture.useCase.execute(command()))

    assertEquals(failure, result.failure)
    assertEquals(result.runId, fixture.runs.failedRuns.single().runId)
    assertTrue(fixture.completion.applied.isEmpty())
    assertEquals(listOf(SITE_ID to OWNER), fixture.leases.releases)
  }

  @Test
  fun `reconciliation failures are recorded without applying plan`() = runTest {
    val cases = listOf(
      invalidSnapshot(raw("dup"), raw("dup")) to CrawlFailureCode.DUPLICATE_EXTERNAL_KEY,
      snapshot(siteId = CareerSiteId(2)) to CrawlFailureCode.WRONG_SITE,
      snapshot(siteHost = SiteHost("foreign.example")) to CrawlFailureCode.WRONG_POSTING_HOST,
    )
    cases.forEach { (snapshot, expectedCode) ->
      val fixture = fixture()
      fixture.source.result = SourceFetchResult.Success(snapshot)

      val result = assertIs<CrawlSiteResult.Failed>(fixture.useCase.execute(command()))

      assertEquals(expectedCode, result.failure.code)
      assertTrue(fixture.completion.applied.isEmpty())
      assertEquals(listOf(SITE_ID to OWNER), fixture.leases.releases)
    }

    val suspicious = fixture()
    suspicious.postings.postingsBySite[SITE_ID] = listOf(openPosting("existing"))
    suspicious.source.result = SourceFetchResult.Success(snapshot())
    val result = assertIs<CrawlSiteResult.Failed>(suspicious.useCase.execute(command()))
    assertEquals(CrawlFailureCode.SUSPICIOUS_SNAPSHOT, result.failure.code)
    assertTrue(suspicious.completion.applied.isEmpty())
    assertEquals(listOf(SITE_ID to OWNER), suspicious.leases.releases)
  }

  @Test
  fun `success applies plan atomically and releases lease`() = runTest {
    val fixture = fixture()
    fixture.source.result = SourceFetchResult.Success(snapshot(raw("new")))
    fixture.completion.result = CrawlChangeCounts(
      fetched = 1,
      inserted = 1,
      updated = 0,
      touched = 0,
      missing = 0,
      closed = 0,
      reopened = 0,
    )

    val result = assertIs<CrawlSiteResult.Succeeded>(fixture.useCase.execute(command()))

    assertEquals(fixture.completion.result, result.counts)
    assertEquals(1, fixture.completion.applied.single().fetched)
    assertEquals("new", fixture.completion.applied.single().plan.insert.single().raw.externalKey)
    assertEquals(listOf(SITE_ID to OWNER), fixture.leases.releases)
  }

  @Test
  fun `source exception is recorded best effort and releases lease`() = runTest {
    val fixture = fixture()
    fixture.source.throwable = IllegalStateException("network\nsecret")

    val result = assertIs<CrawlSiteResult.InfrastructureFailure>(
      fixture.useCase.execute(command()),
    )

    assertEquals("network secret", result.message)
    assertEquals(CrawlFailureCode.SOURCE_FETCH_FAILED, fixture.runs.failedRuns.single().failure.code)
    assertEquals(listOf(SITE_ID to OWNER), fixture.leases.releases)
  }

  @Test
  fun `cancellation records the started run releases lease and propagates`() = runTest {
    val fixture = fixture()
    fixture.source.throwable = CancellationException("scheduler stopped")

    assertFailsWith<CancellationException> {
      fixture.useCase.execute(command())
    }

    assertEquals(CrawlFailureCode.CANCELLED, fixture.runs.failedRuns.single().failure.code)
    assertEquals(listOf(SITE_ID to OWNER), fixture.leases.releases)
  }

  @Test
  fun `completion exception records persistence failure and releases lease`() = runTest {
    val fixture = fixture()
    fixture.completion.throwable = IllegalStateException("database unavailable")

    val result = assertIs<CrawlSiteResult.InfrastructureFailure>(
      fixture.useCase.execute(command()),
    )

    assertEquals("database unavailable", result.message)
    assertEquals(CrawlFailureCode.PERSISTENCE_FAILED, fixture.runs.failedRuns.single().failure.code)
    assertEquals(listOf(SITE_ID to OWNER), fixture.leases.releases)
  }

  private fun fixture(
    includeSite: Boolean = true,
    site: CareerSite = site(),
  ): Fixture {
    val sites = FakeCareerSiteRepository(if (includeSite) listOf(site) else emptyList())
    val runs = FakeCrawlRunRepository()
    val postings = FakePostingRepository()
    val source = FakeSourceFetchPort(SourceFetchResult.Success(snapshot()))
    val leases = FakeCrawlLeasePort()
    val completion = FakeSuccessfulCrawlPort()
    val useCase = CrawlSite(
      sites = sites,
      postings = postings,
      runs = runs,
      source = source,
      leases = leases,
      completion = completion,
      clock = FakeClock(NOW),
      retryPolicy = RETRY_POLICY,
      closePolicy = ClosePolicy(2),
      leaseOwner = OWNER,
      leaseTtl = Duration.ofMinutes(2),
    )
    return Fixture(runs, postings, source, leases, completion, useCase)
  }

  private fun command(trigger: CrawlTrigger = CrawlTrigger.SCHEDULED) =
    CrawlSiteCommand(SITE_ID, trigger)

  private fun site(enabled: Boolean = true): CareerSite = CareerSite(
    id = SITE_ID,
    canonicalBaseUrl = validSiteUrl("https://${SITE_HOST.value}"),
    provider = SourceProvider.GREETING,
    displayName = "Acme",
    crawlSettings = CrawlSettings(enabled = enabled),
  )

  private fun snapshot(
    vararg postings: RawPosting,
    siteId: CareerSiteId = SITE_ID,
    siteHost: SiteHost = SITE_HOST,
  ) = Snapshot(siteId, siteHost, NOW, postings.toList())

  private fun invalidSnapshot(vararg postings: RawPosting) =
    Snapshot(SITE_ID, SITE_HOST, NOW, postings.toList())

  private fun raw(key: String): RawPosting = RawPosting(
    externalKey = key,
    title = "Title $key",
    descriptionText = "Description $key",
    canonicalUrl = validPostingUrl("https://${SITE_HOST.value}/postings/$key"),
  )

  private fun openPosting(key: String): JobPosting {
    val raw = raw(key)
    return JobPosting(
      id = JobPostingId(1),
      careerSiteId = SITE_ID,
      raw = raw,
      contentHash = raw.contentHash(),
      status = PostingStatus.OPEN,
      consecutiveMisses = 0,
      firstSeenAt = NOW.minus(Duration.ofDays(1)),
      lastSeenAt = NOW.minus(Duration.ofHours(6)),
      updatedAt = NOW.minus(Duration.ofDays(1)),
      closedAt = null,
    )
  }

  private fun validSiteUrl(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url

  private fun validPostingUrl(value: String): PostingUrl =
    assertIs<PostingUrlResult.Valid>(PostingUrl.parse(value)).url

  private data class Fixture(
    val runs: FakeCrawlRunRepository,
    val postings: FakePostingRepository,
    val source: FakeSourceFetchPort,
    val leases: FakeCrawlLeasePort,
    val completion: FakeSuccessfulCrawlPort,
    val useCase: CrawlSite,
  )

  private companion object {
    val SITE_ID = CareerSiteId(1)
    val SITE_HOST = SiteHost("jobs.example")
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
    val RETRY_POLICY = RetryPolicy(
      listOf(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)),
    )
    const val OWNER = "worker-1"
  }
}
