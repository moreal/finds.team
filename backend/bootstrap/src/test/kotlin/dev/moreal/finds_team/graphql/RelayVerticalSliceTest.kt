package dev.moreal.finds_team.graphql

import dev.moreal.finds.application.audit.*
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.*
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds.graphql.GlobalIdCodec
import dev.moreal.finds.graphql.NodeType
import dev.moreal.finds_team.security.*
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** These HTTP requests are extracted from Relay's committed compiler output, including its fragments. */
@Suppress("DEPRECATION")
class RelayVerticalSliceTest : OtpHttpSupport() {
  private val sql get() = context.getBean(DSLContext::class.java)
  private lateinit var sites: List<Long>
  private lateinit var postings: List<Long>
  private val tie = "2026-09-22T00:00:00Z"

  @BeforeEach fun seedDiscovery() {
    sql.execute("delete from crawl_runs")
    sql.execute("delete from posting_skills")
    sql.execute("delete from job_postings")
    sql.execute("delete from career_sites")
    sites = (1..2).map { n -> (sql.fetchValue("""insert into career_sites(canonical_base_url, host, provider, display_name)
      values (?, ?, 'FLEX', ?) returning id""", "https://relay$n.example", "relay$n.example", "Relay $n") as Number).toLong() }
    postings = sites.flatMap { site -> (1..3).map { posting(site, "job-$it") } } + posting(sites.first(), "closed", false)
  }

  @Test fun `compiled detail directory and Node requests expose the same normalized public records`() {
    val id = postingId(postings.first())
    val detail = execute("DiscoveryOperationsJobQuery", mapOf("id" to id))["jobPosting"]
    assertEquals(id, detail["id"].asText())
    assertEquals("Kotlin required. Java preferred.", detail["descriptionText"].asText())
    assertEquals("BACKEND", detail["classification"]["role"]["value"].asText())
    assertEquals("seoul", detail["classification"]["location"]["searchValue"].asText())
    assertEquals(siteId(sites.first()), detail["careerSite"]["id"].asText())
    val node = execute("DiscoveryOperationsJobRefetchQuery", mapOf("id" to id))["node"]
    assertEquals(id, node["id"].asText())
    assertEquals(detail["classification"], node["classification"])
    val wrong = response("DiscoveryOperationsJobQuery", mapOf("id" to siteId(sites.first())))
    assertEquals("INVALID_INPUT", wrong["errors"][0]["extensions"]["code"].asText())
    val companies = execute("DiscoveryOperationsCompaniesQuery", mapOf("first" to 1, "orderBy" to "ID_ASC"))["careerSites"]
    assertEquals(2, companies["totalCount"].asInt())
    assertEquals(siteId(sites.first()), ids(companies).single())
    val skills = execute("DiscoveryOperationsSkillsQuery", mapOf("query" to "kotlin", "orderBy" to "SLUG_ASC"))["skills"]
    assertEquals(listOf("kotlin"), skills["edges"].toList().map { it["node"]["slug"].asText() })
  }

  @ParameterizedTest @ValueSource(booleans = [false, true])
  fun `compiled all and filtered job pages retain every original equal-sort row across a concurrent insertion`(filtered: Boolean) {
    val filter = if (filtered) mapOf("all" to listOf(
      mapOf("hasStatus" to "OPEN"), mapOf("hasSkill" to mapOf("slug" to "kotlin", "level" to "REQUIRED")),
      mapOf("hasRole" to "BACKEND"), mapOf("hasEmployment" to "FULL_TIME"), mapOf("hasRemotePolicy" to "REMOTE"),
      mapOf("atLocation" to "seoul"), mapOf("not" to mapOf("hasSkill" to mapOf("slug" to "java", "level" to "REQUIRED"))),
    )) else mapOf("all" to emptyList<Any>())
    val expected = (if (filtered) postings.dropLast(1) else postings).reversed().map(::postingId)
    val variables = mapOf("filter" to filter, "orderBy" to "UPDATED_DESC", "first" to 2)
    var page = execute("DiscoveryOperationsJobsQuery", variables)["jobPostings"]
    assertEquals(expected.size, page["totalCount"].asInt())
    assertEquals(expected.take(2), ids(page))
    assertFalse(page["pageInfo"]["hasPreviousPage"].asBoolean())
    val firstCursor = page["pageInfo"]["endCursor"].asText()
    // A separate connection commits a new equal-timestamp row before the current cursor.
    val inserted = CompletableFuture.supplyAsync { posting(sites.last(), "concurrent") }.get(10, TimeUnit.SECONDS)
    val seen = ids(page).toMutableList()
    while (page["pageInfo"]["hasNextPage"].asBoolean()) {
      page = execute("DiscoveryOperationsJobsQuery", variables + ("after" to page["pageInfo"]["endCursor"].asText()))["jobPostings"]
      assertTrue(page["error"].isNull)
      assertTrue(page["pageInfo"]["hasPreviousPage"].asBoolean())
      assertEquals(expected.size + 1, page["totalCount"].asInt())
      seen += ids(page)
      assertTrue(seen.size <= expected.size, "Pagination repeated a cursor or included a row before it")
    }
    assertEquals(expected, seen, "Every original row appears exactly once, in (updated_at, id) descending order")
    assertEquals(seen.size, seen.toSet().size)
    val fresh = execute("DiscoveryOperationsJobsQuery", variables)["jobPostings"]
    assertEquals(postingId(inserted), ids(fresh).first())
    val wrongScope = execute("DiscoveryOperationsJobsQuery", variables + mapOf("filter" to mapOf("hasStatus" to "CLOSED"), "after" to firstCursor))["jobPostings"]
    assertEquals("INVALID_CURSOR", wrongScope["error"]["code"].asText())
  }

