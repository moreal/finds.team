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
  private val securityEvents = mutableListOf<dev.moreal.finds.application.port.SecurityEvent>()

  @BeforeEach
  fun setUp() {
    scope = ManagedCoroutineScope()
    val facade = FindsGraphqlFacade(
      searchHandler = { _, _ -> SearchPage(emptyList(), null, 0) },
      registerHandler = { RegisterCareerSiteResult.UnsupportedProvider },
      crawlHandler = { CrawlSiteResult.NotFound },
      statusHandler = { emptyList() },
      securityEvents = dev.moreal.finds.application.port.SecurityEventPort {},
    )
    securityEvents.clear()
    val events = dev.moreal.finds_team.security.HttpSecurityEvents({ securityEvents += it },
      dev.moreal.finds.application.port.ClockPort { java.time.Instant.EPOCH }, io.micrometer.core.instrument.simple.SimpleMeterRegistry())
    val controller = GraphqlController(GraphqlRuntime.create(facade, scope), events)
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

    mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(
      """{"query":"mutation { triggerCrawl(careerSiteId: \"1\") { outcome } }"}"""))
      .andExpect(status().isForbidden)

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

  @Test fun `missing deferred CSRF token records exactly one authorization denial`() {
    mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(
      """{"query":"mutation { triggerCrawl(careerSiteId: \"1\", idempotencyKey: \"00000000-0000-0000-0000-000000000001\") { outcome } }"}"""))
      .andExpect(status().isForbidden)
    kotlin.test.assertEquals(1, securityEvents.size)
    kotlin.test.assertEquals(dev.moreal.finds.application.port.SecurityEventAction.AUTHORIZATION_DENIED, securityEvents.single().action)
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
