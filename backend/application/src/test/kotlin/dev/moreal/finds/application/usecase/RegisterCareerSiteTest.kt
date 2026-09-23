package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.application.testing.*
import dev.moreal.finds.domain.career.*
import dev.moreal.finds.domain.identity.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RegisterCareerSiteTest {
  @Test fun `non admin stale weak future and system actors stop before discovery`() = runTest {
    for (actor in listOf(Actor.System, actor(roles = setOf(UserRole.USER)), actor(at = NOW.minusSeconds(301)),
      actor(at = NOW.plusSeconds(1)), actor(strength = AuthenticationStrength.EMAIL_OTP), actor(strength = AuthenticationStrength.RECOVERY_PROOFS))) {
      val f = Fixture()
      assertEquals(RegisterCareerSiteResult.Forbidden, f.useCase.execute(command().copy(actor = actor)))
      assertEquals(0, f.discoveries)
      assertTrue(f.tx.sites.isEmpty())
      assertTrue(f.tx.auditEvents.isEmpty())
      assertTrue(f.tx.completedRequests.isEmpty())
    }
  }

  @Test fun `revoked missing expired and non owned sessions stop before discovery`() = runTest {
    for (mode in listOf("revoked", "missing", "expired", "other", "role")) {
      val f = Fixture()
      var cmd = command()
      f.tx.execute { tx ->
        tx.users.lockByEmail(user().email)
        when (mode) {
        "revoked" -> tx.userSessions.revoke(SESSION, NOW)
        "missing" -> cmd = cmd.copy(sessionId = UserSessionId(UUID.randomUUID()))
        "expired" -> tx.userSessions.save(session().copy(createdAt = NOW.minusSeconds(10), expiresAt = NOW))
        "other" -> cmd = cmd.copy(actor = Actor.User(UUID.randomUUID(), user().roles, NOW, AuthenticationStrength.PASSKEY))
        "role" -> tx.users.save(assertIs<UserChange.Updated>(user().revokeRole(UserRole.ADMIN)).user)
      } }
      assertEquals(RegisterCareerSiteResult.Forbidden, f.useCase.execute(cmd))
      assertEquals(0, f.discoveries)
      assertTrue(f.tx.auditEvents.isEmpty())
    }
  }

  @Test fun `role or session revoked during discovery is denied before writes`() = runTest {
    for (role in listOf(false, true)) {
      val f = Fixture()
      f.onDiscovery = { f.tx.execute { tx ->
        tx.users.lockByEmail(user().email)
        if (role) tx.users.save(assertIs<UserChange.Updated>(user().revokeRole(UserRole.ADMIN)).user)
        else tx.userSessions.revoke(SESSION, NOW)
      } }
      assertEquals(RegisterCareerSiteResult.Forbidden, f.useCase.execute(command()))
      assertTrue(f.tx.sites.isEmpty())
      assertTrue(f.tx.completedRequests.isEmpty())
      assertTrue(f.tx.auditEvents.isEmpty())
    }
  }

  @Test fun `invalid URL display name and missing key stop before discovery`() = runTest {
    val f = Fixture()
    assertIs<RegisterCareerSiteResult.InvalidUrl>(f.useCase.execute(command("http://jobs.example")))
    assertIs<RegisterCareerSiteResult.InvalidDisplayName>(f.useCase.execute(command(name = " ")))
    assertIs<RegisterCareerSiteResult.InvalidDisplayName>(f.useCase.execute(command(name = " Acme ")))
    assertEquals(RegisterCareerSiteResult.InvalidIdempotencyKey,
      f.useCase.execute(command().copy(metadata = METADATA.copy(idempotencyKey = null))))
    assertEquals(0, f.discoveries)
    assertTrue(f.tx.sites.isEmpty())
  }

  @Test fun `provider discovery occurs outside transaction and creates one audited result`() = runTest {
    val policy = CrawlSettings(successfulInterval = Duration.ofHours(12))
    val f = Fixture(policy = policy)
    val result = assertIs<RegisterCareerSiteResult.Registered>(f.useCase.execute(command()))
    assertEquals(listOf(result.site), f.tx.sites)
    assertEquals(SourceProvider.NINEHIRE, result.site.provider)
    assertEquals(policy, result.site.crawlSettings)
    assertEquals(1, f.discoveries)
    val audit = f.tx.auditEvents.single()
    assertEquals(AuditAction.CAREER_SITE_REGISTERED, audit.action)
    assertEquals("career_site", audit.targetType)
    assertEquals(result.site.id.value.toString(), audit.targetId)
    assertEquals(USER.value, assertIs<Actor.User>(audit.actor).userId)
    assertEquals(METADATA.requestId, audit.requestId)
    assertEquals(METADATA.correlationId, audit.correlationId)
    assertEquals(mapOf("provider" to "NINEHIRE"), audit.details.fields)
    assertEquals("CREATED", f.tx.completedRequests.values.single().outcome)
  }

  @Test fun `audit failure rolls back site and reservation and allows retry`() = runTest {
    val f = Fixture()
    f.tx.auditFailure = { error("audit unavailable") }
    assertFailsWith<IllegalStateException> { f.useCase.execute(command()) }
    assertTrue(f.tx.sites.isEmpty())
    assertTrue(f.tx.completedRequests.isEmpty())
    assertTrue(f.tx.auditEvents.isEmpty())
    f.tx.auditFailure = null
    assertIs<RegisterCareerSiteResult.Registered>(f.useCase.execute(command()))
    assertEquals(1, f.tx.auditEvents.size)
  }

  @Test fun `same key returns original registered outcome without rediscovery`() = runTest {
    val f = Fixture()
    val first = f.useCase.execute(command())
    f.detected = ProviderDiscoveryResult.Failed("now offline")
    assertEquals(first, f.useCase.execute(command().copy(metadata = METADATA.copy(requestId = UUID.randomUUID()))))
    assertEquals(1, f.discoveries)
    assertEquals(1, f.tx.sites.size)
    assertEquals(1, f.tx.auditEvents.size)
    assertEquals(1, f.tx.completedRequests.size)
  }

  @Test fun `normalized URL replays and changed request conflicts even when discovery fails`() = runTest {
    val f = Fixture()
    val first = f.useCase.execute(command("HTTPS://JOBS.EXAMPLE/path///"))
    assertEquals(first, f.useCase.execute(command("https://jobs.example/path")))
    assertEquals(RegisterCareerSiteResult.IdempotencyConflict, f.useCase.execute(command(name = "Other")))
    f.detected = ProviderDiscoveryResult.Unsupported
    assertEquals(RegisterCareerSiteResult.IdempotencyConflict, f.useCase.execute(command("https://other.example")))
    assertEquals(1, f.tx.sites.size)
    assertEquals(1, f.tx.auditEvents.size)
  }

  @Test fun `existing host produces typed existing result without audit or discovery`() = runTest {
    val existing = existingSite()
    val f = Fixture(listOf(existing))
    val result = f.useCase.execute(command())
    assertEquals(RegisterCareerSiteResult.AlreadyRegistered(existing), result)
    assertEquals(result, f.useCase.execute(command()))
    assertEquals(0, f.discoveries)
    assertTrue(f.tx.auditEvents.isEmpty())
    assertEquals("ALREADY_REGISTERED", f.tx.completedRequests.values.single().outcome)
  }

  @Test fun `host registered during discovery is rechecked inside write transaction`() = runTest {
    val f = Fixture()
    f.onDiscovery = { f.tx.execute { it.careerSites.insert(dev.moreal.finds.application.model.NewCareerSite(
      existingSite().canonicalBaseUrl, SourceProvider.FLEX, "Other")) } }
    val result = assertIs<RegisterCareerSiteResult.AlreadyRegistered>(f.useCase.execute(command()))
    assertEquals("Other", result.site.displayName)
    assertEquals(1, f.tx.sites.size)
    assertTrue(f.tx.auditEvents.isEmpty())
  }

  @Test fun `discovery failures are typed and leave no reservations or successful audit`() = runTest {
    val f = Fixture()
    for ((discovery, expected) in listOf(
      ProviderDiscoveryResult.Unsupported to RegisterCareerSiteResult.UnsupportedProvider,
      ProviderDiscoveryResult.Ambiguous(setOf(SourceProvider.FLEX, SourceProvider.NINEHIRE)) to
        RegisterCareerSiteResult.AmbiguousProvider(setOf(SourceProvider.FLEX, SourceProvider.NINEHIRE)),
      ProviderDiscoveryResult.Failed("unavailable") to RegisterCareerSiteResult.DiscoveryFailed("unavailable"),
    )) {
      f.detected = discovery
      assertEquals(expected, f.useCase.execute(command()))
      assertTrue(f.tx.completedRequests.isEmpty())
      assertTrue(f.tx.auditEvents.isEmpty())
      assertTrue(f.tx.sites.isEmpty())
    }
  }

  @Test fun `concurrent same key has one business effect and one audit`() {
    val f = Fixture()
    val barrier = CyclicBarrier(2)
    f.onDiscovery = { barrier.await(5, TimeUnit.SECONDS) }
    val pool = Executors.newFixedThreadPool(2)
    try {
      val futures = (1..2).map { pool.submit<RegisterCareerSiteResult> { runBlocking { f.useCase.execute(command()) } } }
      val results = futures.map { it.get(10, TimeUnit.SECONDS) }
      assertIs<RegisterCareerSiteResult.Registered>(results.first())
      assertEquals(results.first(), results.last())
      assertEquals(1, f.tx.sites.size)
      assertEquals(1, f.tx.auditEvents.size)
      assertEquals(1, f.tx.completedRequests.size)
    } finally { pool.shutdownNow() }
  }

  private class Fixture(initial: List<CareerSite> = emptyList(), policy: CrawlSettings = CrawlSettings()) {
    val tx = FakeTransaction(initial, initialUsers = listOf(user()))
    private val inside = ThreadLocal.withInitial { false }
    var discoveries = 0
    var detected: ProviderDiscoveryResult = ProviderDiscoveryResult.Detected(SourceProvider.NINEHIRE)
    var onDiscovery: () -> Unit = {}
    init { tx.execute { it.users.lockByEmail(user().email); it.userSessions.save(session()) } }
    val transactions = object : TransactionPort {
      override fun <T> execute(block: (TransactionContext) -> T): T {
        inside.set(true)
        return try { tx.execute(block) } finally { inside.set(false) }
      }
    }
    val discovery = object : SourceDiscoveryPort {
      override suspend fun detect(url: SiteUrl): ProviderDiscoveryResult {
        assertFalse(inside.get(), "Provider I/O cannot hold a database transaction")
        synchronized(this) { discoveries++ }
        onDiscovery()
        return detected
      }
    }
    val useCase = RegisterCareerSite(transactions, discovery, FakeClock(NOW), policy)
  }

  companion object {
    private val NOW = Instant.parse("2026-09-23T00:00:00Z")
    private val USER = UserId(UUID.fromString("1e0b06e4-40cb-4c14-bef6-58597d56eac6"))
    private val SESSION = UserSessionId(UUID.fromString("ba4ed099-02cb-41ad-9a10-0b3cc43f3786"))
    private val METADATA = CommandMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private fun actor(roles: Set<UserRole> = setOf(UserRole.USER, UserRole.ADMIN), at: Instant = NOW,
      strength: AuthenticationStrength = AuthenticationStrength.PASSKEY) = Actor.User(USER.value, roles, at, strength)
    private fun user() = User(USER, EmailAddress("admin@example.test"), UserStatus.ACTIVE,
      setOf(UserRole.USER, UserRole.ADMIN), setOf(CredentialId("passkey")))
    private fun session() = UserSession(SESSION, USER, NOW, NOW.plusSeconds(3600), NOW)
    private fun command(url: String = "https://jobs.example", name: String = "Acme") =
      RegisterCareerSiteCommand(url, name, actor(), METADATA, SESSION)
    private fun existingSite() = CareerSite(CareerSiteId(42),
      assertIs<SiteUrlResult.Valid>(SiteUrl.parse("https://jobs.example")).url, SourceProvider.GREETING, "Existing")
  }
}
