package dev.moreal.finds_team

import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.ProviderDiscoveryResult
import dev.moreal.finds.application.port.SourceDiscoveryPort
import dev.moreal.finds.application.port.SourceFetchPort
import dev.moreal.finds.application.port.SourceFetchResult
import dev.moreal.finds.application.port.TransactionPort
import dev.moreal.finds.application.port.VerificationCodeNotifier
import dev.moreal.finds.application.port.DeliveryRequestId
import dev.moreal.finds.application.port.VerificationCode
import dev.moreal.finds.application.port.VerificationPurpose
import dev.moreal.finds.domain.identity.EmailAddress
import dev.moreal.finds.notification.MailOutboxDispatcher
import dev.moreal.finds.application.usecase.CrawlSite
import dev.moreal.finds.application.usecase.GetCrawlStatus
import dev.moreal.finds.application.usecase.RegisterCareerSite
import dev.moreal.finds.application.usecase.SearchPostings
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.crawl.ClosePolicy
import dev.moreal.finds.domain.crawl.RetryPolicy
import dev.moreal.finds.domain.crawl.Snapshot
import dev.moreal.finds.domain.posting.PostingUrl
import dev.moreal.finds.domain.posting.PostingUrlResult
import dev.moreal.finds.domain.posting.RawPosting
import dev.moreal.finds.graphql.FindsGraphqlFacade
import dev.moreal.finds.graphql.GraphqlRuntime
import dev.moreal.finds.persistence.JooqCareerSiteRepository
import dev.moreal.finds.persistence.JooqCrawlLeasePort
import dev.moreal.finds.persistence.JooqCrawlRunRepository
import dev.moreal.finds.persistence.JooqPostingRepository
import dev.moreal.finds.persistence.JooqSuccessfulCrawlAdapter
import dev.moreal.finds_team.runtime.ManagedCoroutineScope
import graphql.ExecutionResult
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContains
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.system.CapturedOutput

@ExtendWith(OutputCaptureExtension::class)
class BootstrapVerticalSliceTest {
  @Test
  fun `Spring runtime starts migrates and exposes the GraphQL engine`(output: CapturedOutput) {
    val container = postgres.getOrElse { error ->
      assumeTrue(false, "Docker unavailable: ${error.message}")
      error("unreachable")
    }
    SpringApplicationBuilder(Application::class.java)
      .web(WebApplicationType.NONE)
      .run(
        "--spring.profiles.active=test",
        "--finds.mail.recording=true",
        "--finds.mail.scan-interval=1h",
        "--spring.datasource.url=${container.jdbcUrl}",
        "--spring.datasource.username=${container.username}",
        "--spring.datasource.password=${container.password}",
        "--spring.flyway.user=${container.username}",
        "--spring.flyway.password=${container.password}",
        "--finds.crawl.scan-interval=1h",
      )
      .use { context ->
        context.getBean(VerificationCodeNotifier::class.java)
        context.getBean(MailOutboxDispatcher::class.java)
        context.getBean(TransactionPort::class.java).execute { it.careerSites.findEnabled() }
        val result = context.getBean(graphql.GraphQL::class.java).execute(
          "{ jobPostings { totalCount } crawlStatuses { careerSiteId } }",
        ).requireSuccess()
        val data = result.data<Map<String, Any?>>()
        assertTrue(data.containsKey("jobPostings"))
        assertTrue(data.containsKey("crawlStatuses"))
        val messageId = DeliveryRequestId(UUID.randomUUID())
        val correlationId = UUID.randomUUID()
        context.getBean(TransactionPort::class.java).execute {
          context.getBean(VerificationCodeNotifier::class.java).deliver(it,
            EmailAddress("private-runtime-recipient@example.test"), VerificationPurpose.ENROLLMENT,
            VerificationCode("81726354"), Instant.now().plusSeconds(600), messageId, correlationId)
        }
        assertEquals(1, runBlocking { context.getBean(MailOutboxDispatcher::class.java).dispatch() })
        val trace = output.out.lineSequence().single { "mail.delivery.attempt" in it }
        assertContains(trace, "message_id=\"${messageId.value}\"")
        assertContains(trace, "correlation_id=\"$correlationId\"")
        assertContains(trace, "purpose=\"ENROLLMENT\"")
        assertContains(trace, "attempt=\"1\"")
        assertContains(trace, "provider=\"recording\"")
        listOf("private-runtime-recipient", "81726354", "이메일 인증 코드").forEach {
          assertFalse(output.out.contains(it), "Sensitive marker escaped to console: $it")
        }
      }
  }

