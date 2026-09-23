package dev.moreal.finds_team.graphql

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.audit.*
import dev.moreal.finds.application.security.*
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds.graphql.*
import dev.moreal.finds_team.security.*
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@Suppress("DEPRECATION")
class ViewerAdminGraphqlTest : OtpHttpSupport() {
  private val sql get() = context.getBean(DSLContext::class.java)

  @Test fun `viewer and own user Node are isolated between SSR sessions and expose metadata only`() {
    val (first, a) = account()
    val (second, b) = account()
    val query = """{ viewer { user { id roles } passkeys(first: 1) { edges { node { id label createdAt lastUsedAt } } totalCount }
      sessions(first: 1) { edges { node { id current createdAt expiresAt } } totalCount } } }"""
    assertTrue(execute(query)["viewer"].isNull)
    for ((user, session) in listOf(first to a, second to b, first to a)) {
      val viewer = execute(query, session)["viewer"]
      assertEquals(GlobalIdCodec.encode(NodeType.User, user.id.value), viewer["user"]["id"].asText())
      assertEquals("USER", viewer["user"]["roles"][0].asText())
      assertEquals("Passkey", viewer["passkeys"]["edges"][0]["node"]["label"].asText())
      assertEquals(true, viewer["sessions"]["edges"][0]["node"]["current"].asBoolean())
      assertFalse(viewer.toString().contains(user.credentials.single().value))
      assertEquals("User", execute("""{ node(id: "${GlobalIdCodec.encode(NodeType.User, user.id.value)}") { __typename id } }""", session)["node"]["__typename"].asText())
    }
    assertEquals("FORBIDDEN", error("""{ node(id: "${GlobalIdCodec.encode(NodeType.User, first.id.value)}") { id } }""", b))
    assertEquals("FORBIDDEN", error("{ crawlStatuses { totalCount } }", a))
    assertEquals("FORBIDDEN", error("{ auditEvents { totalCount } }", a))
    val passkeyType = execute("""{ __type(name: "Passkey") { fields { name } } }""")["__type"]
    val sessionType = execute("""{ __type(name: "Session") { fields { name } } }""")["__type"]
    assertEquals(setOf("id", "label", "createdAt", "lastUsedAt"), passkeyType["fields"].toList().map { it["name"].asText() }.toSet())
    assertEquals(setOf("id", "createdAt", "expiresAt", "current"), sessionType["fields"].toList().map { it["name"].asText() }.toSet())
  }

  @Test fun `Passkey rename is idempotent scoped recent and echoes the optional client mutation id`() {
    val (user, session) = account()
    val (_, other) = account()
    val id = execute("{ viewer { passkeys { edges { node { id } } } } }", session)["viewer"]["passkeys"]["edges"][0]["node"]["id"].asText()
    val key = UUID.randomUUID()
    fun rename(label: String, selected: MockHttpSession = session, requestKey: String = key.toString()) = execute("""mutation {
      renamePasskey(input: {passkeyId: "$id", label: "$label", idempotencyKey: "$requestKey", clientMutationId: "relay-7"}) {
        outcome clientMutationId error { code }
      } }""", selected)["renamePasskey"]
    assertEquals("CHANGED", rename("Laptop")["outcome"].asText())
    assertEquals("relay-7", rename("Laptop")["clientMutationId"].asText())
    assertEquals("IDEMPOTENCY_CONFLICT", rename("Other")["error"]["code"].asText())
    assertEquals("NOT_FOUND", rename("Other", other)["error"]["code"].asText())
    assertEquals("INVALID_INPUT", rename("Other", requestKey = "bad")["error"]["code"].asText())
    assertEquals("Laptop", tx.execute { it.credentials.findById(user.credentials.single())!! }.label)
    now = now.plusSeconds(301)
    assertEquals("FORBIDDEN", rename("Old", requestKey = UUID.randomUUID().toString())["error"]["code"].asText())
  }

