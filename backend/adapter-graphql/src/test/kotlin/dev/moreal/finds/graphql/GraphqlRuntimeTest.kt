package dev.moreal.finds.graphql

import graphql.ExecutionInput
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.usecase.CrawlSiteResult
import dev.moreal.finds.application.usecase.RegisterCareerSiteResult
import dev.moreal.finds.application.usecase.SessionPrincipal
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.application.port.UserSessionId
import dev.moreal.finds.application.port.SecurityEventPort
import dev.moreal.finds.domain.identity.UserRole
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.future.await
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphqlRuntimeTest {
  @Test fun `node introspection and typed failures expose a usable Relay contract`() = runTest {
    val facade = FindsGraphqlFacade({ _, _ -> SearchPage(emptyList(), null, 0) },
      { error("must not register") }, { error("must not crawl") }, { emptyList() }, SecurityEventPort {})
    val graphQL = GraphqlRuntime.create(facade, this)
    val introspection = graphQL.execute("""{ __type(name: "Node") { kind possibleTypes { name } } }""")
    assertEquals(emptyList(), introspection.errors)
    val node = introspection.getData<Map<String, Map<String, Any?>>>()?.get("__type")
    assertEquals("INTERFACE", node?.get("kind"))
    assertEquals(setOf("JobPosting", "CareerSite", "User", "Skill", "CrawlRun", "AuditEvent"),
      (node?.get("possibleTypes") as List<*>).map { (it as Map<*, *>)["name"] }.toSet())
    for (field in listOf("node", "jobPosting")) {
      val invalid = graphQL.execute("""{ $field(id: "not-an-id") { id } }""")
      assertEquals("INVALID_INPUT", invalid.errors.single().extensions?.get("code"))
    }
    val wrongType = graphQL.execute("""{ jobPosting(id: "djE6Q2FyZWVyU2l0ZTox") { id } }""")
    assertEquals("INVALID_INPUT", wrongType.errors.single().extensions?.get("code"))
    for (field in listOf("node", "jobPosting")) {
      val unavailable = graphQL.execute("""{ $field(id: "djE6Sm9iUG9zdGluZzoxMjM") { id } }""")
      assertEquals("NOT_FOUND", unavailable.errors.single().extensions?.get("code"))
      assertEquals(mapOf(field to null), unavailable.getData())
    }
    for (type in NodeType.entries) {
      val value = if (type in setOf(NodeType.User, NodeType.AuditEvent)) "a6c5b651-4c67-4c17-aa5c-6476f3a1c111" else "123"
      val id = GlobalIdCodec.encode(type, value)
      val unavailable = graphQL.execute("""{ node(id: "$id") { __typename id } }""")
      assertEquals("NOT_FOUND", unavailable.errors.single().extensions?.get("code"))
      assertEquals(mapOf("node" to null), unavailable.getData())
    }
    val filter = graphQL.execute("""{ jobPostings(filter: {not: {atSite: "djE6Sm9iUG9zdGluZzox"}}) { totalCount } }""")
    assertEquals("INVALID_INPUT", filter.errors.single().extensions?.get("code"))
  }
  @Test fun `security event failure becomes sanitized error and never invokes crawl`() = runTest {
    var calls = 0
    val facade = FindsGraphqlFacade({ _, _ -> SearchPage(emptyList(), null, 0) },
      { error("must not register") }, { calls++; CrawlSiteResult.NotFound }, { emptyList() },
      SecurityEventPort { error("database credential=secret") })
    val result = kotlinx.coroutines.supervisorScope {
      GraphqlRuntime.create(facade, this).executeAsync(ExecutionInput.newExecutionInput()
        .query("""mutation { triggerCrawl(careerSiteId: "1", idempotencyKey: "c6c5b651-4c67-4c17-aa5c-6476f3a1c111") { outcome } }""")).await()
    }
    assertEquals("Request failed", result.errors.single().message)
    assertEquals("INTERNAL", result.errors.single().extensions?.get("code"))
    kotlin.test.assertNull(result.getData<Any>())
    assertEquals(0, calls)
  }
  @Test fun `trusted crawl accepts an idempotency key through alias and fragment`() = runTest {
    val principal = SessionPrincipal(Actor.User(UUID.randomUUID(), setOf(UserRole.USER, UserRole.ADMIN),
      Instant.now(), AuthenticationStrength.PASSKEY), UserSessionId(UUID.randomUUID()))
    var calls = 0
    val facade = FindsGraphqlFacade({ _, _ -> SearchPage(emptyList(), null, 0) },
      { RegisterCareerSiteResult.UnsupportedProvider }, { calls++; CrawlSiteResult.NotFound }, { emptyList() }, SecurityEventPort {})
    val result = GraphqlRuntime.create(facade, this).executeAsync(ExecutionInput.newExecutionInput()
      .query("""mutation { ...Crawl } fragment Crawl on Mutation { aliased: triggerCrawl(careerSiteId: "djE6Q2FyZWVyU2l0ZTox", idempotencyKey: "c6c5b651-4c67-4c17-aa5c-6476f3a1c111") { outcome error { code } } }""")
      .graphQLContext { it.put(GraphqlRuntime.SESSION_PRINCIPAL, principal) }.build()).await()
    assertEquals(emptyList(), result.errors)
    assertEquals(1, calls)
  }

  @Test fun `unauthenticated mutation cannot be opened by alias or fragment`() = runTest {
    var crawls = 0
    val facade = FindsGraphqlFacade({ _, _ -> SearchPage(emptyList(), null, 0) },
      { RegisterCareerSiteResult.UnsupportedProvider }, { crawls++; CrawlSiteResult.NotFound }, { emptyList() }, SecurityEventPort {})
    val graphQL = GraphqlRuntime.create(facade, this)
    val result = graphQL.executeAsync(ExecutionInput.newExecutionInput().query("mutation { ...Closed } fragment Closed on Mutation { aliased: triggerCrawl(careerSiteId: \"1\", idempotencyKey: \"c6c5b651-4c67-4c17-aa5c-6476f3a1c111\") { outcome } }")).await()
    assertEquals(0, crawls)
    assertEquals(emptyList(), result.errors)
    assertEquals("FORBIDDEN", result.getData<Map<String, Map<String, String>>>()?.get("aliased")?.get("outcome"))
  }

  @Test fun `unexpected fetcher failures are sanitized`() = runTest {
    val facade = FindsGraphqlFacade({ _, _ -> error("database password=secret admin@example.test") },
      { RegisterCareerSiteResult.UnsupportedProvider }, { CrawlSiteResult.NotFound }, { emptyList() }, SecurityEventPort {})
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
      securityEvents = SecurityEventPort {},
    )
    val graphQL = GraphqlRuntime.create(facade, this)

    val query = graphQL.execute("{ jobPostings { totalCount pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } crawlStatuses { careerSiteId } }")
    assertEquals(emptyList(), query.errors)
    val postings = query.getData<Map<String, Map<String, Any?>>>()?.get("jobPostings")
    assertEquals(mapOf("hasNextPage" to false, "hasPreviousPage" to false, "startCursor" to null, "endCursor" to null),
      postings?.get("pageInfo"))
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
