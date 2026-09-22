package dev.moreal.finds_team.graphql

import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.usecase.CrawlSiteResult
import dev.moreal.finds.application.usecase.RegisterCareerSiteResult
import dev.moreal.finds.graphql.FindsGraphqlFacade
import dev.moreal.finds.graphql.GraphqlRuntime
import dev.moreal.finds_team.config.FindsProperties
import dev.moreal.finds_team.runtime.ManagedCoroutineScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder

class GraphqlHttpTest {
  private lateinit var scope: ManagedCoroutineScope
  private lateinit var mvc: MockMvc

  @BeforeEach
  fun setUp() {
    scope = ManagedCoroutineScope()
    val facade = FindsGraphqlFacade(
      searchHandler = { _, _ -> SearchPage(emptyList(), null, 0) },
      registerHandler = { RegisterCareerSiteResult.UnsupportedProvider },
      crawlHandler = { CrawlSiteResult.NotFound },
      statusHandler = { emptyList() },
    )
    val controller = GraphqlController(GraphqlRuntime.create(facade, scope))
    val properties = FindsProperties(graphql = FindsProperties.Graphql(maximumRequestBytes = 1_024))
    mvc = MockMvcBuilders.standaloneSetup(controller)
      .addFilters<StandaloneMockMvcBuilder>(GraphqlRequestLimitFilter(properties))
      .build()
  }

  @AfterEach
  fun tearDown() {
    scope.close()
  }

  @Test
  fun `HTTP endpoint preserves GraphQL data and errors semantics`() {
    graphql("""{"query":"{ jobPostings { totalCount } crawlStatuses { careerSiteId } }"}""")
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.data.jobPostings.totalCount").value(0))
      .andExpect(jsonPath("$.data.crawlStatuses").isArray)

    graphql(
      """{"query":"mutation(${'$'}url: String!) { registerCareerSite(input: {url: ${'$'}url, displayName: \"Acme\"}) { error { code } } triggerCrawl(careerSiteId: \"1\") { outcome } }","variables":{"url":"https://jobs.example"}}""",
    )
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.data.registerCareerSite.error.code").value("UNSUPPORTED_PROVIDER"))
      .andExpect(jsonPath("$.data.triggerCrawl.outcome").value("NOT_FOUND"))

    graphql("{\"query\":\"{ unknownField }\"}")
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.errors").isArray)
  }

  @Test
  fun `HTTP endpoint rejects a request larger than the configured bound`() {
    mvc.perform(
      post("/graphql")
        .contentType(MediaType.APPLICATION_JSON)
        .content("x".repeat(1_025)),
    )
      .andExpect(status().`is`(413))
      .andExpect(jsonPath("$.errors[0].message").value("GraphQL request body is too large"))
  }

  private fun graphql(content: String): ResultActions {
    val pending = mvc.perform(
      post("/graphql")
        .contentType(MediaType.APPLICATION_JSON)
        .content(content),
    )
      .andExpect(request().asyncStarted())
      .andReturn()
    return mvc.perform(asyncDispatch(pending))
  }
}