  @Test fun `remove rotate and revoke enforce policies and secrets appear only once at rotation`() {
    val (user, session) = account()
    val viewer = execute("{ viewer { passkeys { edges { node { id } } } sessions { edges { node { id current } } } } }", session)["viewer"]
    val passkey = viewer["passkeys"]["edges"][0]["node"]["id"].asText()
    val current = viewer["sessions"]["edges"][0]["node"]["id"].asText()
    assertEquals("LAST_CREDENTIAL", execute("""mutation { removePasskey(input: {passkeyId: "$passkey", idempotencyKey: "${UUID.randomUUID()}"}) { error { code } } }""", session)["removePasskey"]["error"]["code"].asText())
    val key = UUID.randomUUID()
    val rotate = """mutation { rotateRecoveryCode(input: {idempotencyKey: "$key", clientMutationId: "r"}) { outcome recoveryCode clientMutationId error { code } } }"""
    val first = execute(rotate, session)["rotateRecoveryCode"]
    assertEquals("ROTATED", first["outcome"].asText())
    assertFalse(first["recoveryCode"].isNull)
    val replay = execute(rotate, session)["rotateRecoveryCode"]
    assertEquals("ALREADY_ROTATED", replay["outcome"].asText())
    assertTrue(replay["recoveryCode"].isNull)
    assertEquals("r", replay["clientMutationId"].asText())
    assertEquals("SIGNED_OUT", execute("""mutation { revokeSession(input: {sessionId: "$current", idempotencyKey: "${UUID.randomUUID()}"}) { outcome } }""", session)["revokeSession"]["outcome"].asText())
    assertTrue(execute("{ viewer { user { id } } }", session)["viewer"].isNull)
    assertEquals(1, tx.execute { it.credentials.findByUserId(user.id) }.size)
  }

  @Test fun `public crawl summaries batch while detailed history status and Node are admin only`() {
    val (_, admin) = account(admin = true)
    val sites = (1..6).map { site() }
    val runs = sites.map { site -> (1..3).map { run(site) } }
    val count = countSelects()
    val summary = execute("{ careerSites(first: 100) { edges { node { id crawlSummary { outcome finishedAt } } } } }")
    assertTrue(summary["careerSites"]["edges"].size() >= 6)
    assertTrue(count.get() <= 2, "Public page + summaries: ${count.get()}")
    val id = GlobalIdCodec.encode(NodeType.CareerSite, sites.first())
    assertEquals("FORBIDDEN", error("""{ node(id: "$id") { ... on CareerSite { crawlHistory { totalCount } } } }"""))
    count.set(0)
    val history = execute("{ careerSites(first: 100) { edges { node { crawlHistory(first: 1) { totalCount edges { node { id counts { fetched } } } pageInfo { hasNextPage endCursor } } } } } }", admin)
    assertTrue(history["careerSites"]["edges"].size() >= 6)
    assertTrue(count.get() <= 4, "Actor resolution + sites + one batched history: ${count.get()}")
    val first = execute("""{ node(id: "$id") { ... on CareerSite { crawlHistory(first: 1) { totalCount edges { node { id } } pageInfo { endCursor hasNextPage hasPreviousPage } } } } }""", admin)["node"]["crawlHistory"]
    assertEquals(3, first["totalCount"].asInt())
    assertEquals(GlobalIdCodec.encode(NodeType.CrawlRun, runs.first().last()), first["edges"][0]["node"]["id"].asText())
    val cursor = first["pageInfo"]["endCursor"].asText()
    val next = execute("""{ node(id: "$id") { ... on CareerSite { crawlHistory(first: 1, after: "$cursor") { edges { node { id } } pageInfo { hasPreviousPage } } } } }""", admin)["node"]["crawlHistory"]
    assertEquals(GlobalIdCodec.encode(NodeType.CrawlRun, runs.first()[1]), next["edges"][0]["node"]["id"].asText())
    assertTrue(next["pageInfo"]["hasPreviousPage"].asBoolean())
    val runId = GlobalIdCodec.encode(NodeType.CrawlRun, runs.first().last())
    assertEquals("CrawlRun", execute("""{ node(id: "$runId") { __typename ... on CrawlRun { startedAt } } }""", admin)["node"]["__typename"].asText())
    assertEquals("FORBIDDEN", error("""{ node(id: "$runId") { id } }"""))
    assertEquals("INVALID_PAGE", execute("{ crawlStatuses(first: 101) { error { code } } }", admin)["crawlStatuses"]["error"]["code"].asText())
    val neverCrawled = site()
    val statuses = execute("{ crawlStatuses(first: 100) { edges { node { careerSiteId runId outcome } } } }", admin)["crawlStatuses"]["edges"].toList()
    val populated = statuses.single { it["node"]["careerSiteId"].asText() == id }["node"]
    assertEquals(runId, populated["runId"].asText())
    assertEquals("SUCCESS", populated["outcome"].asText())
    val absent = statuses.single { it["node"]["careerSiteId"].asText() == GlobalIdCodec.encode(NodeType.CareerSite, neverCrawled) }["node"]
    assertTrue(absent["runId"].isNull && absent["outcome"].isNull)
    val otherId = GlobalIdCodec.encode(NodeType.CareerSite, sites[1])
    val mixed = execute("""{ good: node(id: "$id") { ... on CareerSite { crawlHistory(first: 1, after: "$cursor") { totalCount error { code } } } }
      bad: node(id: "$otherId") { ... on CareerSite { crawlHistory(first: 1, after: "$cursor") { error { code } } } } }""", admin)
    assertEquals(3, mixed["good"]["crawlHistory"]["totalCount"].asInt())
    assertEquals("INVALID_CURSOR", mixed["bad"]["crawlHistory"]["error"]["code"].asText())
  }

