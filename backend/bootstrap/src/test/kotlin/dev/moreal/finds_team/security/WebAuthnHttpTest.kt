package dev.moreal.finds_team.security

import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds_team.Application
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.context.annotation.Primary
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension::class)
class WebAuthnHttpTest {
  private lateinit var db: PostgreSQLContainer
  private lateinit var context: ConfigurableApplicationContext
  private lateinit var tx: TransactionPort
  private lateinit var mvc: MockMvc
  private val json = JsonMapper.builder().build()
  @BeforeAll fun start() {
    db = PostgreSQLContainer("postgres:17-alpine").apply { start() }
    context = SpringApplicationBuilder(Application::class.java, TestClock::class.java).run(
      "--spring.profiles.active=test", "--server.port=0", "--finds.mail.recording=true",
      "--spring.datasource.url=${db.jdbcUrl}", "--spring.datasource.username=${db.username}",
      "--spring.datasource.password=${db.password}", "--spring.flyway.user=${db.username}",
      "--spring.flyway.password=${db.password}", "--finds.crawl.scan-interval=1h",
      "--finds.security.admin-emails=Admin@Example.test", "--finds.security.cleanup-interval=1h",
    )
    tx = context.getBean(TransactionPort::class.java)
    mvc = MockMvcBuilders.webAppContextSetup(context as WebApplicationContext)
      .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
  }
  @AfterAll fun stop() { context.close(); db.close() }
  @BeforeEach fun resetTime() { now = Instant.parse("2026-09-23T00:00:00Z") }