  @Test
  fun `registration crawl reconciliation search and status cross the PostgreSQL boundary`() {
    val container = postgres.getOrElse { error ->
      assumeTrue(false, "Docker unavailable: ${error.message}")
      error("unreachable")
    }
    val dataSource = PGSimpleDataSource().apply {
      setURL(container.jdbcUrl)
      user = container.username
      password = container.password
    }
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
    val context = DSL.using(dataSource, SQLDialect.POSTGRES)
    val sites = JooqCareerSiteRepository(context)
    val postings = JooqPostingRepository(context)
    val runs = JooqCrawlRunRepository(context)
    val clock = ClockPort { OBSERVED_AT }
    val registration = RegisterCareerSite(
      sites,
      SourceDiscoveryPort { ProviderDiscoveryResult.Detected(SourceProvider.NINEHIRE) },
    )
    val crawling = CrawlSite(
      sites,
      postings,
      runs,
      SourceFetchPort { site ->
        SourceFetchResult.Success(
          Snapshot(
            site.id,
            site.canonicalBaseUrl.host,
            OBSERVED_AT,
            listOf(
              RawPosting(
                externalKey = "backend-1",
                title = "Backend Engineer",
                descriptionText = "Build reliable Kotlin services",
                canonicalUrl = postingUrl(
                  "https://acme.ninehire.site/job_posting/backend-1",
                ),
                employmentHint = "FULL_TIME",
                locationHint = "Seoul",
              ),
            ),
          ),
        )
      },
      JooqCrawlLeasePort(context),
      JooqSuccessfulCrawlAdapter(context),
      clock,
      RetryPolicy(listOf(Duration.ofMinutes(5))),
      ClosePolicy(2),
      "vertical-slice-test",
      Duration.ofMinutes(10),
    )
    val facade = FindsGraphqlFacade(
      SearchPostings(postings),
      registration,
      crawling,
      GetCrawlStatus(runs),
    )

    ManagedCoroutineScope().use { scope ->
      val graphQL = GraphqlRuntime.create(facade, scope)
      val registered = graphQL.execute(
        """mutation { registerCareerSite(input: {url: "https://acme.ninehire.site", displayName: "Acme"}) { site { id provider successfulIntervalSeconds } error { code } } }""",
      ).requireSuccess()
      val registrationPayload = registered.data<Map<String, Map<String, Any?>>>()
        .getValue("registerCareerSite")
      val site = assertIs<Map<String, Any?>>(registrationPayload["site"])
      assertEquals("NINEHIRE", site["provider"].toString())
      val siteId = site.getValue("id").toString()

      val crawled = graphQL.execute(
        """mutation { triggerCrawl(careerSiteId: "$siteId") { outcome counts { fetched inserted } error { code } } }""",
      ).requireSuccess()
      val crawlPayload = crawled.data<Map<String, Map<String, Any?>>>()
        .getValue("triggerCrawl")
      assertEquals("SUCCEEDED", crawlPayload["outcome"].toString())
      assertEquals(1, assertIs<Map<String, Any?>>(crawlPayload["counts"])["inserted"])

      val queried = graphQL.execute(
        """{ jobPostings(filter: {textContains: "Kotlin"}) { totalCount edges { node { title status canonicalUrl } } } crawlStatuses { careerSiteId outcome error { code } } }""",
      ).requireSuccess()
      val data = queried.data<Map<String, Any?>>()
      val connection = assertIs<Map<String, Any?>>(data["jobPostings"])
      assertEquals(1, connection["totalCount"])
      val edge = assertIs<Map<String, Any?>>(assertIs<List<*>>(connection["edges"]).single())
      val posting = assertIs<Map<String, Any?>>(edge["node"])
      assertEquals("Backend Engineer", posting["title"])
      assertEquals("OPEN", posting["status"].toString())
      val status = assertIs<Map<String, Any?>>(assertIs<List<*>>(data["crawlStatuses"]).single())
      assertEquals(siteId, status["careerSiteId"].toString())
      assertEquals("SUCCESS", status["outcome"].toString())
    }
  }

  private fun ExecutionResult.requireSuccess(): ExecutionResult {
    assertTrue(errors.isEmpty(), errors.joinToString { it.message })
    return this
  }

  private inline fun <reified T : Any> ExecutionResult.data(): T = requireNotNull(getData<T>())

  private fun postingUrl(value: String): PostingUrl =
    assertIs<PostingUrlResult.Valid>(PostingUrl.parse(value)).url

  private companion object {
    val OBSERVED_AT: Instant = Instant.parse("2026-09-22T01:02:03Z")
    val postgres: Result<PostgreSQLContainer> by lazy {
      runCatching { PostgreSQLContainer("postgres:17-alpine").apply { start() } }
    }
  }
}