  @Test fun `audit page has stable tie cursors allowlisted details bounded count and Node refetch`() {
    val (user, admin) = account(admin = true)
    val ids = (1..3).map { UUID.randomUUID() }
    tx.execute { transaction ->
      ids.forEach { id -> transaction.auditLog.append(AuditEvent(id, 1, now,
        Actor.User(user.id.value, user.roles, now, AuthenticationStrength.PASSKEY), AuditAction.ROLE_GRANTED,
        "user", user.id.value.toString(), UUID.randomUUID(), UUID.randomUUID(), AuditOutcome.SUCCEEDED,
        AuditDetails.from(AuditAction.ROLE_GRANTED, mapOf("role" to "ADMIN")))) }
    }
    val count = countSelects()
    fun page(after: String = "") = execute("""{ auditEvents(filter: {actorUserId: "${GlobalIdCodec.encode(NodeType.User, user.id.value)}"}, first: 2 $after) {
      totalCount edges { node { id action details { role provider enabled } } } pageInfo { endCursor hasNextPage hasPreviousPage }
    } }""", admin)["auditEvents"]
    val first = page()
    assertEquals(3, first["totalCount"].asInt())
    assertEquals("ADMIN", first["edges"][0]["node"]["details"]["role"].asText())
    assertTrue(first["edges"][0]["node"]["details"]["provider"].isNull)
    assertTrue(count.get() <= 3, "Actor resolution + one bounded audit statement: ${count.get()}")
    val second = page(", after: \"${first["pageInfo"]["endCursor"].asText()}\"")
    assertEquals(1, second["edges"].size())
    assertTrue(second["pageInfo"]["hasPreviousPage"].asBoolean())
    assertEquals(3, (first["edges"].toList().map { it["node"]["id"].asText() } + second["edges"].toList().map { it["node"]["id"].asText() }).toSet().size)
    val id = first["edges"][0]["node"]["id"].asText()
    assertEquals("AuditEvent", execute("""{ node(id: "$id") { __typename } }""", admin)["node"]["__typename"].asText())
    assertEquals("INVALID_CURSOR", execute("{ auditEvents(after: \"bad\") { error { code } } }", admin)["auditEvents"]["error"]["code"].asText())
    assertEquals("INVALID_FILTER", execute("{ auditEvents(filter: {from: \"+300000-01-01T00:00:00Z\"}) { error { code } } }", admin)["auditEvents"]["error"]["code"].asText())
  }