  @Test fun `compiled job filter failures are typed empty connections`() {
    for ((filter, code) in listOf(
      mapOf("hasStatus" to "OPEN", "hasRole" to "BACKEND") to "INVALID_FILTER",
      mapOf("hasSkill" to mapOf("slug" to "unknown-relay-skill")) to "UNKNOWN_SKILL",
      mapOf("updatedAfter" to "+300000-01-01T00:00:00Z") to "INVALID_FILTER",
    )) {
      val page = execute("DiscoveryOperationsJobsQuery", mapOf("filter" to filter))["jobPostings"]
      assertEquals(code, page["error"]["code"].asText())
      assertEquals(0, page["totalCount"].asInt())
      assertEquals(emptyList(), ids(page))
      assertFalse(page["pageInfo"]["hasNextPage"].asBoolean())
      assertTrue(page["pageInfo"]["startCursor"].isNull && page["pageInfo"]["endCursor"].isNull)
    }
  }

  @Test fun `compiled company and skill relationships paginate independently and exclude closed postings`() {
    val company = execute("DiscoveryOperationsCompanyQuery", mapOf("slug" to slug(sites.first()), "first" to 2, "orderBy" to "UPDATED_DESC"))["careerSite"]
    assertEquals(3, company["openPostings"]["totalCount"].asInt())
    assertEquals(postings.take(3).reversed().take(2).map(::postingId), ids(company["openPostings"]))
    val variables = mapOf("slug" to "kotlin", "first" to 2, "companiesFirst" to 1, "relatedFirst" to 1,
      "postingsOrder" to "UPDATED_DESC", "companiesOrder" to "ID_ASC", "relatedOrder" to "SLUG_ASC")
    val skill = execute("DiscoveryOperationsSkillQuery", variables)["skill"]
    assertEquals(2, skill["companies"]["totalCount"].asInt())
    assertEquals(6, skill["openPostings"]["totalCount"].asInt())
    assertEquals(6, skill["requirementCounts"]["required"].asInt())
    assertEquals(0, skill["requirementCounts"]["preferred"].asInt())
    assertEquals(listOf("java"), skill["relatedSkills"]["edges"].toList().map { it["node"]["slug"].asText() })
    assertEquals(listOf(siteId(sites.first())), ids(skill["companies"]))
    val next = execute("DiscoveryOperationsSkillQuery", variables + mapOf(
      "companiesAfter" to skill["companies"]["pageInfo"]["endCursor"].asText(),
      "after" to skill["openPostings"]["pageInfo"]["endCursor"].asText(),
      "relatedAfter" to skill["relatedSkills"]["pageInfo"]["endCursor"].asText(),
    ))["skill"]
    assertEquals(listOf(siteId(sites.last())), ids(next["companies"]))
    assertEquals(postings.dropLast(1).reversed().drop(2).take(2).map(::postingId), ids(next["openPostings"]))
    assertEquals(0, next["relatedSkills"]["edges"].size())
    assertTrue(next["relatedSkills"]["pageInfo"]["hasPreviousPage"].asBoolean())
  }

