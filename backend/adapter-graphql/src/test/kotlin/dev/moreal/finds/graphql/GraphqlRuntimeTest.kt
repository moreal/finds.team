package dev.moreal.finds.graphql

import graphql.ExecutionInput
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.usecase.CrawlSiteResult
import dev.moreal.finds.application.usecase.RegisterCareerSiteResult
import dev.moreal.finds.application.usecase.SessionPrincipal
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.application.port.UserSessionId
import dev.moreal.finds.domain.identity.UserRole
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.future.await
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphqlRuntimeTest {
  @Test fun `closed mutation cannot be opened by alias or fragment`() = runTest {
    var crawls = 0
    val facade = FindsGraphqlFacade({ _, _ -> SearchPage(emptyList(), null, 0) },
      { RegisterCareerSiteResult.UnsupportedProvider }, { crawls++; CrawlSiteResult.NotFound }, { emptyList() })
    val graphQL = GraphqlRuntime.create(facade, this)
    val result = graphQL.executeAsync(ExecutionInput.newExecutionInput().query("mutation { ...Closed } fragment Closed on Mutation { aliased: triggerCrawl(careerSiteId: \"1\") { outcome } }")).await()
    assertEquals(0, crawls)
    assertEquals("FORBIDDEN", result.errors.single().extensions?.get("code"))
  }

  @Test fun `unexpected fetcher failures are sanitized`() = runTest {
    val facade = FindsGraphqlFacade({ _, _ -> error("database password=secret admin@example.test") },
      { RegisterCareerSiteResult.UnsupportedProvider }, { CrawlSiteResult.NotFound }, { emptyList() })
    val result = GraphqlRuntime.create(facade, this).execute("{ jobPostings { totalCount } }")
    assertEquals("Request failed", result.errors.single().message)
    assertEquals("INTERNAL", result.errors.single().extensions?.get("code"))
  }
  @Test fun `public queries and trusted registration execute while schema rejects identity spoofing and missing keys`() = runTest {
    val principal = SessionPrincipal(Actor.User(UUID.randomUUID(), setOf(UserRole.USER, UserRole.ADMIN),
      Instant.now(), AuthenticationStrength.PASSKEY), UserSessionId(UUID.randomUUID()))
    var received: dev.moreal.finds.application.usecase.RegisterCareerSiteCommand? = null
    val facade = FindsGraphqlFacade(
      searchHandler = { _, _ -> SearchPage(emptyList(), null, 0) },
      registerHandler = { received = it; RegisterCareerSiteResult.UnsupportedProvider },
      crawlHandler = { CrawlSiteResult.NotFound },
      statusHandler = { emptyList() },
    )
    val graphQL = GraphqlRuntime.create(facade, this)

    val query = graphQL.execute("{ jobPostings { totalCount pageInfo { hasNextPage } } crawlStatuses { careerSiteId } }")
    assertEquals(emptyList(), query.errors)
    val mutation = graphQL.executeAsync(
      ExecutionInput.newExecutionInput()
        .query("mutation { registerCareerSite(input: {url: \"https://jobs.example\", displayName: \"Acme\", idempotencyKey: \"c6c5b651-4c67-4c17-aa5c-6476f3a1c111\"}) { error { code } } }")
        .graphQLContext { it.put(GraphqlRuntime.SESSION_PRINCIPAL, principal) }
        .build(),
    ).await()
    assertEquals(emptyList(), mutation.errors)
    val data = requireNotNull(mutation.getData<Map<String, Map<String, Any?>>>() )
    assertEquals("UNSUPPORTED_PROVIDER", (data.getValue("registerCareerSite")["error"] as Map<*, *>)["code"])
    assertEquals(principal.actor, received?.actor)
    assertEquals(principal.sessionId, received?.sessionId)
    for (input in listOf("url: \"https://jobs.example\", displayName: \"Acme\"",
      "url: \"https://jobs.example\", displayName: \"Acme\", idempotencyKey: \"key\", actor: \"ADMIN\"")) {
      val invalid = graphQL.execute("mutation { registerCareerSite(input: {$input}) { error { code } } }")
      assertTrue(invalid.errors.isNotEmpty())
    }
  }
}
