package dev.moreal.finds.graphql

import dev.moreal.finds.application.model.CrawlChangeCounts
import dev.moreal.finds.application.model.CrawlFailure
import dev.moreal.finds.application.model.CrawlFailureCode
import dev.moreal.finds.application.model.CrawlRunId
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.usecase.CrawlSiteResult
import dev.moreal.finds.application.usecase.RegisterCareerSiteResult
import dev.moreal.finds.application.usecase.RegisterCareerSiteCommand
import dev.moreal.finds.application.usecase.SessionPrincipal
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.application.port.UserSessionId
import dev.moreal.finds.application.port.SecurityEventPort
import dev.moreal.finds.domain.identity.UserRole
import java.time.Instant
import java.util.UUID
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FindsGraphqlFacadeTest {
  private val principal = SessionPrincipal(Actor.User(UUID.randomUUID(), setOf(UserRole.USER, UserRole.ADMIN),
    Instant.now(), AuthenticationStrength.PASSKEY), UserSessionId(UUID.randomUUID()))

  @Test fun `anonymous denials emit only categorical security events without invoking handlers`() = runTest {
    val events = mutableListOf<dev.moreal.finds.application.port.SecurityEvent>()
    val facade = FindsGraphqlFacade({ _, _ -> SearchPage(emptyList(), null, 0) },
      { error("unauthenticated registration invoked") }, { error("unauthenticated crawl invoked") }, { emptyList() },
      SecurityEventPort { events += it })
    assertEquals(ApiErrorCode.FORBIDDEN, facade.registerCareerSite(input()).error?.code)
    assertEquals(ApiErrorCode.FORBIDDEN, facade.triggerCrawl("email@example.test", "secret").error?.code)
    assertEquals(listOf("career_site.registration_denied", "crawl.trigger_denied"), events.map { it.action.wireName })
    assertTrue(events.all { it.actorUserId == null && it.careerSiteId == null })
    assertTrue(events.none { "secret" in it.toString() || "@" in it.toString() })
  }

  @Test fun `manual crawl validates UUID and passes trusted actor session and metadata`() = runTest {
    var command: dev.moreal.finds.application.usecase.CrawlSiteCommand? = null
    val facade = FindsGraphqlFacade({ _, _ -> SearchPage(emptyList(), null, 0) },
      { RegisterCareerSiteResult.UnsupportedProvider }, { command = it; CrawlSiteResult.Triggered(CrawlRunId(7)) },
      { emptyList() }, SecurityEventPort {})
    for (key in listOf("", "1-1-1-1-1", "secret"))
      assertEquals(ApiErrorCode.INVALID_INPUT, facade.triggerCrawl("1", key, principal).error?.code)
    kotlin.test.assertNull(command)
    val result = facade.triggerCrawl("1", input().idempotencyKey, principal)
    assertEquals(CrawlTriggerOutcome.TRIGGERED, result.outcome)
    assertEquals("7", result.runId)
    assertSame(principal.actor, command?.actor)
    assertEquals(principal.sessionId, command?.sessionId)
    assertEquals(UUID.fromString(input().idempotencyKey), command?.metadata?.idempotencyKey)
  }

  @Test fun `registration requires a trusted principal and strict UUID before handler`() = runTest {
    var calls = 0
    val facade = facade(register = { calls++; RegisterCareerSiteResult.UnsupportedProvider })
    assertEquals("FORBIDDEN", facade.registerCareerSite(input()).error?.code?.name)
    for (key in listOf("", "1-1-1-1-1", "not-a-uuid")) {
      assertEquals(ApiErrorCode.INVALID_INPUT, facade.registerCareerSite(input().copy(idempotencyKey = key), principal).error?.code)
    }
    assertEquals(0, calls)
  }

  @Test fun `registration passes trusted actor session and parsed metadata`() = runTest {
    var command: RegisterCareerSiteCommand? = null
    val facade = FindsGraphqlFacade({ _, _ -> SearchPage(emptyList(), null, 0) },
      { command = it; RegisterCareerSiteResult.UnsupportedProvider }, { CrawlSiteResult.NotFound }, { emptyList() }, SecurityEventPort {})
    facade.registerCareerSite(input(), principal)
    assertSame(principal.actor, command?.actor)
    assertEquals(principal.sessionId, command?.sessionId)
    assertEquals(UUID.fromString(input().idempotencyKey), command?.metadata?.idempotencyKey)
  }

  @Test fun `URL diagnostics are replaced with a stable safe error`() = runTest {
    val facade = facade(register = { RegisterCareerSiteResult.InvalidUrl("https://secret@example.test/?otp=12345678") })
    assertEquals("Invalid career-site URL", facade.registerCareerSite(input(), principal).error?.message)
  }
  @Test fun `registration errors never return raw provider diagnostics`() = runTest {
    val facade = facade(register = { RegisterCareerSiteResult.DiscoveryFailed("token=secret admin@example.test") })
    assertEquals("Career-site discovery failed", facade.registerCareerSite(input(), principal).error?.message)
  }
  @Test fun `registration variants map to stable payload codes`() = runTest {
    var result: RegisterCareerSiteResult = RegisterCareerSiteResult.Registered(site())
    val facade = facade(register = { result })
    assertEquals("1", facade.registerCareerSite(input(), principal).site?.id)

    result = RegisterCareerSiteResult.AmbiguousProvider(setOf(SourceProvider.NINEHIRE, SourceProvider.FLEX))
    val ambiguous = facade.registerCareerSite(input(), principal)
    assertEquals(ApiErrorCode.AMBIGUOUS_PROVIDER, ambiguous.error?.code)
    assertEquals(listOf(SourceProvider.FLEX, SourceProvider.NINEHIRE), ambiguous.error?.providers)

    result = RegisterCareerSiteResult.InvalidUrl("HTTPS required")
    assertEquals(ApiErrorCode.INVALID_URL, facade.registerCareerSite(input(), principal).error?.code)
  }

  @Test fun `manual crawl success failure and invalid id map without exceptions`() = runTest {
    var result: CrawlSiteResult = CrawlSiteResult.Succeeded(
      CrawlRunId(2), CrawlChangeCounts(1, 1, 0, 0, 0, 0, 0),
    )
    val facade = facade(crawl = { result })
    assertEquals(CrawlTriggerOutcome.SUCCEEDED, facade.triggerCrawl("1", input().idempotencyKey, principal).outcome)

    result = CrawlSiteResult.Failed(
      CrawlRunId(3), CrawlFailure(CrawlFailureCode.ROBOTS_DENIED, "denied"),
    )
    val failed = facade.triggerCrawl("1", input().idempotencyKey, principal)
    assertEquals(ApiErrorCode.CRAWL_FAILED, failed.error?.code)
    assertEquals("Crawl failed", failed.error?.message)

    assertEquals(ApiErrorCode.INVALID_INPUT, facade.triggerCrawl("bad", input().idempotencyKey, principal).error?.code)
  }

  private fun facade(
    register: suspend () -> RegisterCareerSiteResult = { RegisterCareerSiteResult.UnsupportedProvider },
    crawl: suspend () -> CrawlSiteResult = { CrawlSiteResult.NotFound },
  ) = FindsGraphqlFacade(
    searchHandler = { _, _ -> SearchPage(emptyList(), null, 0) },
    registerHandler = { register() },
    crawlHandler = { crawl() },
    statusHandler = { emptyList() },
    securityEvents = SecurityEventPort {},
  )

  private fun input() = RegisterCareerSiteInput("https://jobs.example", "Acme", "c6c5b651-4c67-4c17-aa5c-6476f3a1c111")
  private fun site() = CareerSite(
    CareerSiteId(1),
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse("https://jobs.example")).url,
    SourceProvider.NINEHIRE,
    "Acme",
  )
}