  @Test fun `compiled viewer requests never reuse authenticated records across anonymous or different sessions`() {
    val (a, first) = account()
    val (b, second) = account()
    assertTrue(execute("AccountOperationsViewerQuery")["viewer"].isNull)
    val firstViewer = execute("AccountOperationsViewerQuery", session = first)["viewer"]
    val secondViewer = execute("AccountOperationsViewerQuery", session = second)["viewer"]
    for ((user, viewer) in listOf(a to firstViewer, b to secondViewer)) {
      assertEquals(GlobalIdCodec.encode(NodeType.User, user.id.value), viewer["user"]["id"].asText())
      assertEquals(1, viewer["passkeys"]["totalCount"].asInt())
      assertEquals(1, viewer["sessions"]["totalCount"].asInt())
      assertTrue(viewer["sessions"]["edges"][0]["node"]["current"].asBoolean())
      assertFalse(viewer.toString().contains(user.credentials.single().value))
      val node = execute("AccountOperationsUserRefetchQuery", mapOf("id" to viewer["user"]["id"].asText()), if (user == a) first else second)["node"]
      assertEquals(viewer["user"]["id"], node["id"])
      assertEquals(viewer["user"]["roles"], node["roles"])
    }
    assertNotEquals(ids(firstViewer["passkeys"]), ids(secondViewer["passkeys"]))
    assertNotEquals(ids(firstViewer["sessions"]), ids(secondViewer["sessions"]))
    assertEquals(firstViewer, execute("AccountOperationsViewerQuery", session = first)["viewer"])
    assertTrue(execute("AccountOperationsViewerQuery")["viewer"].isNull)
    val forbidden = response("AccountOperationsUserRefetchQuery", mapOf("id" to firstViewer["user"]["id"].asText()), second)
    assertEquals("FORBIDDEN", forbidden["errors"][0]["extensions"]["code"].asText())
    val passkey = ids(firstViewer["passkeys"]).single()
    val input = mapOf("passkeyId" to passkey, "label" to "Relay laptop", "idempotencyKey" to UUID.randomUUID().toString(), "clientMutationId" to "rename-first")
    val changed = execute("AccountOperationsRenameMutation", mapOf("input" to input), first)["renamePasskey"]
    assertEquals("CHANGED", changed["outcome"].asText())
    assertEquals("rename-first", changed["clientMutationId"].asText())
    val replay = execute("AccountOperationsRenameMutation", mapOf("input" to (input + ("clientMutationId" to "rename-retry"))), first)["renamePasskey"]
    assertEquals("CHANGED", replay["outcome"].asText())
    assertEquals("rename-retry", replay["clientMutationId"].asText())
    assertEquals("IDEMPOTENCY_CONFLICT", execute("AccountOperationsRenameMutation", mapOf("input" to (input + ("label" to "Conflict"))), first)["renamePasskey"]["error"]["code"].asText())
    assertEquals("Relay laptop", execute("AccountOperationsViewerQuery", session = first)["viewer"]["passkeys"]["edges"][0]["node"]["label"].asText())
    assertEquals("Passkey", execute("AccountOperationsViewerQuery", session = second)["viewer"]["passkeys"]["edges"][0]["node"]["label"].asText())
    assertEquals(1, (sql.fetchValue("select count(*) from audit_events where action = 'passkey.renamed' and actor_user_id = ?", a.id.value) as Number).toInt())
  }