  @ParameterizedTest
  @ValueSource(strings = ["bad", "djE6Q2FyZWVyU2l0ZTox"])
  fun `invalid audit actor IDs stay local before SQL and preserve valid siblings`(invalidId: String) {
    val (user, admin) = account(admin = true)
    val eventId = UUID.randomUUID()
    tx.execute { it.auditLog.append(AuditEvent(eventId, 1, now,
      Actor.User(user.id.value, user.roles, now, AuthenticationStrength.PASSKEY), AuditAction.ROLE_GRANTED,
      "user", user.id.value.toString(), UUID.randomUUID(), UUID.randomUUID(), AuditOutcome.SUCCEEDED,
      AuditDetails.from(AuditAction.ROLE_GRANTED, mapOf("role" to "ADMIN")))) }
    val userId = GlobalIdCodec.encode(NodeType.User, user.id.value)
    val selects = countSelects()
    val data = execute("""{
      viewer { user { id } }
      good: auditEvents(filter: {actorUserId: "$userId"}, first: 1) {
        totalCount edges { node { id } } error { code }
      }
      bad: auditEvents(filter: {actorUserId: "$invalidId"}) {
        totalCount edges { cursor } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } error { code }
      }
    }""", admin)
    assertEquals(userId, data["viewer"]["user"]["id"].asText())
    assertEquals(1, data["good"]["totalCount"].asInt())
    assertEquals(GlobalIdCodec.encode(NodeType.AuditEvent, eventId), data["good"]["edges"][0]["node"]["id"].asText())
    assertTrue(data["good"]["error"].isNull)
    val bad = data["bad"]
    assertEquals("INVALID_FILTER", bad["error"]["code"].asText())
    assertEquals(0, bad["totalCount"].asInt())
    assertEquals(0, bad["edges"].size())
    assertFalse(bad["pageInfo"]["hasNextPage"].asBoolean())
    assertFalse(bad["pageInfo"]["hasPreviousPage"].asBoolean())
    assertTrue(bad["pageInfo"]["startCursor"].isNull && bad["pageInfo"]["endCursor"].isNull)
    assertEquals(3, selects.get(), "Actor lookup plus only the valid audit key may execute SQL")
    selects.set(0)
    assertEquals("INVALID_FILTER", execute("""{ auditEvents(filter: {actorUserId: "$invalidId"}) { error { code } } }""", admin)["auditEvents"]["error"]["code"].asText())
    assertEquals(2, selects.get(), "An invalid-only query must execute Actor lookup but no audit SQL")
  }

  @Test fun `all management mutations retain selected-operation CSRF and typed admin payloads`() {
    val (_, admin) = account(admin = true)
    val input = """{careerSiteId: "${GlobalIdCodec.encode(NodeType.CareerSite, Long.MAX_VALUE)}", idempotencyKey: "${UUID.randomUUID()}", clientMutationId: "c"}"""
    val mutation = "mutation Private { triggerCrawl(input: $input) { outcome clientMutationId error { code } } } query Public { viewer { user { id } } }"
    mvc.perform(post("/graphql").session(admin).secure(true).contentType("application/json")
      .content(json.writeValueAsString(mapOf("query" to mutation, "operationName" to "Private")))).andExpect(status().isForbidden)
    val payload = execute("mutation { triggerCrawl(input: $input) { outcome clientMutationId error { code } } }", admin)["triggerCrawl"]
    assertEquals("NOT_FOUND", payload["outcome"].asText())
    assertEquals("c", payload["clientMutationId"].asText())
    val wrong = input.replace(GlobalIdCodec.encode(NodeType.CareerSite, Long.MAX_VALUE), GlobalIdCodec.encode(NodeType.CrawlRun, 1))
    assertEquals("INVALID_INPUT", execute("mutation { triggerCrawl(input: $wrong) { error { code } } }", admin)["triggerCrawl"]["error"]["code"].asText())
  }

