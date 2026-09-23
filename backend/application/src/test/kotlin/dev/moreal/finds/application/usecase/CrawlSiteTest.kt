package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.CrawlChangeCounts
import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.security.*
import dev.moreal.finds.application.testing.FakeTransaction
import dev.moreal.finds.domain.identity.*
import java.util.UUID
import dev.moreal.finds.application.testing.FakeClock
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
import kotlin.test.assertFalse

class CrawlSiteTest {
  @Test
  fun `manual trigger without trusted authentication cannot fetch or create a run`() = runTest {
    val fixture = fixture()
    fixture.useCase.execute(command(CrawlTrigger.MANUAL).copy(actor = Actor.System, sessionId = null))
    assertTrue(fixture.runs.startedSites.isEmpty(), "Untrusted manual trigger created a run")
    assertTrue(fixture.source.fetchedSites.isEmpty())
  }

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
    assertIs<CrawlSiteResult.Triggered>(
      manual.useCase.execute(command(CrawlTrigger.MANUAL)),
    )
    assertEquals(1, manual.source.fetchedSites.size)
  }

  @Test fun `manual replay returns original run without fetching and changed site conflicts`() = runTest {
    val f = fixture()
    val command = command(CrawlTrigger.MANUAL)
    val first = assertIs<CrawlSiteResult.Triggered>(f.useCase.execute(command))
    assertEquals(first, f.useCase.execute(command.copy(metadata = command.metadata.copy(requestId = UUID.randomUUID()))))
    assertEquals(CrawlSiteResult.IdempotencyConflict, f.useCase.execute(command.copy(siteId = CareerSiteId(2))))
    assertEquals(1, f.runs.startedSites.size)
    assertEquals(1, f.source.fetchedSites.size)
    assertEquals(1, f.tx.auditEvents.size)
    assertEquals("crawl.manually_triggered", f.tx.auditEvents.single().action.wireName)
    assertEquals(first.runId.value.toString(), f.tx.auditEvents.single().targetId)
    assertEquals("crawl_run", f.tx.auditEvents.single().targetType)
    assertEquals(mapOf("crawl_run" to CommandResourceId.Number(first.runId.value)), f.tx.completedRequests.values.single().resourceIds)
  }

  @Test fun `manual authorization rechecks current role session and freshness before replay`() = runTest {
    for (mode in listOf("role", "revoked", "expired", "missing", "stale", "future", "weak", "other", "suspended")) {
      val f = fixture()
      val command = command(CrawlTrigger.MANUAL)
      f.useCase.execute(command)
      var denied = command
      f.tx.execute {
        it.users.lockByEmail(user().email)
        when (mode) {
          "role" -> it.users.save(User(USER, user().email, UserStatus.ACTIVE, setOf(UserRole.USER), user().credentials))
          "suspended" -> it.users.save(User(USER, user().email, UserStatus.SUSPENDED, user().roles, user().credentials))
          "revoked" -> it.userSessions.revoke(SESSION, NOW)
          "expired" -> it.userSessions.save(session().copy(createdAt = NOW.minusSeconds(10), expiresAt = NOW))
          "missing" -> denied = command.copy(sessionId = null)
          "other" -> denied = command.copy(sessionId = UserSessionId(UUID.randomUUID()))
          "stale" -> denied = command.copy(actor = actor(at = NOW.minusSeconds(301)))
          "future" -> denied = command.copy(actor = actor(at = NOW.plusSeconds(1)))
          "weak" -> denied = command.copy(actor = actor(strength = AuthenticationStrength.EMAIL_OTP))
        }
      }
      assertEquals(CrawlSiteResult.Forbidden, f.useCase.execute(denied), mode)
      assertEquals(1, f.events.size, mode)
      assertEquals(SecurityEventAction.CRAWL_DENIED, f.events.single().action)
      assertEquals(1, f.tx.auditEvents.size)
      assertEquals(1, f.source.fetchedSites.size)
    }
  }

  @Test fun `audit failure rolls back reservation run and lease before source and remains retryable`() = runTest {
    val f = fixture()
    val command = command(CrawlTrigger.MANUAL)
    f.tx.auditFailure = { error("audit unavailable") }
    assertIs<CrawlSiteResult.InfrastructureFailure>(f.useCase.execute(command))
    assertTrue(f.runs.startedSites.isEmpty())
    assertTrue(f.tx.completedRequests.isEmpty())
    assertTrue(f.source.fetchedSites.isEmpty())
    assertTrue(f.leases.attempts.isEmpty())
    f.tx.auditFailure = null
    assertIs<CrawlSiteResult.Triggered>(f.useCase.execute(command))
  }

  @Test fun `security event failure fails closed and no successful audit is emitted`() = runTest {
    val f = fixture()
    f.securityFailure = true
    assertFailsWith<IllegalStateException> {
      f.useCase.execute(command(CrawlTrigger.MANUAL).copy(actor = Actor.System))
    }
    assertTrue(f.runs.startedSites.isEmpty())
    assertTrue(f.tx.completedRequests.isEmpty())
    assertTrue(f.tx.auditEvents.isEmpty())
  }

  @Test fun `failed or cancelled manual crawl remains replayable without another fetch`() = runTest {
    for (cancel in listOf(false, true)) {
      val f = fixture()
      val command = command(CrawlTrigger.MANUAL)
      f.source.throwable = if (cancel) CancellationException("shutdown") else IllegalStateException("transport secret")
      if (cancel) assertFailsWith<CancellationException> { f.useCase.execute(command) }
      else assertIs<CrawlSiteResult.Triggered>(f.useCase.execute(command))
      assertIs<CrawlSiteResult.Triggered>(f.useCase.execute(command))
      assertEquals(1, f.runs.startedSites.size)
      assertEquals(1, f.runs.failedRuns.size)
      assertEquals(1, f.tx.auditEvents.size)
      assertEquals(1, f.leases.releases.size)
    }
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
    val tx = FakeTransaction(if (includeSite) listOf(site) else emptyList(), initialUsers = listOf(user()))
    tx.execute { it.users.lockByEmail(user().email); it.userSessions.save(session()) }
    val postings = FakePostingRepository()
    val source = FakeSourceFetchPort(SourceFetchResult.Success(snapshot()))
    val completion = FakeSuccessfulCrawlPort()
    val f = Fixture(tx, postings, source, completion)
    val runs = object : CrawlRunRepository {
      override fun latestHistory(siteId: CareerSiteId) = tx.crawlRuns.latestHistory(siteId)
      override fun start(siteId: CareerSiteId, startedAt: Instant) = tx.crawlRuns.start(siteId, startedAt)
      override fun fail(runId: dev.moreal.finds.application.model.CrawlRunId, failure: CrawlFailure, finishedAt: Instant) = tx.crawlRuns.fail(runId, failure, finishedAt)
      override fun latestStatuses() = tx.crawlRuns.latestStatuses()
    }
    val leases = object : CrawlLeasePort {
      override fun tryAcquire(siteId: CareerSiteId, owner: String, now: Instant, ttl: Duration) = tx.crawlLeases.tryAcquire(siteId, owner, now, ttl)
      override fun release(siteId: CareerSiteId, owner: String) = tx.crawlLeases.release(siteId, owner)
    }
    val useCase = CrawlSite(
      postings = postings,
      runs = runs,
      source = SourceFetchPort { fetched ->
        assertFalse(f.inside, "Source transport ran inside transaction")
        assertEquals(tx.crawlRuns.startedSites.size, tx.completedRequests.size, "Trigger must commit before transport")
        source.fetch(fetched)
      },
      leases = leases,
      completion = completion,
      clock = FakeClock(NOW),
      retryPolicy = RETRY_POLICY,
      closePolicy = ClosePolicy(2),
      leaseOwner = OWNER,
      leaseTtl = Duration.ofMinutes(2),
      transactions = object : TransactionPort {
        override fun <T> execute(block: (TransactionContext) -> T): T {
          f.inside = true
          return try { tx.execute(block) } finally { f.inside = false }
        }
      },
      securityEvents = SecurityEventPort {
        assertFalse(f.inside, "Security event must be recorded after the transaction")
        if (f.securityFailure) error("Security event storage unavailable")
        f.events += it
      },
    )
    f.useCase = useCase
    return f
  }

  private fun command(trigger: CrawlTrigger = CrawlTrigger.SCHEDULED) =
    CrawlSiteCommand(SITE_ID, trigger, if (trigger == CrawlTrigger.MANUAL) actor() else Actor.System,
      CommandMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
      if (trigger == CrawlTrigger.MANUAL) SESSION else null)

  @Test fun `scheduled requests use stable system scope replay once and later key can crawl`() = runTest {
    val f = fixture()
    val first = command()
    val completed = assertIs<CrawlSiteResult.Succeeded>(f.useCase.execute(first))
    assertEquals(CrawlSiteResult.Triggered(completed.runId), f.useCase.execute(first))
    assertIs<CrawlSiteResult.Succeeded>(f.useCase.execute(command()))
    assertEquals(2, f.source.fetchedSites.size)
    assertEquals(2, f.tx.completedRequests.size)
    assertTrue(f.tx.completedRequests.keys.all { it.scope == "SYSTEM:crawl_scheduler" })
    assertTrue(f.tx.auditEvents.isEmpty())
  }

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

  private class Fixture(
    val tx: FakeTransaction,
    val postings: FakePostingRepository,
    val source: FakeSourceFetchPort,
    val completion: FakeSuccessfulCrawlPort,
  ) {
    val runs get() = tx.crawlRuns
    val leases get() = tx.crawlLeases
    val events = mutableListOf<SecurityEvent>()
    var inside = false
    var securityFailure = false
    lateinit var useCase: CrawlSite
  }

  private companion object {
    val SITE_ID = CareerSiteId(1)
    val SITE_HOST = SiteHost("jobs.example")
    val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
    val RETRY_POLICY = RetryPolicy(
      listOf(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)),
    )
    const val OWNER = "worker-1"
    val USER = UserId(UUID.randomUUID())
    val SESSION = UserSessionId(UUID.randomUUID())
    fun user() = User(USER, EmailAddress("admin@example.test"), UserStatus.ACTIVE,
      setOf(UserRole.USER, UserRole.ADMIN), setOf(CredentialId("passkey")))
    fun actor(at: Instant = NOW, strength: AuthenticationStrength = AuthenticationStrength.PASSKEY) =
      Actor.User(USER.value, user().roles, at, strength)
    fun session() = UserSession(SESSION, USER, NOW, NOW.plusSeconds(3600), NOW)
  }
}