  @Test fun `compiled administration requests protect crawl and audit records and retain public summaries`() {
    val (user, admin) = account(true)
    val (_, ordinary) = account()
    val run = (sql.fetchValue("""insert into crawl_runs(career_site_id, started_at, finished_at, outcome, fetched_count)
      values (?, ?::timestamptz, ?::timestamptz, 'SUCCESS', 7) returning id""", sites.first(), tie, tie) as Number).toLong()
    val event = UUID.randomUUID()
    tx.execute { it.auditLog.append(AuditEvent(event, 1, now,
      Actor.User(user.id.value, user.roles, now, AuthenticationStrength.PASSKEY), AuditAction.ROLE_GRANTED,
      "user", user.id.value.toString(), UUID.randomUUID(), UUID.randomUUID(), AuditOutcome.SUCCEEDED,
      AuditDetails.from(AuditAction.ROLE_GRANTED, mapOf("role" to "ADMIN")))) }
    val requests = listOf("AdminOperationsHistoryQuery" to mapOf("slug" to slug(sites.first())),
      "AdminOperationsStatusesQuery" to emptyMap(), "AdminOperationsAuditQuery" to mapOf("filter" to mapOf("actorUserId" to GlobalIdCodec.encode(NodeType.User, user.id.value))))
    for ((operation, variables) in requests) for (session in listOf(null, ordinary)) {
      assertEquals("FORBIDDEN", response(operation, variables, session)["errors"][0]["extensions"]["code"].asText())
    }
    val history = execute(requests[0].first, requests[0].second, admin)["careerSite"]["crawlHistory"]
    val runId = GlobalIdCodec.encode(NodeType.CrawlRun, run)
    assertEquals(listOf(runId), ids(history))
    assertEquals(7, history["edges"][0]["node"]["counts"]["fetched"].asInt())
    assertEquals(runId, execute("AdminOperationsCrawlRefetchQuery", mapOf("id" to runId), admin)["node"]["id"].asText())
    val statuses = execute("AdminOperationsStatusesQuery", session = admin)["crawlStatuses"]["edges"].toList().map { it["node"] }
    assertEquals(runId, statuses.single { it["careerSiteId"].asText() == siteId(sites.first()) }["runId"].asText())
    assertTrue(statuses.single { it["careerSiteId"].asText() == siteId(sites.last()) }["runId"].isNull)
    val audit = execute(requests[2].first, requests[2].second, admin)["auditEvents"]
    val eventId = GlobalIdCodec.encode(NodeType.AuditEvent, event)
    assertEquals(listOf(eventId), ids(audit))
    assertEquals("ADMIN", audit["edges"][0]["node"]["details"]["role"].asText())
    assertEquals(eventId, execute("AdminOperationsAuditRefetchQuery", mapOf("id" to eventId), admin)["node"]["id"].asText())
    for ((query, id) in listOf("AdminOperationsCrawlRefetchQuery" to runId, "AdminOperationsAuditRefetchQuery" to eventId)) {
      assertEquals("FORBIDDEN", response(query, mapOf("id" to id))["errors"][0]["extensions"]["code"].asText())
    }
    val summary = execute("DiscoveryOperationsCompanyQuery", mapOf("slug" to slug(sites.first())))["careerSite"]["crawlSummary"]
    assertEquals("SUCCESS", summary["outcome"].asText())
    // An anonymous request after an admin request must still be denied.
    assertEquals("FORBIDDEN", response("AdminOperationsStatusesQuery")["errors"][0]["extensions"]["code"].asText())
  }

  @Test fun `compiled account and administrator mutations preserve typed policies and client mutation ids`() {
    val (_, session) = account()
    val (_, admin) = account(true)
    val viewer = execute("AccountOperationsViewerQuery", session = session)["viewer"]
    val base = mapOf("idempotencyKey" to UUID.randomUUID().toString(), "clientMutationId" to "relay-command")
    val last = execute("AccountOperationsRemoveMutation", mapOf("input" to (base + ("passkeyId" to ids(viewer["passkeys"]).single()))), session)["removePasskey"]
    assertEquals("LAST_CREDENTIAL", last["error"]["code"].asText())
    assertEquals("relay-command", last["clientMutationId"].asText())
    val rotated = execute("AccountOperationsRotateMutation", mapOf("input" to base), session)["rotateRecoveryCode"]
    assertEquals("ROTATED", rotated["outcome"].asText())
    assertFalse(rotated["recoveryCode"].isNull)
    val replay = execute("AccountOperationsRotateMutation", mapOf("input" to base), session)["rotateRecoveryCode"]
    assertEquals("ALREADY_ROTATED", replay["outcome"].asText())
    assertTrue(replay["recoveryCode"].isNull)
    assertEquals("relay-command", replay["clientMutationId"].asText())
    assertEquals("UNCHANGED", execute("AccountOperationsRevokeOthersMutation", mapOf("input" to base), session)["revokeOtherSessions"]["outcome"].asText())
    val register = base + mapOf("url" to "https://relay-new.example", "displayName" to "Relay")
    assertEquals("FORBIDDEN", execute("AdminOperationsRegisterMutation", mapOf("input" to register), session)["registerCareerSite"]["error"]["code"].asText())
    val invalid = execute("AdminOperationsRegisterMutation", mapOf("input" to (register + ("url" to "not-a-url"))), admin)["registerCareerSite"]
    assertEquals("INVALID_URL", invalid["error"]["code"].asText())
    assertEquals("relay-command", invalid["clientMutationId"].asText())
    val trigger = base + ("careerSiteId" to siteId(Long.MAX_VALUE))
    assertEquals("FORBIDDEN", execute("AdminOperationsTriggerMutation", mapOf("input" to trigger), session)["triggerCrawl"]["error"]["code"].asText())
    val missing = execute("AdminOperationsTriggerMutation", mapOf("input" to trigger), admin)["triggerCrawl"]
    assertEquals("NOT_FOUND", missing["outcome"].asText())
    assertEquals("relay-command", missing["clientMutationId"].asText())
    val signedOut = execute("AccountOperationsRevokeMutation", mapOf("input" to (base + ("sessionId" to ids(viewer["sessions"]).single()))), session)["revokeSession"]
    assertEquals("SIGNED_OUT", signedOut["outcome"].asText())
    assertEquals("relay-command", signedOut["clientMutationId"].asText())
    assertTrue(execute("AccountOperationsViewerQuery", session = session)["viewer"].isNull)
  }

