package dev.moreal.finds.graphql

import graphql.ExecutionInput
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.usecase.CrawlSiteResult
import dev.moreal.finds.application.usecase.RegisterCareerSiteResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.future.await
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphqlRuntimeTest {
  @Test fun `every root field is wired and representative operations execute`() = runTest {
    val facade = FindsGraphqlFacade(
      searchHandler = { _, _ -> SearchPage(emptyList(), null, 0) },
      registerHandler = { RegisterCareerSiteResult.UnsupportedProvider },
      crawlHandler = { CrawlSiteResult.NotFound },
      statusHandler = { emptyList() },
    )
    val graphQL = GraphqlRuntime.create(facade, this)

    val query = graphQL.execute("{ jobPostings { totalCount pageInfo { hasNextPage } } crawlStatuses { careerSiteId } }")
    assertEquals(emptyList(), query.errors)
    val mutation = graphQL.executeAsync(
      ExecutionInput.newExecutionInput()
        .query("mutation { registerCareerSite(input: {url: \"https://jobs.example\", displayName: \"Acme\"}) { error { code } } triggerCrawl(careerSiteId: \"1\") { outcome } }")
        .build(),
    ).await()
    assertEquals(emptyList(), mutation.errors)
    val data = requireNotNull(mutation.getData<Map<String, Map<String, Any?>>>() )
    assertEquals("UNSUPPORTED_PROVIDER", (data.getValue("registerCareerSite")["error"] as Map<*, *>)["code"])
    assertEquals("NOT_FOUND", data.getValue("triggerCrawl")["outcome"])
  }
}
