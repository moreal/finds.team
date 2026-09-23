package dev.moreal.finds.graphql

import dev.moreal.finds.application.model.CrawlStatus
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.port.SecurityEventPort
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.crawl.CrawlOutcome
import dev.moreal.finds.application.model.CrawlRunId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DiscoveryGraphqlTest {
  @Test fun `invalid recursive filters have typed connection errors instead of internal failures`() = runTest {
    val graph = GraphqlRuntime.create(facade(), this)
    for ((filter, code) in listOf(
      "{}" to "INVALID_FILTER", "{hasRole: BACKEND, hasStatus: OPEN}" to "INVALID_FILTER",
      "{not: {hasSkill: {slug: \"invented\"}}}" to "UNKNOWN_SKILL",
      "{all: [{updatedAfter: \"yesterday\"}]}" to "INVALID_FILTER",
      "{atLocation: \"  \"}" to "INVALID_FILTER",
    )) {
      val result = graph.execute("{ jobPostings(filter: $filter) { edges { cursor } totalCount error { code } } }")
      assertEquals(emptyList(), result.errors, filter)
      val connection = result.getData<Map<String, Map<String, Any?>>>()!!.getValue("jobPostings")
      assertEquals(mapOf("code" to code), connection["error"], filter)
      assertEquals(emptyList<Any>(), connection["edges"])
      assertEquals(0, connection["totalCount"])
    }
  }

  @Test fun `canonical skill lookup has stable refetch identity and unknown slug is typed`() = runTest {
    val graph = GraphqlRuntime.create(facade(), this)
    val result = graph.execute("""{ skill(slug: "kotlin") { id slug displayName } node(id: "djE6U2tpbGw6MQ") { __typename ... on Skill { slug } } }""")
    assertEquals(emptyList(), result.errors)
    assertEquals(mapOf("skill" to mapOf("id" to "djE6U2tpbGw6MQ", "slug" to "kotlin", "displayName" to "Kotlin"),
      "node" to mapOf("__typename" to "Skill", "slug" to "kotlin")), result.getData())
    val missing = graph.execute("""{ skill(slug: "Kotlin") { id } }""")
    assertEquals("UNKNOWN_SKILL", missing.errors.single().extensions?.get("code"))
  }

  @Test fun `populated crawl statuses encode both global identity types and preserve absent run`() = runTest {
    val graph = GraphqlRuntime.create(facade(listOf(
      CrawlStatus(CareerSiteId(7), CrawlRunId(9), CrawlOutcome.SUCCESS, Instant.EPOCH, null),
      CrawlStatus(CareerSiteId(8), null, null, null, null),
    )), this)
    val result = graph.execute("{ crawlStatuses { careerSiteId runId outcome finishedAt error { code } } }")
    assertEquals(emptyList(), result.errors)
    assertEquals(mapOf("crawlStatuses" to listOf(
      mapOf("careerSiteId" to "djE6Q2FyZWVyU2l0ZTo3", "runId" to "djE6Q3Jhd2xSdW46OQ", "outcome" to "SUCCESS", "finishedAt" to "1970-01-01T00:00:00Z", "error" to null),
      mapOf("careerSiteId" to "djE6Q2FyZWVyU2l0ZTo4", "runId" to null, "outcome" to null, "finishedAt" to null, "error" to null),
    )), result.getData())
  }

  @Test fun `all catalog skill nodes refetch uniquely with fixed stable identities`() = runTest {
    val graph = GraphqlRuntime.create(facade(), this)
    val skills = dev.moreal.finds.domain.posting.SkillTaxonomy.V1.skills
    val ids = skills.map { DiscoveryGraphqlMapping.skill(it).id }
    assertEquals(skills.size, ids.toSet().size)
    assertEquals("djE6U2tpbGw6MTQ", DiscoveryGraphqlMapping.skill(skills.single { it.slug == "rust" }).id)
    for ((skill, id) in skills.zip(ids)) {
      val result = graph.execute("""{ node(id: "$id") { __typename ... on Skill { slug } } }""")
      assertEquals(emptyList(), result.errors)
      assertEquals(mapOf("node" to mapOf("__typename" to "Skill", "slug" to skill.slug)), result.getData())
    }
  }

  private fun facade(statuses: List<CrawlStatus> = emptyList()) = FindsGraphqlFacade(
    { _, _ -> SearchPage(emptyList(), null, 0) }, { error("No registration") }, { error("No crawl") },
    { statuses }, SecurityEventPort {},
  )
}
