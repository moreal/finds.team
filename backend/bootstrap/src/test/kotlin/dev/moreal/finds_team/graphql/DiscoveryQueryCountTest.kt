package dev.moreal.finds_team.graphql

import dev.moreal.finds.graphql.GlobalIdCodec
import dev.moreal.finds.graphql.NodeType
import dev.moreal.finds_team.security.OtpHttpSupport
import graphql.GraphQL
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DiscoveryQueryCountTest : OtpHttpSupport() {
  private val sql get() = context.getBean(DSLContext::class.java)
  private val graph get() = context.getBean(GraphQL::class.java)
  private val selects = AtomicInteger()
  private lateinit var siteIds: List<Long>
  private lateinit var postingIds: List<Long>

  @BeforeEach fun seedDiscovery() {
    sql.execute("delete from posting_skills")
    sql.execute("delete from job_postings")
    sql.execute("delete from career_sites")
    siteIds = (1..6).map { i ->
      (sql.fetchValue("""insert into career_sites(canonical_base_url, host, provider, display_name)
        values (?, ?, 'FLEX', ?) returning id""", "https://company$i.example", "company$i.example", "Company $i") as Number).toLong()
    }
    postingIds = siteIds.flatMapIndexed { index, site -> (1..3).map { p ->
      val id = (sql.fetchValue("""insert into job_postings(career_site_id, external_key, title, description_text,
        canonical_url, content_hash, status, first_seen_at, last_seen_at, updated_at, closed_at,
        taxonomy_version, role_category, employment_type, remote_policy, location_display_name, location_search_value)
        values (?, ?, ?, 'Kotlin required. Java preferred.', ?, ?, ?, '2026-09-22Z', '2026-09-22Z', '2026-09-22Z',
        case when ? then null else timestamptz '2026-09-22Z' end, 1, 'BACKEND', 'FULL_TIME', 'REMOTE', '서울', 'seoul') returning id""",
        site, "p$p", "Backend $index-$p", "https://company$index.example/p$p", "a".repeat(64), if (p < 3) "OPEN" else "CLOSED", p < 3) as Number).toLong()
      for ((slug, level) in listOf("kotlin" to "REQUIRED", "java" to "PREFERRED")) sql.execute(
        """insert into posting_skills(job_posting_id, skill, canonical_slug, mention_text, requirement_level)
          values (?, ?, ?, ?, ?)""", id, slug, slug, slug, level)
      id
    } }
    sql.configuration().set(DefaultExecuteListenerProvider(object : ExecuteListener {
      override fun executeStart(ctx: ExecuteContext) {
        if (ctx.sql()?.startsWith("select", true) == true) selects.incrementAndGet()
      }
    }))
    selects.set(0)
  }

  @Test fun `anonymous HTTP job detail and Node refetch expose classification and company`() {
    val id = GlobalIdCodec.encode(NodeType.JobPosting, postingIds.first())
    val query = """{ jobPosting(id: "$id") { id title careerSite { id slug } classification {
      role { value } employment { value } remote { value } location { searchValue }
      skills { skill { slug } level }
    } } node(id: "$id") { __typename id } }"""
    val pending = mvc.perform(post("/graphql").secure(true).contentType("application/json")
      .content(json.writeValueAsString(mapOf("query" to query))))
      .andExpect(request().asyncStarted()).andReturn()
    mvc.perform(asyncDispatch(pending)).andExpect(status().isOk)
      .andExpect(jsonPath("$.errors").doesNotExist())
      .andExpect(jsonPath("$.data.jobPosting.title").value("Backend 0-1"))
      .andExpect(jsonPath("$.data.jobPosting.classification.role.value").value("BACKEND"))
      .andExpect(jsonPath("$.data.jobPosting.classification.employment.value").value("FULL_TIME"))
      .andExpect(jsonPath("$.data.jobPosting.classification.remote.value").value("REMOTE"))
      .andExpect(jsonPath("$.data.jobPosting.classification.location.searchValue").value("seoul"))
      .andExpect(jsonPath("$.data.jobPosting.careerSite.id").value(GlobalIdCodec.encode(NodeType.CareerSite, siteIds.first())))
      .andExpect(jsonPath("$.data.node.__typename").value("JobPosting"))
    assertTrue(selects.get() <= 2, "Detail, duplicate Node and company must use at most 2 SELECTs: ${selects.get()}")
    val wrong = graph.execute("""{ jobPosting(id: "${GlobalIdCodec.encode(NodeType.CareerSite, siteIds.first())}") { id } }""")
    assertEquals("INVALID_INPUT", wrong.errors.single().extensions?.get("code"))
  }

  @Test fun `recursive filters and forward pages preserve ordering counts and cursor scope`() {
    val filter = """{all: [{hasStatus: OPEN}, {hasSkill: {slug: "kotlin", level: REQUIRED}},
      {hasRole: BACKEND}, {hasEmployment: FULL_TIME}, {hasRemotePolicy: REMOTE}, {atLocation: "seoul"},
      {any: [{textContains: "Backend"}, {any: []}]}, {not: {hasSkill: {slug: "java", level: REQUIRED}}}]}"""
    fun query(after: String = "", where: String = filter) = execute("""{ jobPostings(filter: $where, orderBy: UPDATED_DESC, first: 2 $after) {
      edges { cursor node { id } } totalCount error { code } pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
    } }""").obj("jobPostings")
    val first = query()
    assertEquals(12, first["totalCount"])
    assertEquals(null, first["error"])
    assertEquals(listOf(postingIds[16], postingIds[15]).map { GlobalIdCodec.encode(NodeType.JobPosting, it) },
      first.edges().map { it.obj("node")["id"] })
    assertEquals(true, first.obj("pageInfo")["hasNextPage"])
    assertEquals(false, first.obj("pageInfo")["hasPreviousPage"])
    val cursor = first.obj("pageInfo")["endCursor"]
    val second = query(", after: \"$cursor\"")
    assertEquals(true, second.obj("pageInfo")["hasPreviousPage"])
    assertTrue(first.edges().map { it.obj("node")["id"] }.intersect(second.edges().map { it.obj("node")["id"] }.toSet()).isEmpty())
    assertEquals("INVALID_CURSOR", query(", after: \"$cursor\"", "{hasStatus: CLOSED}").obj("error")["code"])
    assertEquals(0, query(where = "{any: []}")["totalCount"])
    assertEquals(18, query(where = "{all: []}")["totalCount"])
    for (size in listOf(0, 101)) assertEquals("INVALID_PAGE", execute("{ jobPostings(first: $size) { error { code } } }").obj("jobPostings").obj("error")["code"])
  }

  @Test fun `multi-company pages batch open postings and nested companies with a constant query ceiling`() {
    val data = execute("""{ careerSites(first: 6) { totalCount edges { node { id slug openPostings(first: 1) {
      totalCount edges { node { id careerSite { id } } } pageInfo { hasNextPage }
    } } } } }""")
    assertEquals(6, data.obj("careerSites")["totalCount"])
    data.obj("careerSites").edges().forEach { edge ->
      val site = edge.obj("node")
      assertEquals(2, site.obj("openPostings")["totalCount"])
      assertEquals(true, site.obj("openPostings").obj("pageInfo")["hasNextPage"])
      assertEquals(site["id"], site.obj("openPostings").edges().single().obj("node").obj("careerSite")["id"])
    }
    assertTrue(selects.get() <= 3, "Six companies and nested postings must use at most 3 SELECTs: ${selects.get()}")
    val slug = "company-1-${siteIds.first()}"
    val bySlug = execute("""{ careerSite(slug: "$slug") { id openPostings(first: 1) { totalCount } }
      node(id: "${GlobalIdCodec.encode(NodeType.CareerSite, siteIds.first())}") { ... on CareerSite { slug } } }""")
    assertEquals(2, bySlug.obj("careerSite").obj("openPostings")["totalCount"])
    assertEquals(slug, bySlug.obj("node")["slug"])
  }

  @Test fun `multi-skill relationships have a fixed query ceiling and open distinct membership`() {
    val data = execute("""{ skills(first: 100) { totalCount edges { node { slug
      companies(first: 2) { totalCount edges { node { id } } }
      openPostings(first: 2) { totalCount edges { node { id } } }
      relatedSkills(first: 2) { totalCount edges { node { slug } } }
      requirementCounts { required preferred mentioned }
    } } } }""")
    val skills = data.obj("skills").edges().associate { it.obj("node")["slug"] to it.obj("node") }
    val kotlin = skills.getValue("kotlin")
    assertEquals(6, kotlin.obj("companies")["totalCount"])
    assertEquals(12, kotlin.obj("openPostings")["totalCount"])
    assertEquals(listOf("java"), kotlin.obj("relatedSkills").edges().map { it.obj("node")["slug"] })
    assertEquals(mapOf("required" to 12, "preferred" to 0, "mentioned" to 0), kotlin["requirementCounts"])
    assertEquals(mapOf("required" to 0, "preferred" to 12, "mentioned" to 0), skills.getValue("java")["requirementCounts"])
    assertEquals(0, skills.getValue("rust").obj("companies")["totalCount"])
    assertTrue(selects.get() <= 4, "41 skills and four relationship families must use at most 4 SELECTs: ${selects.get()}")
  }

  @Test fun `separate GraphQL requests see committed changes rather than a shared loader cache`() {
    val query = """{ skill(slug: "kotlin") { openPostings(first: 1) { totalCount } requirementCounts { required } } }"""
    assertEquals(12, execute(query).obj("skill").obj("openPostings")["totalCount"])
    sql.execute("update job_postings set status = 'CLOSED', closed_at = updated_at where status = 'OPEN'")
    assertEquals(0, execute(query).obj("skill").obj("openPostings")["totalCount"])
    assertEquals(0, execute(query).obj("skill").obj("requirementCounts")["required"])
  }

  @Test fun `relationship cursors bind parent and collection while invalid aliases do not poison valid batch keys`() {
    val first = execute("""{ skill(slug: "kotlin") {
      companies(first: 1) { edges { node { id } } pageInfo { endCursor hasNextPage hasPreviousPage } }
      openPostings(first: 1) { pageInfo { endCursor } }
      relatedSkills(first: 1) { pageInfo { endCursor } }
    } }""").obj("skill")
    val companyCursor = first.obj("companies").obj("pageInfo")["endCursor"]
    val postingCursor = first.obj("openPostings").obj("pageInfo")["endCursor"]
    val relatedCursor = first.obj("relatedSkills").obj("pageInfo")["endCursor"]
    selects.set(0)
    val aliases = execute("""{ skill(slug: "kotlin") {
      good: companies(first: 1, after: "$companyCursor") { totalCount edges { node { id } } pageInfo { hasPreviousPage } error { code } }
      bad: companies(first: 1, after: "broken") { totalCount error { code } }
      wrongCollection: companies(first: 1, after: "$relatedCursor") { error { code } }
      tail: relatedSkills(first: 1, after: "$relatedCursor") { totalCount edges { node { id } } pageInfo { hasPreviousPage startCursor endCursor } }
    } other: skill(slug: "java") {
      companies(first: 1, after: "$companyCursor") { error { code } }
      openPostings(first: 1, after: "$postingCursor") { error { code } }
      relatedSkills(first: 1, after: "$relatedCursor") { error { code } }
    } }""")
    val kotlin = aliases.obj("skill")
    assertEquals(6, kotlin.obj("good")["totalCount"])
    assertEquals(null, kotlin.obj("good")["error"])
    assertEquals(GlobalIdCodec.encode(NodeType.CareerSite, siteIds[1]), kotlin.obj("good").edges().single().obj("node")["id"])
    assertEquals(true, kotlin.obj("good").obj("pageInfo")["hasPreviousPage"])
    for (alias in listOf("bad", "wrongCollection")) assertEquals("INVALID_CURSOR", kotlin.obj(alias).obj("error")["code"])
    for (field in listOf("companies", "openPostings", "relatedSkills")) assertEquals("INVALID_CURSOR", aliases.obj("other").obj(field).obj("error")["code"])
    assertEquals(emptyList<Any>(), kotlin.obj("tail")["edges"])
    assertEquals(1, kotlin.obj("tail")["totalCount"])
    assertEquals(mapOf("hasPreviousPage" to true, "startCursor" to null, "endCursor" to null), kotlin.obj("tail")["pageInfo"])
    assertTrue(selects.get() <= 2, "Only valid companies/related keys may execute SQL: ${selects.get()}")
  }

  private fun execute(query: String): Map<String, Any?> {
    val result = graph.execute(query)
    assertEquals(emptyList(), result.errors, result.toSpecification().toString())
    return result.getData()!!
  }
  @Suppress("UNCHECKED_CAST") private fun Map<String, Any?>.obj(key: String) = this[key] as Map<String, Any?>
  @Suppress("UNCHECKED_CAST") private fun Map<String, Any?>.edges() = this["edges"] as List<Map<String, Any?>>
}