  @Test fun `removed Passkey retries retain original success without exposing removed credential material`() {
    val (user, session) = account()
    val secondId = CredentialId(UUID.randomUUID().toString())
    tx.execute { t ->
      t.users.lockByEmail(user.email)
      t.credentials.insert(PasskeyCredential(user.id, PasskeyCredentialMaterial(secondId, byteArrayOf(1), 0, emptySet(), false, false), now, "Second"))
      t.users.save((user.registerCredential(secondId) as UserChange.Updated).user)
    }
    val passkeys = execute("{ viewer { passkeys { edges { node { id label } } } } }", session)["viewer"]["passkeys"]["edges"]
    val id = passkeys.toList().single { it["node"]["label"].asText() == "Second" }["node"]["id"].asText()
    val command = """mutation { removePasskey(input: {passkeyId: "$id", idempotencyKey: "${UUID.randomUUID()}", clientMutationId: "remove"}) { outcome clientMutationId error { code } } }"""
    assertEquals("CHANGED", execute(command, session)["removePasskey"]["outcome"].asText())
    assertNull(tx.execute { it.credentials.findById(secondId) })
    val replay = execute(command, session)["removePasskey"]
    assertEquals("CHANGED", replay["outcome"].asText())
    assertEquals("remove", replay["clientMutationId"].asText())
  }

  @Test fun `account metadata responses are never stored in a shared HTTP cache`() {
    val (_, session) = account()
    val pending = mvc.perform(post("/graphql").secure(true).session(session).contentType("application/json")
      .content("""{"query":"{ viewer { user { id } } }"}""")).andExpect(request().asyncStarted()).andReturn()
    mvc.perform(asyncDispatch(pending)).andExpect(header().string("Cache-Control", "no-store"))
  }

  @Test fun `account pages and aliases batch within a request and cursors cannot cross accounts or collections`() {
    val (_, session) = account()
    val (_, other) = account()
    val counter = countSelects()
    val data = execute("""{ a: viewer { passkeys(first: 1) { totalCount pageInfo { endCursor } }
      sessions(first: 1) { totalCount pageInfo { endCursor } } }
      b: viewer { passkeys(first: 1) { totalCount } sessions(first: 1) { totalCount } } }""", session)
    assertEquals(1, data["a"]["passkeys"]["totalCount"].asInt())
    assertEquals(1, data["b"]["sessions"]["totalCount"].asInt())
    assertTrue(counter.get() <= 4, "Actor once plus one statement per metadata family: ${counter.get()}")
    val cursor = data["a"]["passkeys"]["pageInfo"]["endCursor"].asText()
    assertEquals("INVALID_CURSOR", execute("""{ viewer { passkeys(after: "$cursor") { error { code } } } }""", other)["viewer"]["passkeys"]["error"]["code"].asText())
    assertEquals("INVALID_CURSOR", execute("""{ viewer { sessions(after: "$cursor") { error { code } } } }""", session)["viewer"]["sessions"]["error"]["code"].asText())
    val tail = execute("""{ viewer { passkeys(after: "$cursor") { totalCount edges { cursor } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } } }""", session)["viewer"]["passkeys"]
    assertEquals(1, tail["totalCount"].asInt())
    assertEquals(0, tail["edges"].size())
    assertFalse(tail["pageInfo"]["hasNextPage"].asBoolean())
    assertTrue(tail["pageInfo"]["hasPreviousPage"].asBoolean())
    assertTrue(tail["pageInfo"]["startCursor"].isNull && tail["pageInfo"]["endCursor"].isNull)
  }