  @Test fun `discoverable options require CSRF and require user verification`() {
    mvc.perform(post("/webauthn/authenticate/options").secure(true)).andExpect(status().isForbidden)
    val (options, _) = options()
    assertEquals("localhost", options["rpId"].asText())
    assertEquals(0, options["allowCredentials"].size())
    assertEquals("required", options["userVerification"].asText())
  }
  @Test fun `real signature logs in rotates session and advances persisted counter`() {
    val account = seed()
    val (options, session) = options()
    val oldId = session.id
    val assertion = account.key.assertion(options["challenge"].asText(), account.handle, count = 8)
    login(session, assertion).andExpect(status().isOk).andExpect(jsonPath("$.authenticated").value(true))
    mvc.perform(get("/auth/session").session(session).secure(true)).andExpect(status().isOk)
      .andExpect(jsonPath("$.userId").value(account.user.id.value.toString()))
      .andExpect(jsonPath("$.roles[0]").value("USER"))
      .andExpect(header().string("Cache-Control", "no-store"))
    assertNotEquals(oldId, session.id)
    tx.execute {
      assertEquals(8, it.credentials.findById(account.key.id)!!.material.signatureCount)
      assertEquals(now, it.credentials.findById(account.key.id)!!.lastUsedAt)
      assertEquals(1, it.userSessions.findByUserId(account.user.id).count { s -> s.isUsable(now) })
    }
    login(session, assertion).andExpect(status().isUnauthorized)
    assertEquals(1, tx.execute { it.userSessions.findByUserId(account.user.id).size })
  }
  @Test fun `cryptographic failures never change counters or create normal sessions`() {
    val account = seed()
    val cases = listOf<(String) -> Map<String, Any>>(
      { c -> account.key.assertion(c, account.handle, origin = "https://evil.example") },
      { c -> account.key.assertion(c, account.handle, rp = "evil.example") },
      { c -> account.key.assertion(c, ByteArray(16) { 99 }) },
      { c -> account.key.assertion(c, account.handle, flags = 1) },
      { c -> account.key.assertion(c, account.handle, count = 7) },
      { _ -> account.key.assertion(b64(ByteArray(32)), account.handle) },
      { c -> Fixture().assertion(c, account.handle) },
      { c -> account.key.assertion(c, account.handle, corruptSignature = true) },
    )
    cases.forEachIndexed { index, request ->
      val (options, session) = options()
      login(session, request(options["challenge"].asText())).andExpect(status().isUnauthorized)
      tx.execute {
        assertEquals(7, it.credentials.findById(account.key.id)!!.material.signatureCount, "case $index")
        assertTrue(it.userSessions.findByUserId(account.user.id).isEmpty(), "case $index")
      }
    }
  }
  @Test fun `expired challenge and challenge copied to another session fail`() {
    val account = seed()
    val (options, session) = options()
    val assertion = account.key.assertion(options["challenge"].asText(), account.handle)
    val other = MockHttpSession()
    session.attributeNames.toList().forEach { other.setAttribute(it, session.getAttribute(it)) }
    login(other, assertion).andExpect(status().isUnauthorized)
    now = now.plusSeconds(300)
    login(session, assertion).andExpect(status().isUnauthorized)
    assertTrue(tx.execute { it.userSessions.findByUserId(account.user.id).isEmpty() })
  }
  @Test fun `zero-only counter authenticators remain usable`() {
    val account = seed(counter = 0)
    repeat(2) {
      val (options, session) = options()
      login(session, account.key.assertion(options["challenge"].asText(), account.handle, count = 0)).andExpect(status().isOk)
    }
  }
  @Test fun `failed signature and completed challenge replay append categorical events without credential material`(output: CapturedOutput) {
    val account = seed()
    val (options, session) = options()
    val challenge = options["challenge"].asText()
    val database = context.getBean(org.jooq.DSLContext::class.java)
    val before = database.fetchValue("SELECT count(*)::int FROM security_events") as Int
    val failedId = UUID.randomUUID()
    login(session, account.key.assertion(challenge, account.handle, corruptSignature = true), failedId).andExpect(status().isUnauthorized)
    assertEquals(before + 1, database.fetchValue("SELECT count(*)::int FROM security_events"))
    assertEquals("{\"reason\": \"AUTHENTICATION_FAILED\"}", database.fetchValue("SELECT details::text FROM security_events WHERE request_id = ?", failedId))
    now = now.plusSeconds(1)
    val assertion = account.key.assertion(challenge, account.handle)
    login(session, assertion).andExpect(status().isOk)
    val replayId = UUID.randomUUID()
    login(session, assertion, replayId).andExpect(status().isUnauthorized)
    assertEquals(before + 2, database.fetchValue("SELECT count(*)::int FROM security_events"))
    assertEquals("{\"reason\": \"CHALLENGE_REPLAY\"}", database.fetchValue("SELECT details::text FROM security_events WHERE request_id = ?", replayId))
    val events = database.fetch("SELECT row_to_json(s)::text FROM security_events s").joinToString()
    val normalSessions = tx.execute { it.userSessions.findByUserId(account.user.id) }
    // MockHttpSession uses short numeric IDs that can coincidentally occur in any UUID. Inspect
    // the actual persisted secret session ID, not that test harness counter.
    for (value in listOf(challenge, account.key.id.value, account.user.email.value, normalSessions.single().id.value.toString(), b64(account.key.cose))) {
      assertFalse(events.contains(value)); assertFalse(output.all.contains(value))
    }
    assertEquals(1, normalSessions.size)
  }
  @Test fun `registration requires live restricted binding and validates attestation`() {
    mvc.perform(post("/webauthn/register/options").secure(true).with(csrf())).andExpect(status().isUnauthorized)
    val account = seed(pending = true, email = "Admin@Example.test")
    val session = restricted(account.user)
    val options = registerOptions(session)
    assertEquals("required", options["authenticatorSelection"]["userVerification"].asText())
    assertEquals("required", options["authenticatorSelection"]["residentKey"].asText())
    assertEquals(b64(account.handle), options["user"]["id"].asText())
    val response = register(session, account.key.registration(options["challenge"].asText()))
      .andExpect(status().isOk).andExpect(jsonPath("$.recoveryCode").isString).andReturn()
    assertFalse(response.response.contentAsString.contains(account.user.email.value))
    tx.execute {
      assertEquals(UserStatus.ACTIVE, it.users.findById(account.user.id)!!.status)
      assertEquals(setOf(UserRole.USER, UserRole.ADMIN), it.users.findById(account.user.id)!!.roles)
      assertNotNull(it.credentials.findById(account.key.id))
      assertTrue(it.userSessions.findByUserId(account.user.id).isEmpty())
    }
    register(session, account.key.registration(options["challenge"].asText())).andExpect(status().isUnauthorized)
  }
  @Test fun `registration rejects wrong RP origin UV and expired restricted session`() {
    val cases = listOf<(Fixture, String) -> Map<String, Any>>(
      { key, c -> key.registration(c, origin = "https://evil.example") },
      { key, c -> key.registration(c, rp = "evil.example") },
      { key, c -> key.registration(c, flags = 65) },
    )
    cases.forEach { request ->
      val account = seed(pending = true)
      val session = restricted(account.user)
      val options = registerOptions(session)
      register(session, request(account.key, options["challenge"].asText())).andExpect(status().isUnauthorized)
      assertEquals(UserStatus.PENDING_PASSKEY, tx.execute { it.users.findById(account.user.id)!!.status })
    }
    val account = seed(pending = true)
    val session = restricted(account.user)
    now = now.plusSeconds(600)
    mvc.perform(post("/webauthn/register/options").session(session).secure(true).with(csrf())).andExpect(status().isUnauthorized)
  }
  @Test fun `logout requires CSRF atomically audits once and repeats generically after lost response`(output: CapturedOutput) {
    val account = seed()
    val (options, session) = options()
    login(session, account.key.assertion(options["challenge"].asText(), account.handle)).andExpect(status().isOk)
    val sessionId = tx.execute { it.userSessions.findByUserId(account.user.id).single().id }
    val sql = context.getBean(org.jooq.DSLContext::class.java)
    mvc.perform(post("/auth/logout").session(session).secure(true)).andExpect(status().isForbidden)
    mvc.perform(post("/auth/logout").session(session).secure(true).with(csrf())).andExpect(status().isNoContent)
    assertTrue(session.isInvalid)
    assertTrue(tx.execute { it.userSessions.findByUserId(account.user.id).none { s -> s.isUsable(now) } })
    assertEquals(1, sql.fetchValue("SELECT count(*)::int FROM audit_events WHERE actor_user_id = ? AND action = 'session.revoked'", account.user.id.value))
    val audit = sql.fetchValue("SELECT row_to_json(a)::text FROM audit_events a WHERE actor_user_id = ? AND action = 'session.revoked'", account.user.id.value).toString()
    val result = sql.fetchValue("SELECT result::text FROM command_requests WHERE scope = ? AND operation = 'session.logout'", account.user.id.value.toString()).toString()
    assertTrue(result.contains("SIGNED_OUT"))
    assertFalse(audit.contains(sessionId.value.toString()))
    assertFalse(result.contains(sessionId.value.toString()))
    assertFalse(output.all.contains(sessionId.value.toString()))
    // A lost response can leave the old cookie on the browser. The server session is gone;
    // an anonymous repeat with a fresh CSRF token must keep the same generic outcome.
    repeat(2) {
      mvc.perform(post("/auth/logout").secure(true).with(csrf())).andExpect(status().isNoContent)
    }
    assertEquals(1, sql.fetchValue("SELECT count(*)::int FROM audit_events WHERE actor_user_id = ? AND action = 'session.revoked'", account.user.id.value))
  }
  @Test fun `logout audit failure preserves both sessions and allows same request retry`(output: CapturedOutput) {
    val account = seed()
    val (options, session) = options()
    login(session, account.key.assertion(options["challenge"].asText(), account.handle)).andExpect(status().isOk)
    val sql = context.getBean(org.jooq.DSLContext::class.java)
    val sessionId = tx.execute { it.userSessions.findByUserId(account.user.id).single().id }
    sql.execute("ALTER TABLE audit_events ADD CONSTRAINT reject_logout CHECK (action <> 'session.revoked')")
    try {
      assertFails { mvc.perform(post("/auth/logout").session(session).secure(true).with(csrf())) }
      assertFalse(session.isInvalid)
      assertTrue(tx.execute { it.userSessions.findById(sessionId)!!.isUsable(now) })
      assertEquals(0, sql.fetchValue("SELECT count(*)::int FROM command_requests WHERE scope = ? AND operation = 'session.logout'", account.user.id.value.toString()))
      assertEquals(0, sql.fetchValue("SELECT count(*)::int FROM audit_events WHERE actor_user_id = ? AND action = 'session.revoked'", account.user.id.value))
    } finally { sql.execute("ALTER TABLE audit_events DROP CONSTRAINT reject_logout") }
    mvc.perform(post("/auth/logout").session(session).secure(true).with(csrf())).andExpect(status().isNoContent)
    assertTrue(session.isInvalid)
    assertEquals(1, sql.fetchValue("SELECT count(*)::int FROM audit_events WHERE actor_user_id = ? AND action = 'session.revoked'", account.user.id.value))
    assertFalse(output.all.contains(sessionId.value.toString()))
  }
  @Test fun `public GraphQL queries work without CSRF while mutations remain closed`() {
    val query = mvc.perform(post("/graphql").contentType("application/json")
      .content("""{"query":"{ jobPostings { totalCount } }"}"""))
      .andExpect(request().asyncStarted()).andReturn()
    mvc.perform(asyncDispatch(query)).andExpect(status().isOk).andExpect(jsonPath("$.data.jobPostings.totalCount").value(0))
    mvc.perform(post("/graphql").contentType("application/json")
      .content("""{"query":"mutation { triggerCrawl(careerSiteId: \"1\") { outcome } }"}"""))
      .andExpect(status().isForbidden)
  }
  @Test fun `registration uses live Passkey administrator identity and replays reordered GraphQL input`() {
    discoveries.set(0)
    val key = UUID.randomUUID().toString()
    val host = "jobs-${UUID.randomUUID()}.example.test"
    val mutation = "mutation Register(\$input: RegisterCareerSiteInput!) { registerCareerSite(input: \$input) { site { id } error { code } } }"
    fun register(session: MockHttpSession? = null, name: String = "Acme", reverse: Boolean = false): JsonNode {
      val input = if (reverse) linkedMapOf("idempotencyKey" to key, "displayName" to name, "url" to "https://$host")
        else linkedMapOf("url" to "https://$host", "displayName" to name, "idempotencyKey" to key)
      val builder = post("/graphql").secure(true).with(csrf()).contentType("application/json")
        .content(json.writeValueAsString(mapOf("query" to mutation, "variables" to mapOf("input" to input))))
      if (session != null) builder.session(session)
      val pending = mvc.perform(builder).andExpect(request().asyncStarted()).andReturn()
      val response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk).andReturn().response
      return json.readTree(response.contentAsString)["data"]["registerCareerSite"]
    }
    assertEquals("FORBIDDEN", register()["error"]["code"].asText())
    val account = seed()
    val (options, session) = options()
    login(session, account.key.assertion(options["challenge"].asText(), account.handle)).andExpect(status().isOk)
    assertEquals("FORBIDDEN", register(session)["error"]["code"].asText())
    assertEquals(0, discoveries.get())
    tx.execute {
      val live = it.users.lockByEmail(account.user.email)!!
      it.users.save(assertIs<UserChange.Updated>(live.grantRole(UserRole.ADMIN)).user)
    }
    val requestBody = json.writeValueAsString(mapOf("query" to mutation, "variables" to mapOf("input" to
      mapOf("url" to "https://$host", "displayName" to "Acme", "idempotencyKey" to key))))
    mvc.perform(post("/graphql").session(session).secure(true).contentType("application/json").content(requestBody))
      .andExpect(status().isForbidden)
    mvc.perform(post("/graphql").session(session).secure(true).with(csrf().useInvalidToken())
      .contentType("application/json").content(requestBody)).andExpect(status().isForbidden)
    assertEquals(0, discoveries.get())
    val first = register(session)
    assertTrue(first["error"].isNull)
    val siteId = first["site"]["id"].asText()
    assertEquals(first, register(session, reverse = true))
    assertEquals("IDEMPOTENCY_CONFLICT", register(session, "Changed")["error"]["code"].asText())
    assertEquals(1, discoveries.get())
    val database = context.getBean(org.jooq.DSLContext::class.java)
    assertEquals(1, database.fetchValue("SELECT count(*)::int FROM audit_events WHERE action = 'career_site.registered' AND target_id = ?", siteId))
    now = now.plusSeconds(301)
    assertEquals("FORBIDDEN", register(session)["error"]["code"].asText())
    now = now.minusSeconds(301)
    tx.execute { it.users.lockByEmail(account.user.email); it.userSessions.revokeForUser(account.user.id, now) }
    assertEquals("FORBIDDEN", register(session)["error"]["code"].asText())
    assertEquals(1, discoveries.get())
  }
  @Test fun `empty GraphQL operation names cannot bypass administrator mutation CSRF`() {
    val session = recentAdministratorSession()
    discoveries.set(0)
    val database = context.getBean(org.jooq.DSLContext::class.java)
    fun counts() = listOf("career_sites", "crawl_runs", "audit_events", "command_requests").map {
      database.fetchValue("SELECT count(*)::int FROM $it")
    }
    val before = counts()
    val statuses = mutableListOf<Int>()
    for (crawl in listOf(false, true)) for (multiple in listOf(false, true)) for (invalidCsrf in listOf(false, true)) {
      val input = """{url: "https://jobs-${UUID.randomUUID()}.example.test", displayName: "Acme", idempotencyKey: "${UUID.randomUUID()}"}"""
      val query = if (crawl)
        "mutation Private { ...Crawl } ${if (multiple) "query Public { __typename }" else ""} fragment Crawl on Mutation { aliased: triggerCrawl(careerSiteId: \"1\", idempotencyKey: \"${UUID.randomUUID()}\") { runId } }"
        else if (multiple)
        "mutation Private { ...Registration } query Public { __typename } fragment Registration on Mutation { aliased: registerCareerSite(input: $input) { site { id } } }"
        else "mutation { registerCareerSite(input: $input) { site { id } } }"
      val builder = post("/graphql").session(session).secure(true).contentType("application/json")
        .content(json.writeValueAsString(mapOf("query" to query, "operationName" to "")))
      if (invalidCsrf) builder.with(csrf().useInvalidToken())
      val pending = mvc.perform(builder).andReturn()
      val response = if (pending.request.isAsyncStarted) mvc.perform(asyncDispatch(pending)).andReturn().response else pending.response
      statuses += response.status
    }
    assertEquals(List(8) { 400 }, statuses)
    assertEquals(0, discoveries.get())
    assertEquals(before, counts(), "Rejected operation names cannot create a site, audit event or command result")
  }

  @Test fun `selected administrator mutation CSRF denial records one event and remains forbidden when event storage fails`(output: CapturedOutput) {
    val session = recentAdministratorSession()
    discoveries.set(0)
    val database = context.getBean(org.jooq.DSLContext::class.java)
    val metrics = context.getBean(io.micrometer.core.instrument.MeterRegistry::class.java)
    val counter = metrics.counter("finds.security.events", "outcome", "write_failed", "action", "identity.authorization_denied")
    val initialFailures = counter.count()
    val tables = listOf("career_sites", "crawl_runs", "audit_events", "command_requests")
    val counts = tables.associateWith { database.fetchValue("SELECT count(*)::int FROM $it") }
    val query = """mutation Private { ...Register } fragment Register on Mutation { aliased: registerCareerSite(input: {url: "https://csrf-private.example.test", displayName: "Private", idempotencyKey: "${UUID.randomUUID()}"}) { error { code } } } query Public { __typename }"""
    val body = json.writeValueAsString(mapOf("query" to query, "operationName" to "Private"))
    for (unavailable in listOf(false, true)) {
      if (unavailable) database.execute("ALTER TABLE security_events ADD CONSTRAINT reject_graphql_csrf CHECK (false) NOT VALID")
      try {
        for (invalid in listOf(false, true)) {
          val requestId = UUID.randomUUID()
          val request = post("/graphql").session(session).secure(true).header("X-Request-ID", requestId)
            .contentType("application/json").content(body)
          if (invalid) request.with(csrf().useInvalidToken())
          mvc.perform(request).andExpect(status().isForbidden)
          val rows = database.fetch("SELECT action, details::text FROM security_events WHERE request_id = ?", requestId)
          assertEquals(if (unavailable) 0 else 1, rows.size)
          if (!unavailable) {
            assertEquals("identity.authorization_denied", rows.single().get(0))
            assertEquals("{\"reason\": \"FORBIDDEN\"}", rows.single().get(1))
          }
          assertEquals(counts, tables.associateWith { database.fetchValue("SELECT count(*)::int FROM $it") })
          assertEquals(0, discoveries.get())
        }
      } finally { if (unavailable) database.execute("ALTER TABLE security_events DROP CONSTRAINT reject_graphql_csrf") }
    }
    assertEquals(initialFailures + 2, counter.count())
    assertFalse(output.all.contains("reject_graphql_csrf"))
    assertFalse(output.all.contains("csrf-private.example.test"))
  }

  @Test fun `manual crawl binds live admin and key enforces CSRF and records denials separately`() {
    val site = tx.execute { assertIs<InsertCareerSiteResult.Inserted>(it.careerSites.insert(
      dev.moreal.finds.application.model.NewCareerSite(
        assertIs<dev.moreal.finds.domain.career.SiteUrlResult.Valid>(dev.moreal.finds.domain.career.SiteUrl.parse(
          "https://crawl-${UUID.randomUUID()}.example.test")).url,
        dev.moreal.finds.domain.career.SourceProvider.FLEX, "Crawl"))).site }
    val database = context.getBean(org.jooq.DSLContext::class.java)
    val key = UUID.randomUUID()
    val query = """mutation Crawl { ...Trigger } fragment Trigger on Mutation { aliased: triggerCrawl(careerSiteId: "${site.id.value}", idempotencyKey: "$key") { runId outcome error { code } } }"""
    val body = json.writeValueAsString(mapOf("query" to query))
    val beforeEvents = database.fetchValue("SELECT count(*)::int FROM security_events WHERE action = 'crawl.trigger_denied'") as Int
    fun trigger(session: MockHttpSession?): JsonNode {
      val builder = post("/graphql").secure(true).with(csrf()).contentType("application/json").content(body)
      if (session != null) builder.session(session)
      val pending = mvc.perform(builder).andExpect(request().asyncStarted()).andReturn()
      return json.readTree(mvc.perform(asyncDispatch(pending)).andExpect(status().isOk).andReturn().response.contentAsString)["data"]["aliased"]
    }
    fetches.set(0)
    assertEquals("FORBIDDEN", trigger(null)["outcome"].asText())
    val account = seed()
    val (options, session) = options()
    login(session, account.key.assertion(options["challenge"].asText(), account.handle)).andExpect(status().isOk)
    assertEquals("FORBIDDEN", trigger(session)["outcome"].asText())
    tx.execute {
      val live = it.users.lockByEmail(account.user.email)!!
      it.users.save(assertIs<UserChange.Updated>(live.grantRole(UserRole.ADMIN)).user)
    }
    mvc.perform(post("/graphql").session(session).secure(true).contentType("application/json").content(body))
      .andExpect(status().isForbidden)
    mvc.perform(post("/graphql").session(session).secure(true).with(csrf().useInvalidToken()).contentType("application/json").content(body))
      .andExpect(status().isForbidden)
    assertEquals(0, fetches.get())
    val first = trigger(session)
    assertEquals("TRIGGERED", first["outcome"].asText())
    assertEquals(first, trigger(session))
    assertEquals(1, fetches.get())
    val runId = first["runId"].asText().toLong()
    assertEquals("SUCCESS", database.fetchValue("SELECT outcome FROM crawl_runs WHERE id = ?", runId))
    assertEquals(1, database.fetchValue("SELECT count(*)::int FROM audit_events WHERE action = 'crawl.manually_triggered' AND target_id = ?", runId.toString()))
    now = now.plusSeconds(301)
    assertEquals("FORBIDDEN", trigger(session)["outcome"].asText())
    now = now.minusSeconds(301)
    tx.execute { it.users.lockByEmail(account.user.email); it.userSessions.revokeForUser(account.user.id, now) }
    assertEquals("FORBIDDEN", trigger(session)["outcome"].asText())
    assertEquals(1, fetches.get())
    assertEquals(beforeEvents + 4, database.fetchValue("SELECT count(*)::int FROM security_events WHERE action = 'crawl.trigger_denied'"))
    assertEquals(1, database.fetchValue("SELECT count(*)::int FROM crawl_runs WHERE career_site_id = ?", site.id.value))
  }

  @Test fun `null omitted and selected GraphQL operation names preserve CSRF and public queries`() {
    val session = recentAdministratorSession()
    discoveries.set(0)
    val input = """{url: "https://jobs-${UUID.randomUUID()}.example.test", displayName: "Acme", idempotencyKey: "${UUID.randomUUID()}"}"""
    val mutation = "mutation Private { ...Registration } fragment Registration on Mutation { aliased: registerCareerSite(input: $input) { site { id } } }"
    val multiple = "$mutation query Public { __typename }"
    fun body(query: String, explicitNull: Boolean) = json.writeValueAsString(
      mutableMapOf<String, Any?>("query" to query).apply { if (explicitNull) put("operationName", null) })
    for (explicitNull in listOf(false, true)) {
      mvc.perform(post("/graphql").session(session).secure(true).contentType("application/json")
        .content(body(mutation, explicitNull))).andExpect(status().isForbidden)
      mvc.perform(post("/graphql").session(session).secure(true).with(csrf().useInvalidToken()).contentType("application/json")
        .content(body(mutation, explicitNull))).andExpect(status().isForbidden)
      val ambiguous = mvc.perform(post("/graphql").session(session).secure(true).contentType("application/json")
        .content(body(multiple, explicitNull))).andExpect(request().asyncStarted()).andReturn()
      mvc.perform(asyncDispatch(ambiguous)).andExpect(status().isOk).andExpect(jsonPath("$.errors").isArray)
        .andExpect(jsonPath("$.data").doesNotExist())
      val public = mvc.perform(post("/graphql").session(session).secure(true).contentType("application/json")
        .content(body("{ __typename }", explicitNull))).andExpect(request().asyncStarted()).andReturn()
      mvc.perform(asyncDispatch(public)).andExpect(status().isOk).andExpect(jsonPath("$.data.__typename").value("Query"))
    }
    val selected = mvc.perform(post("/graphql").session(session).secure(true).contentType("application/json")
      .content(json.writeValueAsString(mapOf("query" to multiple, "operationName" to "Public"))))
      .andExpect(request().asyncStarted()).andReturn()
    mvc.perform(asyncDispatch(selected)).andExpect(status().isOk).andExpect(jsonPath("$.data.__typename").value("Query"))
    assertEquals(0, discoveries.get())
  }

  @Test fun `real server cookie is secure HttpOnly SameSite Lax and csrf bootstrap is no-store`() {
    val port = context.environment.getProperty("local.server.port")
    val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://localhost:$port/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(200, response.statusCode())
    val cookie = response.headers().firstValue("set-cookie").orElseThrow()
    assertContains(cookie, "Secure"); assertContains(cookie, "HttpOnly"); assertContains(cookie, "SameSite=Lax")
    assertContains(response.headers().firstValue("cache-control").orElseThrow(), "no-store")
    assertTrue(json.readTree(response.body())["token"].asText().isNotBlank())
  }
  @Test fun `real servlet error dispatch preserves forbidden GraphQL mutation status`() {
    val port = context.environment.getProperty("local.server.port")
    val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://localhost:$port/graphql"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString("""{"query":"mutation { triggerCrawl(careerSiteId: \"1\") { outcome } }"}"""))
      .build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(403, response.statusCode())
  }
  @Test fun `completed registration retries are secret-free and bound to original command and request`() {
    val account = seed(pending = true)
    val session = restricted(account.user)
    val options = registerOptions(session)
    val body = account.key.registration(options["challenge"].asText())
    val key = UUID.randomUUID()
    register(session, body, key).andExpect(status().isOk).andExpect(jsonPath("$.recoveryCode").isString)
    register(session, body, key).andExpect(status().isOk).andExpect(jsonPath("$.recoveryCode").doesNotExist())
    val otherSession = MockHttpSession()
    session.attributeNames.toList().forEach { otherSession.setAttribute(it, session.getAttribute(it)) }
    register(otherSession, body, key).andExpect(status().isUnauthorized)
    val changed = account.key.registration(b64(ByteArray(32)))
    register(session, changed, key).andExpect(status().isConflict)
    register(session, body).andExpect(status().isUnauthorized)
    now = now.plusSeconds(87000)
    register(session, body, key).andExpect(status().isUnauthorized)
  }
  @Test fun `ceremony request parsing is bounded and malformed JSON is a client error`() {
    mvc.perform(post("/login/webauthn").secure(true).with(csrf()).contentType("application/json").content("x".repeat(65537)))
      .andExpect(status().`is`(413))
    mvc.perform(post("/login/webauthn").secure(true).with(csrf()).contentType("application/json").content("{}"))
      .andExpect(status().isBadRequest)
  }
  @Test fun `JSON null credentials are malformed problem details rather than server errors`() {
    listOf("/login/webauthn" to "null", "/webauthn/register" to "null", "/webauthn/register" to """{"publicKey":null}""").forEach { (path, body) ->
      mvc.perform(post(path).secure(true).with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isBadRequest).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
    }
  }
  @Test fun `recovery plaintext never enters Spring response diagnostics`(output: CapturedOutput) {
    val account = seed(pending = true)
    val session = restricted(account.user)
    val options = registerOptions(session)
    val logger = org.slf4j.LoggerFactory.getLogger("org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor") as ch.qos.logback.classic.Logger
    val before = logger.level
    logger.level = ch.qos.logback.classic.Level.DEBUG
    try {
      val response = register(session, account.key.registration(options["challenge"].asText())).andExpect(status().isOk).andReturn()
      val recovery = json.readTree(response.response.contentAsString)["recoveryCode"].asText()
      assertFalse(output.out.contains(recovery))
    } finally { logger.level = before }
  }
  @Test fun `Actor reloads roles and rejects expired revoked suspended or restricted sessions`() {
    val account = seed()
    val (options, session) = options()
    login(session, account.key.assertion(options["challenge"].asText(), account.handle)).andExpect(status().isOk)
    val authentication = (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext).authentication
    val actors = context.getBean(ActorResolver::class.java)
    assertEquals(account.user.id.value, actors.resolve(authentication)!!.userId)
    assertFalse(actors.isRecentAdministrator(authentication))
    tx.execute {
      val current = it.users.lockByEmail(account.user.email)!!
      it.users.save((current.grantRole(UserRole.ADMIN) as UserChange.Updated).user)
    }
    assertTrue(actors.isRecentAdministrator(authentication))
    now = now.plusSeconds(301)
    assertFalse(actors.isRecentAdministrator(authentication))
    assertNotNull(actors.resolve(authentication))
    tx.execute { it.users.lockByEmail(account.user.email); it.userSessions.revokeForUser(account.user.id, now) }
    assertNull(actors.resolve(authentication))
    mvc.perform(get("/auth/session").session(session).secure(true)).andExpect(status().isUnauthorized)
    val (newOptions, newSession) = options()
    login(newSession, account.key.assertion(newOptions["challenge"].asText(), account.handle, count = 9)).andExpect(status().isOk)
    val newAuthentication = (newSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext).authentication
    tx.execute {
      val user = it.users.lockByEmail(account.user.email)!!
      it.users.save(User(user.id, user.email, UserStatus.SUSPENDED, user.roles, user.credentials))
    }
    assertNull(actors.resolve(newAuthentication))
    tx.execute {
      val user = it.users.lockByEmail(account.user.email)!!
      it.users.save(User(user.id, user.email, UserStatus.ACTIVE, user.roles, user.credentials))
    }
    now = now.plusSeconds(43200)
    assertNull(actors.resolve(newAuthentication))
    val restricted = restricted(seed(pending = true).user)
    mvc.perform(get("/auth/session").session(restricted).secure(true)).andExpect(status().isUnauthorized)
    assertNull(actors.resolve(null))
  }
  @Test fun `parallel assertion replay has one persisted winner`() {
    val account = seed()
    val (options, session) = options()
    val assertion = account.key.assertion(options["challenge"].asText(), account.handle)
    val threads = Executors.newFixedThreadPool(2)
    try {
      val futures = (1..2).map { threads.submit<Int> { login(session, assertion).andReturn().response.status } }
      assertEquals(listOf(200, 401), futures.map { it.get(10, TimeUnit.SECONDS) }.sorted())
      assertEquals(1, tx.execute { it.userSessions.findByUserId(account.user.id).size })
      assertEquals(8, tx.execute { it.credentials.findById(account.key.id)!!.material.signatureCount })
    } finally { threads.shutdownNow() }
  }
  @Test fun `bounded scheduled cleanup preserves restricted replay tombstones`() {
    val account = seed(pending = true)
    val retained = RestrictedSession(RestrictedSessionId(UUID.randomUUID()), account.user.id, RestrictedSessionScope.ENROLLMENT,
      now.minusSeconds(700), now.minusSeconds(100), now.minusSeconds(200))
    val old = (1..102).map { RestrictedSession(RestrictedSessionId(UUID.randomUUID()), account.user.id, RestrictedSessionScope.ENROLLMENT,
      now.minusSeconds(88000), now.minusSeconds(87000)) }
    val oldChallenge = WebAuthnChallenge(UUID.randomUUID(), WebAuthnChallengePurpose.AUTHENTICATION, "localhost",
      KeyedIdentityHash(1, ByteArray(32)), KeyedIdentityHash(1, ByteArray(32)), null, null, now.minusSeconds(88000), now.minusSeconds(87000))
    val liveChallenge = oldChallenge.copy(id = UUID.randomUUID(), createdAt = now, expiresAt = now.plusSeconds(300))
    tx.execute {
      it.users.lockByEmail(account.user.email); it.restrictedSessions.save(retained); old.forEach(it.restrictedSessions::save)
      it.webauthnChallenges.save(oldChallenge); it.webauthnChallenges.save(liveChallenge)
    }
    val cleanup = context.getBean(RestrictedSessionCleanup::class.java)
    assertEquals(100, cleanup.purge())
    assertNotNull(tx.execute { it.restrictedSessions.findById(retained.id) })
    assertNull(tx.execute { it.webauthnChallenges.findById(oldChallenge.id) })
    assertNotNull(tx.execute { it.webauthnChallenges.findById(liveChallenge.id) })
    assertEquals(2, cleanup.purge())
    assertNotNull(tx.execute { it.restrictedSessions.findById(retained.id) })
  }
  private fun options(): Pair<JsonNode, MockHttpSession> {
    val result = mvc.perform(post("/webauthn/authenticate/options").secure(true).with(csrf())).andExpect(status().isOk).andReturn()
    return json.readTree(result.response.contentAsString) to (result.request.session as MockHttpSession)
  }
  private fun login(session: MockHttpSession, body: Map<String, Any>, requestId: UUID = UUID.randomUUID()) = mvc.perform(post("/login/webauthn").secure(true)
    .session(session).with(csrf()).header("X-Request-ID", requestId).contentType("application/json").content(json.writeValueAsString(body)))
  private fun registerOptions(session: MockHttpSession): JsonNode = json.readTree(mvc.perform(post("/webauthn/register/options")
    .secure(true).session(session).with(csrf())).andExpect(status().isOk).andReturn().response.contentAsString)
  private fun register(session: MockHttpSession, body: Map<String, Any>, key: UUID = UUID.randomUUID()) = mvc.perform(post("/webauthn/register").secure(true)
    .session(session).with(csrf()).header("Idempotency-Key", key).contentType("application/json").content(json.writeValueAsString(body)))
  private fun restricted(user: User): MockHttpSession {
    val restricted = RestrictedSession(RestrictedSessionId(UUID.randomUUID()), user.id, RestrictedSessionScope.ENROLLMENT, now, now.plusSeconds(600))
    tx.execute { it.users.lockByEmail(user.email); it.restrictedSessions.save(restricted) }
    return MockHttpSession().apply { setAttribute("finds.restricted-session", restricted.id) }
  }
  private data class Account(val user: User, val key: Fixture, val handle: ByteArray)
  private fun recentAdministratorSession(): MockHttpSession {
    val account = seed()
    tx.execute {
      val user = it.users.lockByEmail(account.user.email)!!
      it.users.save(assertIs<UserChange.Updated>(user.grantRole(UserRole.ADMIN)).user)
    }
    val (options, session) = options()
    login(session, account.key.assertion(options["challenge"].asText(), account.handle)).andExpect(status().isOk)
    return session
  }
  private fun seed(pending: Boolean = false, counter: Long = 7, email: String = "${UUID.randomUUID()}@example.test"): Account {
    val key = Fixture()
    val user = User(UserId(UUID.randomUUID()), EmailAddress(email))
    return tx.execute {
      it.users.lockByEmail(user.email); it.users.save(user)
      if (!pending) {
        it.credentials.insert(PasskeyCredential(user.id, PasskeyCredentialMaterial(key.id, key.cose, counter, setOf("internal"), false, false), now))
        it.users.save((user.registerCredential(key.id) as UserChange.Updated).user)
      }
      Account(user, key, it.users.findUserHandle(user.id)!!)
    }
  }
  @TestConfiguration(proxyBeanMethods = false) class TestClock {
    @Bean @Primary fun testClock() = ClockPort { now }
    @Bean @Primary fun testDiscovery() = SourceDiscoveryPort {
      discoveries.incrementAndGet()
      ProviderDiscoveryResult.Detected(dev.moreal.finds.domain.career.SourceProvider.NINEHIRE)
    }
    @Bean @Primary fun testFetch(transactions: TransactionPort) = SourceFetchPort { site ->
      // The source boundary must be able to start an independent transaction: nested use fails.
      check(transactions.execute { it.careerSites.findById(site.id) } != null)
      fetches.incrementAndGet()
      SourceFetchResult.Success(dev.moreal.finds.domain.crawl.Snapshot(site.id, site.canonicalBaseUrl.host, now, emptyList()))
    }
  }
  companion object {
    var now: Instant = Instant.parse("2026-09-23T00:00:00Z")
    val discoveries = java.util.concurrent.atomic.AtomicInteger()
    val fetches = java.util.concurrent.atomic.AtomicInteger()
  }
}
private fun b64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
private fun sha(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
/** Signs authenticatorData || SHA-256(clientDataJSON) independently with a fresh P-256 key. */
internal class Fixture {
  private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  private val rawId = UUID.randomUUID().toString().toByteArray()
  val id = CredentialId(b64(rawId))
  val cose = ObjectConverter().cborMapper.writeValueAsBytes(EC2COSEKey.create(key.public as ECPublicKey, COSEAlgorithmIdentifier.ES256))
  private val json = JsonMapper.builder().build()
  private fun client(challenge: String, origin: String, type: String) = json.writeValueAsBytes(mapOf("type" to type, "challenge" to challenge, "origin" to origin, "crossOrigin" to false))
  private fun auth(rp: String, flags: Int, count: Int) = sha(rp.toByteArray()) + byteArrayOf(flags.toByte()) + ByteBuffer.allocate(4).putInt(count).array()
  fun assertion(challenge: String, handle: ByteArray, origin: String = "https://localhost:8443", rp: String = "localhost",
    flags: Int = 5, count: Int = 8, corruptSignature: Boolean = false): Map<String, Any> {
    val client = client(challenge, origin, "webauthn.get")
    val auth = auth(rp, flags, count)
    val signature = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(auth + sha(client)); sign() }
    if (corruptSignature) signature[signature.lastIndex] = (signature.last().toInt() xor 1).toByte()
    return mapOf("id" to id.value, "rawId" to id.value, "type" to "public-key", "clientExtensionResults" to emptyMap<String, Any>(),
      "response" to mapOf("clientDataJSON" to b64(client), "authenticatorData" to b64(auth), "signature" to b64(signature), "userHandle" to b64(handle)))
  }
  fun registration(challenge: String, origin: String = "https://localhost:8443", rp: String = "localhost", flags: Int = 69): Map<String, Any> {
    val auth = auth(rp, flags, 0) + ByteArray(16) + ByteBuffer.allocate(2).putShort(rawId.size.toShort()).array() + rawId + cose
    val attestation = ObjectConverter().cborMapper.writeValueAsBytes(mapOf("fmt" to "none", "authData" to auth, "attStmt" to emptyMap<String, Any>()))
    return mapOf("publicKey" to mapOf("label" to "Laptop", "credential" to mapOf("id" to id.value, "rawId" to id.value, "type" to "public-key",
      "clientExtensionResults" to emptyMap<String, Any>(), "response" to mapOf("attestationObject" to b64(attestation), "clientDataJSON" to b64(client(challenge, origin, "webauthn.create")), "transports" to listOf("internal")))))
  }
}