  private fun posting(site: Long, key: String, open: Boolean = true): Long {
    val id = (sql.fetchValue("""insert into job_postings(career_site_id, external_key, title, description_text,
      canonical_url, content_hash, status, first_seen_at, last_seen_at, updated_at, closed_at,
      taxonomy_version, role_category, employment_type, remote_policy, location_display_name, location_search_value)
      values (?, ?, 'Backend engineer', 'Kotlin required. Java preferred.', ?, ?, ?, ?::timestamptz, ?::timestamptz,
      ?::timestamptz, case when ? then null else ?::timestamptz end, 1, 'BACKEND', 'FULL_TIME', 'REMOTE', '서울', 'seoul') returning id""",
      site, key, "https://relay.example/$site/$key", "a".repeat(64), if (open) "OPEN" else "CLOSED", tie, tie, tie, open, tie) as Number).toLong()
    for ((skill, level) in listOf("kotlin" to "REQUIRED", "java" to "PREFERRED")) sql.execute("""insert into posting_skills
      (job_posting_id, skill, canonical_slug, mention_text, requirement_level) values (?, ?, ?, ?, ?)""", id, skill, skill, skill, level)
    return id
  }
  private fun account(admin: Boolean = false): Pair<User, MockHttpSession> {
    var user = seed(true)
    if (admin) user = tx.execute { t -> t.users.lockByEmail(user.email); (user.grantRole(UserRole.ADMIN) as UserChange.Updated).user.also(t.users::save) }
    val id = UserSessionId(UUID.randomUUID())
    tx.execute { it.users.lockByEmail(user.email); it.userSessions.save(UserSession(id, user.id, now, now.plusSeconds(3600), now)) }
    return user to MockHttpSession().apply { setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
      SecurityContextImpl(PasskeyAuthentication(PasskeyPrincipal(user.id, id)))) }
  }
  private fun postingId(id: Long) = GlobalIdCodec.encode(NodeType.JobPosting, id)
  private fun siteId(id: Long) = GlobalIdCodec.encode(NodeType.CareerSite, id)
  private fun slug(id: Long) = sql.fetchValue("select slug from career_sites where id = ?", id) as String
  private fun ids(connection: JsonNode) = connection["edges"].toList().map { it["node"]["id"].asText() }
  private fun response(operation: String, variables: Map<String, Any?> = emptyMap(), session: MockHttpSession? = null): JsonNode {
    val artifact = assertNotNull(javaClass.getResource("/$operation.graphql.ts"), "Compile and commit the $operation Relay operation").readText()
    val encoded = assertNotNull(Regex("\"text\": (\"(?:[^\"\\\\]|\\\\.)*\")").find(artifact), "Relay request must contain operation text").groupValues[1]
    val query = json.readTree(encoded).asText()
    val request = post("/graphql").secure(true).with(csrf()).contentType("application/json")
      .content(json.writeValueAsString(mapOf("query" to query, "operationName" to operation, "variables" to variables)))
    session?.let(request::session)
    val pending = mvc.perform(request).andExpect(request().asyncStarted()).andReturn()
    val result = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store")).andReturn()
    return json.readTree(result.response.contentAsString)
  }
  private fun execute(operation: String, variables: Map<String, Any?> = emptyMap(), session: MockHttpSession? = null): JsonNode {
    val response = response(operation, variables, session)
    assertNull(response["errors"], response.toString())
    return response["data"]
  }
}