  @Test fun `crawl and audit unexpected infrastructure errors stay sanitized and failure detail is never exposed`() {
    val (_, admin) = account(admin = true)
    val site = site()
    val id = (sql.fetchValue("""insert into crawl_runs(career_site_id,started_at,finished_at,outcome,failure_code,failure_message)
      values (?, ?::timestamptz, ?::timestamptz, 'FAILED', 'SOURCE_FETCH_FAILED', 'password=secret private-provider-host') returning id""",
      site, now.toString(), now.toString()) as Number).toLong()
    val result = execute("""{ node(id: "${GlobalIdCodec.encode(NodeType.CrawlRun, id)}") { ... on CrawlRun { outcome error { code message } } } }""", admin)["node"]
    assertEquals("CRAWL_FAILED", result["error"]["code"].asText())
    assertEquals("Crawl failed", result["error"]["message"].asText())
    assertFalse(result.toString().contains("secret"))
    sql.execute("alter table audit_events rename to unavailable_audit_events")
    try {
      val failure = response("{ auditEvents { totalCount } }", admin)["errors"][0]
      assertEquals("INTERNAL", failure["extensions"]["code"].asText())
      assertEquals("Request failed", failure["message"].asText())
      assertNotNull(UUID.fromString(failure["extensions"]["correlationId"].asText()))
    } finally { sql.execute("alter table unavailable_audit_events rename to audit_events") }
  }

  private fun account(admin: Boolean = false): Pair<User, MockHttpSession> {
    var user = seed(true)
    if (admin) user = tx.execute { t -> t.users.lockByEmail(user.email); (user.grantRole(UserRole.ADMIN) as UserChange.Updated).user.also(t.users::save) }
    val id = UserSessionId(UUID.randomUUID())
    tx.execute { it.users.lockByEmail(user.email); it.userSessions.save(UserSession(id, user.id, now, now.plusSeconds(3600), now)) }
    return user to MockHttpSession().apply { setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
      SecurityContextImpl(PasskeyAuthentication(PasskeyPrincipal(user.id, id)))) }
  }
  private fun site(): Long { val host = "${UUID.randomUUID()}.example"; return (sql.fetchValue("insert into career_sites(canonical_base_url,host,provider,display_name) values (?,?,'FLEX','Company') returning id", "https://$host", host) as Number).toLong() }
  private fun run(site: Long) = (sql.fetchValue("insert into crawl_runs(career_site_id,started_at,finished_at,outcome,fetched_count) values (?, ?::timestamptz, ?::timestamptz, 'SUCCESS', 7) returning id", site, now.toString(), now.toString()) as Number).toLong()
  private fun countSelects() = AtomicInteger().also { counter -> sql.configuration().set(DefaultExecuteListenerProvider(object : ExecuteListener {
    override fun executeStart(ctx: ExecuteContext) { if (ctx.sql()?.startsWith("select", true) == true) counter.incrementAndGet() }
  })) }
  private fun response(query: String, session: MockHttpSession? = null): tools.jackson.databind.JsonNode {
    val request = post("/graphql").secure(true).with(csrf()).contentType("application/json").content(json.writeValueAsString(mapOf("query" to query)))
    session?.let(request::session)
    val pending = mvc.perform(request).andExpect(request().asyncStarted()).andReturn()
    return json.readTree(mvc.perform(asyncDispatch(pending)).andExpect(status().isOk).andReturn().response.contentAsString)
  }
  private fun execute(query: String, session: MockHttpSession? = null): tools.jackson.databind.JsonNode {
    val result = response(query, session)
    assertNull(result["errors"], result.toString())
    return result["data"]
  }
  private fun error(query: String, session: MockHttpSession? = null) = response(query, session)["errors"][0]["extensions"]["code"].asText()
}
