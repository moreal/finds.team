package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class AdditionalPasskeyHttpTest : OtpHttpSupport() {
  private val sql get() = context.getBean(DSLContext::class.java)

  @Test fun `registration options reject a different account or superseded begin key before issuing a challenge`() {
    val (original, _) = enroll()
    val (current, session) = enroll()
    val beginKey = UUID.randomUUID()
    begin(session, beginKey).andExpect(status().isOk)
    fun options(user: UserId, key: UUID) = postJson("/webauthn/register/options",
      json.writeValueAsString(mapOf("expectedUserId" to dev.moreal.finds.graphql.GlobalIdCodec.encode(
        dev.moreal.finds.graphql.NodeType.User, user.value), "beginKey" to key.toString())), session)
    options(original, UUID.randomUUID()).andExpect(status().isUnauthorized)
    options(current, UUID.randomUUID()).andExpect(status().isUnauthorized)
    assertNull(session.getAttribute("finds.webauthn.registration"))
    options(current, beginKey).andExpect(status().isOk)
  }

  @Test fun `additional registration binding cannot be used in enrollment or recovery restricted scopes`() {
    val (original, _) = enroll()
    val (recovering, _) = enroll()
    val email = email()
    challenge(email, VerificationPurpose.ENROLLMENT)
    val enrollment = verify("enrollment", email).andExpect(status().isOk).andReturn().request.session as MockHttpSession
    val recoveryScope = RestrictedSession(RestrictedSessionId(UUID.randomUUID()), recovering,
      RestrictedSessionScope.RECOVERY, now, now.plusSeconds(600))
    tx.execute { transaction ->
      val user = transaction.users.findById(recovering)!!
      transaction.users.lockByEmail(user.email)
      transaction.restrictedSessions.save(recoveryScope)
    }
    val recovery = MockHttpSession().apply { setAttribute(WebAuthnCeremonies.RESTRICTED_SESSION, recoveryScope.id) }
    val body = json.writeValueAsString(mapOf("expectedUserId" to dev.moreal.finds.graphql.GlobalIdCodec.encode(
      dev.moreal.finds.graphql.NodeType.User, original.value), "beginKey" to UUID.randomUUID().toString()))
    for (restricted in listOf(enrollment, recovery)) {
      postJson("/webauthn/register/options", body, restricted).andExpect(status().isUnauthorized)
      assertNull(restricted.getAttribute("finds.webauthn.registration"))
      postJson("/webauthn/register/options", "{}", restricted).andExpect(status().isOk)
    }
  }

  @Test fun `superseded and completed begin retries cannot replace a later ceremony`() {
    for (completeFirst in listOf(false, true)) {
      val (user, session) = enroll()
      val first = UUID.randomUUID()
      begin(session, first).andExpect(status().isOk)
      if (completeFirst) postJson("/webauthn/register", registration(session, Fixture()), session).andExpect(status().isOk)
      val second = UUID.randomUUID()
      begin(session, second).andExpect(status().isOk)
      val active = scope(session)
      val body = registration(session, Fixture())
      val ceremony = session.getAttribute("finds.webauthn.registration")
      val audit = audits(user)
      val count = tx.execute { it.credentials.findByUserId(user).size }
      begin(session, first).andExpect(status().isForbidden)
      begin(session, second).andExpect(status().isOk)
      assertEquals(active, scope(session))
      assertSame(ceremony, session.getAttribute("finds.webauthn.registration"))
      assertTrue(tx.execute { it.restrictedSessions.findById(active)!!.isUsable(now) })
      assertEquals(2, scopes(user))
      assertEquals(count, tx.execute { it.credentials.findByUserId(user).size })
      assertEquals(audit, audits(user))
      postJson("/webauthn/register", body, session).andExpect(status().isOk)
      assertEquals(count + 1, tx.execute { it.credentials.findByUserId(user).size })
      assertEquals(audit + 1, audits(user))
    }
  }

  @Test fun `accepted begin history fails closed without evicting superseded keys`() {
    val (user, session) = enroll()
    val keys = List(64) { UUID.randomUUID() }
    keys.forEach { begin(session, it).andExpect(status().isOk) }
    val active = scope(session)
    begin(session).andExpect(status().isForbidden)
    begin(session, keys.first()).andExpect(status().isForbidden)
    begin(session, keys.last()).andExpect(status().isOk)
    assertEquals(active, scope(session))
    assertEquals(64, scopes(user))
  }

  @Test fun `canceled begin retry cannot replace a later active registration`() {
    val (user, session) = enroll()
    val first = UUID.randomUUID()
    val second = UUID.randomUUID()
    begin(session, first).andExpect(status().isOk)
    postJson("/webauthn/register/cancel", "{}", session, first).andExpect(status().isOk)
    begin(session, second).andExpect(status().isOk)
    val active = scope(session)
    val body = registration(session, Fixture())
    val ceremony = session.getAttribute("finds.webauthn.registration")
    val audit = audits(user)
    begin(session, first).andExpect(status().isForbidden)
    assertEquals(active, scope(session))
    assertSame(ceremony, session.getAttribute("finds.webauthn.registration"))
    assertTrue(tx.execute { it.restrictedSessions.findById(active)!!.isUsable(now) })
    assertEquals(2, scopes(user))
    postJson("/webauthn/register", body, session).andExpect(status().isOk)
    assertEquals(2, tx.execute { it.credentials.findByUserId(user).size })
    assertEquals(audit + 1, audits(user))
  }

  @Test fun `canceled begin retry cannot restore restricted mode after a later cancellation`() {
    val (user, session) = enroll()
    val first = UUID.randomUUID()
    val second = UUID.randomUUID()
    val audit = audits(user)
    for (key in listOf(first, second)) {
      begin(session, key).andExpect(status().isOk)
      postJson("/webauthn/register/cancel", "{}", session, key).andExpect(status().isOk)
    }
    begin(session, first).andExpect(status().isForbidden)
    begin(session, second).andExpect(status().isForbidden)
    for (key in listOf(first, second)) {
      postJson("/webauthn/register/cancel", "{}", session, key).andExpect(status().isOk)
        .andExpect(content().string("""{"success":true}"""))
        .andExpect(header().string("Cache-Control", "no-store"))
    }
    assertNull(session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION))
    val query = mvc.perform(post("/graphql").secure(true).session(session).contentType("application/json")
      .content("""{"query":"{ __typename }"}""")).andExpect(request().asyncStarted()).andReturn()
    mvc.perform(asyncDispatch(query)).andExpect(status().isOk).andExpect(jsonPath("$.data.__typename").value("Query"))
    mvc.perform(get("/auth/session").secure(true).session(session)).andExpect(status().isOk)
    assertEquals(2, scopes(user))
    assertEquals(1, tx.execute { it.credentials.findByUserId(user).size })
    assertEquals(audit, audits(user))
  }

  @Test fun `cancellation history limit rejects new begins without evicting old canceled commands`() {
    val (user, session) = enroll()
    val keys = List(64) { UUID.randomUUID() }
    for (key in keys) {
      begin(session, key).andExpect(status().isOk)
      postJson("/webauthn/register/cancel", "{}", session, key).andExpect(status().isOk)
    }
    begin(session).andExpect(status().isForbidden)
    for (key in listOf(keys.first(), keys.last())) {
      begin(session, key).andExpect(status().isForbidden)
      postJson("/webauthn/register/cancel", "{}", session, key).andExpect(status().isOk)
        .andExpect(content().string("""{"success":true}"""))
    }
    assertEquals(64, scopes(user))
    assertNull(session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION))
    assertEquals(1, tx.execute { it.credentials.findByUserId(user).size })
  }

  @Test fun `cancel releases registration without changing credentials principal or CSRF and retries are harmless`() {
    val (user, session) = enroll()
    val auth = authentication(session)
    val browser = session.id
    val audit = audits(user)
    val key = UUID.randomUUID()
    begin(session, key).andExpect(status().isOk)
    val restricted = scope(session)
    val body = registration(session, Fixture())
    val token = json.readTree(mvc.perform(get("/auth/csrf").secure(true).session(session)).andReturn().response.contentAsString)["token"].asText()
    postJson("/graphql", """{"query":"{ __typename }"}""", session).andExpect(status().isForbidden)
    repeat(2) {
      mvc.perform(post("/webauthn/register/cancel").secure(true).session(session).header("Idempotency-Key", key).header("X-CSRF-TOKEN", token))
        .andExpect(status().isOk).andExpect(content().string("""{"success":true}"""))
        .andExpect(header().string("Cache-Control", "no-store"))
    }
    assertFalse(tx.execute { it.restrictedSessions.findById(restricted)!!.isUsable(now) })
    assertNull(session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION))
    assertNull(session.getAttribute("finds.webauthn.registration"))
    assertNull(session.getAttribute("finds.webauthn.additional-begin"))
    assertSame(auth, authentication(session))
    assertEquals(browser, session.id)
    mvc.perform(get("/auth/session").secure(true).session(session)).andExpect(status().isOk)
    val query = mvc.perform(post("/graphql").secure(true).session(session).header("X-CSRF-TOKEN", token)
      .contentType("application/json").content("""{"query":"{ __typename }"}"""))
      .andExpect(request().asyncStarted()).andReturn()
    mvc.perform(asyncDispatch(query)).andExpect(status().isOk).andExpect(jsonPath("$.data.__typename").value("Query"))
    postJson("/webauthn/register", body, session).andExpect(status().isUnauthorized)
    assertEquals(1, tx.execute { it.credentials.findByUserId(user).size })
    assertEquals(audit, audits(user))
    begin(session, key).andExpect(status().isForbidden)
    assertNull(session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION))
    begin(session).andExpect(status().isOk)
    val fresh = scope(session)
    postJson("/webauthn/register/cancel", "{}", session, key).andExpect(status().isForbidden)
    assertEquals(fresh, scope(session))
  }

  @Test fun `cancel rejects wrong browser key owner stale principal and absent CSRF without clearing ceremony`() {
    val fixture = Fixture()
    val (user, session) = enroll(fixture)
    val other = login(user, fixture, MockHttpSession(), 2)
    val key = UUID.randomUUID()
    begin(session, key).andExpect(status().isOk)
    val restricted = scope(session)
    registration(session, Fixture())
    val ceremony = session.getAttribute("finds.webauthn.registration")
    val requestId = UUID.randomUUID()
    postJson("/webauthn/register/cancel", "{}", session, requestId = requestId).andExpect(status().isForbidden)
      .andExpect(header().string("Cache-Control", "no-store"))
    assertEquals(1, sql.fetchCount(sql.selectFrom("security_events").where("request_id = ? and action = 'identity.authorization_denied'", requestId)))
    postJson("/webauthn/register/cancel", "{}", other, key).andExpect(status().isForbidden)
    postJson("/webauthn/register/cancel", "{}", MockHttpSession(), key).andExpect(status().isUnauthorized)
    mvc.perform(post("/webauthn/register/cancel").secure(true).session(session).header("Idempotency-Key", key)).andExpect(status().isForbidden)
    for (invalid in listOf(null, "invalid", "1-1-1-1-1")) {
      val request = post("/webauthn/register/cancel").secure(true).session(session).with(csrf())
      if (invalid != null) request.header("Idempotency-Key", invalid)
      mvc.perform(request).andExpect(status().isBadRequest)
    }
    val (_, foreign) = enroll()
    session.attributeNames.toList().filter { it.startsWith("finds.") }.forEach { foreign.setAttribute(it, session.getAttribute(it)) }
    postJson("/webauthn/register/cancel", "{}", foreign, key).andExpect(status().isForbidden)
    now = now.plusSeconds(301)
    postJson("/webauthn/register/cancel", "{}", session, key).andExpect(status().isForbidden)
    assertEquals(restricted, scope(session))
    assertSame(ceremony, session.getAttribute("finds.webauthn.registration"))
    assertNull(tx.execute { it.restrictedSessions.findById(restricted)!!.invalidatedAt })
    val principal = principal(session)
    tx.execute { it.users.lockByEmail(it.users.findById(user)!!.email); it.userSessions.revoke(principal.sessionId, now) }
    postJson("/webauthn/register/cancel", "{}", session, key).andExpect(status().isUnauthorized)
    assertSame(ceremony, session.getAttribute("finds.webauthn.registration"))
  }

  @Test fun `cancel waits for options publication then removes the whole pending ceremony`() {
    val (user, original) = enroll()
    val session = gated(original)
    val key = UUID.randomUUID()
    begin(session, key).andExpect(status().isOk)
    val audit = audits(user)
    session.pauseOn = "finds.webauthn.registration"
    val options = concurrently { registration(session, Fixture()) }
    try {
      assertTrue(session.entered.await(10, TimeUnit.SECONDS))
      val cancel = concurrently { postJson("/webauthn/register/cancel", "{}", session, key).andExpect(status().isOk) }
      awaitFinishedOrSessionLock(cancel, session)
      session.release.countDown()
      val body = options.result.get(10, TimeUnit.SECONDS)
      cancel.result.get(10, TimeUnit.SECONDS)
      assertNull(session.getAttribute("finds.webauthn.registration"))
      assertNull(session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION))
      postJson("/webauthn/register", body, session).andExpect(status().isUnauthorized)
      assertEquals(1, tx.execute { it.credentials.findByUserId(user).size })
      assertEquals(audit, audits(user))
    } finally { session.release.countDown(); options.thread.join(10000) }
  }

  @Test fun `completion winning cancellation preserves its committed credential and replay`() {
    val (user, original) = enroll()
    val session = gated(original)
    val key = UUID.randomUUID()
    begin(session, key).andExpect(status().isOk)
    val body = registration(session, Fixture())
    val command = UUID.randomUUID()
    val audit = audits(user)
    session.pauseOn = "finds.webauthn.completion"
    val completion = concurrently { postJson("/webauthn/register", body, session, command).andExpect(status().isOk) }
    try {
      assertTrue(session.entered.await(10, TimeUnit.SECONDS))
      val cancel = concurrently { postJson("/webauthn/register/cancel", "{}", session, key).andExpect(status().isForbidden) }
      awaitFinishedOrSessionLock(cancel, session)
      session.release.countDown()
      completion.result.get(10, TimeUnit.SECONDS)
      cancel.result.get(10, TimeUnit.SECONDS)
      postJson("/webauthn/register", body, session, command).andExpect(status().isOk)
      assertEquals(2, tx.execute { it.credentials.findByUserId(user).size })
      assertEquals(audit + 1, audits(user))
      assertNull(session.getAttribute("finds.webauthn.registration"))
      assertNull(session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION))
    } finally { session.release.countDown(); completion.thread.join(10000) }
  }

  @Test fun `real servlet sessions retain an explicit mutex across requests and facade wrappers`() {
    val server = (context as org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext).webServer
      as org.springframework.boot.tomcat.TomcatWebServer
    val client = java.net.http.HttpClient.newHttpClient()
    val uri = java.net.URI("http://localhost:${server.port}/auth/csrf")
    val first = client.send(java.net.http.HttpRequest.newBuilder(uri).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
    assertEquals(200, first.statusCode())
    val cookie = first.headers().firstValue("set-cookie").orElseThrow().substringBefore(';')
    val servlet = server.tomcat.host.findChildren().filterIsInstance<org.apache.catalina.Context>().single()
    val session = servlet.manager.findSession(cookie.substringAfter('='))!!.session
    val mutex = assertNotNull(session.getAttribute(org.springframework.web.util.WebUtils.SESSION_MUTEX_ATTRIBUTE))
    val second = client.send(java.net.http.HttpRequest.newBuilder(uri).header("Cookie", cookie).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
    assertEquals(200, second.statusCode())
    val wrapper = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(jakarta.servlet.http.HttpSession::class.java)) {
      _, method, arguments -> method.invoke(session, *(arguments ?: emptyArray()))
    } as jakarta.servlet.http.HttpSession
    assertNotSame(session, wrapper)
    assertSame(mutex, org.springframework.web.util.WebUtils.getSessionMutex(wrapper))
    assertSame(mutex, org.springframework.web.util.WebUtils.getSessionMutex(session))
  }

  @Test fun `new begin and options survive an older completion paused after its database commit`() {
    val (user, original) = enroll()
    val session = gated(original)
    begin(session).andExpect(status().isOk)
    val oldBody = registration(session, Fixture())
    val audit = audits(user)
    val oldKey = UUID.randomUUID()
    session.pauseOn = "finds.webauthn.completion"
    val old = concurrently { postJson("/webauthn/register", oldBody, session, oldKey).andExpect(status().isOk) }
    try {
      assertTrue(session.entered.await(10, TimeUnit.SECONDS), "completion must reach post-commit publication")
      assertEquals(2, tx.execute { it.credentials.findByUserId(user).size })
      val newKey = UUID.randomUUID()
      val fresh = concurrently {
        begin(session, newKey).andExpect(status().isOk)
        scope(session) to registration(session, Fixture())
      }
      awaitFinishedOrSessionLock(fresh, session)
      session.release.countDown()
      old.result.get(10, TimeUnit.SECONDS)
      val (newScope, newBody) = fresh.result.get(10, TimeUnit.SECONDS)
      assertEquals(newScope, session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION))
      begin(session, newKey).andExpect(status().isOk)
      val completionKey = UUID.randomUUID()
      repeat(2) { postJson("/webauthn/register", newBody, session, completionKey).andExpect(status().isOk) }
      assertEquals(3, tx.execute { it.credentials.findByUserId(user).size })
      assertEquals(audit + 2, audits(user))
    } finally { session.release.countDown(); old.thread.join(10000) }
  }

  @Test fun `replacement options published before old completion preserve the new challenge`() {
    val (user, original) = enroll()
    val session = gated(original)
    begin(session).andExpect(status().isOk)
    val oldBody = registration(session, Fixture())
    val audit = audits(user)
    session.pauseOn = "finds.webauthn.registration"
    val fresh = concurrently { registration(session, Fixture()) }
    try {
      assertTrue(session.entered.await(10, TimeUnit.SECONDS), "options must pause before publishing their challenge")
      val old = concurrently { postJson("/webauthn/register", oldBody, session).andExpect(status().isUnauthorized) }
      awaitFinishedOrSessionLock(old, session)
      session.release.countDown()
      val body = fresh.result.get(10, TimeUnit.SECONDS)
      old.result.get(10, TimeUnit.SECONDS)
      val completionKey = UUID.randomUUID()
      repeat(2) { postJson("/webauthn/register", body, session, completionKey).andExpect(status().isOk) }
      assertEquals(2, tx.execute { it.credentials.findByUserId(user).size })
      assertEquals(audit + 1, audits(user))
    } finally { session.release.countDown(); fresh.thread.join(10000) }
  }

  @Test fun `begin winning before old completion preserves the replacement scope and its ceremony`() {
    val (user, original) = enroll()
    val session = gated(original)
    begin(session).andExpect(status().isOk)
    val oldBody = registration(session, Fixture())
    val audit = audits(user)
    val key = UUID.randomUUID()
    session.pauseOn = WebAuthnCeremonies.RESTRICTED_SESSION
    val fresh = concurrently { begin(session, key).andExpect(status().isOk) }
    try {
      assertTrue(session.entered.await(10, TimeUnit.SECONDS), "begin must commit before publishing its new scope")
      val old = concurrently { postJson("/webauthn/register", oldBody, session).andExpect(status().isUnauthorized) }
      awaitFinishedOrSessionLock(old, session)
      session.release.countDown()
      fresh.result.get(10, TimeUnit.SECONDS)
      old.result.get(10, TimeUnit.SECONDS)
      val newScope = scope(session)
      begin(session, key).andExpect(status().isOk)
      assertEquals(newScope, scope(session))
      val body = registration(session, Fixture())
      val completionKey = UUID.randomUUID()
      repeat(2) { postJson("/webauthn/register", body, session, completionKey).andExpect(status().isOk) }
      assertEquals(2, tx.execute { it.credentials.findByUserId(user).size })
      assertEquals(audit + 1, audits(user))
    } finally { session.release.countDown(); fresh.thread.join(10000) }
  }

  @Test fun `begin retries preserve usable scope while a new command replaces it without account mutation`() {
    val (user, session) = enroll()
    val key = UUID.randomUUID()
    val before = tx.execute { it.users.findById(user) }
    val audit = audits(user)
    begin(session, key).andExpect(status().isOk).andExpect(content().json("""{"ready":true}"""))
      .andExpect(header().string("Cache-Control", "no-store"))
    val first = scope(session)
    assertEquals(RestrictedSessionScope.ADDITIONAL_PASSKEY, tx.execute { it.restrictedSessions.findById(first)!!.scope })
    val body = registration(session, Fixture())
    begin(session, key).andExpect(status().isOk).andExpect(content().string("""{"ready":true}"""))
    assertEquals(first, scope(session))
    assertEquals(1, scopes(user))
    assertTrue(tx.execute { it.restrictedSessions.findById(first)!!.isUsable(now) })
    begin(session).andExpect(status().isOk)
    assertNotEquals(first, scope(session))
    assertFalse(tx.execute { it.restrictedSessions.findById(first)!!.isUsable(now) })
    postJson("/webauthn/register", body, session).andExpect(status().isUnauthorized)
    assertEquals(2, scopes(user))
    assertEquals(before!!.status, tx.execute { it.users.findById(user)!!.status })
    assertEquals(before.credentials, tx.execute { it.users.findById(user)!!.credentials })
    assertEquals(1, tx.execute { it.credentials.findByUserId(user).size })
    assertEquals(audit, audits(user))
  }

  @Test fun `begin requires live recent authentication CSRF and an explicit canonical UUID command`() {
    begin(MockHttpSession()).andExpect(status().isUnauthorized).andExpect(header().string("Cache-Control", "no-store"))
    val (user, session) = enroll()
    mvc.perform(post("/webauthn/register/begin").secure(true).session(session).header("Idempotency-Key", UUID.randomUUID()))
      .andExpect(status().isForbidden)
    for (key in listOf(null, "invalid", "1-1-1-1-1")) {
      val request = post("/webauthn/register/begin").secure(true).session(session).with(csrf())
      if (key != null) request.header("Idempotency-Key", key)
      mvc.perform(request).andExpect(status().isBadRequest).andExpect(header().string("Cache-Control", "no-store"))
    }
    assertEquals(0, scopes(user))
    val key = UUID.randomUUID()
    begin(session, key).andExpect(status().isOk)
    now = now.plusSeconds(301)
    val requestId = UUID.randomUUID()
    postJson("/webauthn/register/begin", "{}", session, key, requestId = requestId).andExpect(status().isForbidden)
    begin(session).andExpect(status().isForbidden)
    assertEquals(1, scopes(user))
    assertEquals(1, sql.fetchCount(sql.selectFrom("security_events").where("request_id = ? and action = 'identity.authorization_denied'", requestId)))
    val principal = principal(session)
    tx.execute {
      it.users.lockByEmail(it.users.findById(user)!!.email)
      it.userSessions.revoke(principal.sessionId, now)
    }
    begin(session, key).andExpect(status().isUnauthorized)
  }

  @Test fun `begin replay rejects invalidated expired or foreign scope and never mints a replacement`() {
    val (user, session) = enroll()
    val key = UUID.randomUUID()
    begin(session, key).andExpect(status().isOk)
    val first = scope(session)
    tx.execute {
      it.users.lockByEmail(it.users.findById(user)!!.email)
      it.restrictedSessions.invalidateForUser(user, RestrictedSessionScope.ADDITIONAL_PASSKEY, now)
    }
    begin(session, key).andExpect(status().isForbidden)
    assertEquals(1, scopes(user))
    val nextKey = UUID.randomUUID()
    begin(session, nextKey).andExpect(status().isOk)
    val next = scope(session)
    now = now.plusSeconds(1)
    sql.execute("update restricted_sessions set expires_at = ?::timestamptz where id = ?", now.toString(), next.value)
    begin(session, nextKey).andExpect(status().isForbidden)
    assertEquals(2, scopes(user))
    assertNotEquals(first, next)
    val (_, other) = enroll()
    begin(other).andExpect(status().isOk)
    session.setAttribute(WebAuthnCeremonies.RESTRICTED_SESSION, scope(other))
    begin(session, nextKey).andExpect(status().isForbidden)
    assertEquals(2, scopes(user))
  }

  @Test fun `real completion is atomic secret free and retains normal principal and CSRF`() {
    val (user, session) = enroll()
    val before = principal(session)
    val auth = authentication(session)
    val browserId = session.id
    val beginKey = UUID.randomUUID()
    begin(session, beginKey).andExpect(status().isOk)
    val restricted = scope(session)
    val fixture = Fixture()
    val body = registration(session, fixture)
    begin(session, beginKey).andExpect(status().isOk)
    val token = json.readTree(mvc.perform(get("/auth/csrf").secure(true).session(session)).andReturn().response.contentAsString)["token"].asText()
    val command = UUID.randomUUID()
    val requestId = UUID.randomUUID()
    fun submit(encoded: String = body) = mvc.perform(post("/webauthn/register").secure(true).session(session)
      .header("Idempotency-Key", command).header("X-Request-ID", requestId).header("X-CSRF-TOKEN", token)
      .contentType("application/json").content(encoded))
    val audit = audits(user)
    submit(json.writeValueAsString(fixture.registration("wrong-challenge"))).andExpect(status().isUnauthorized)
    assertEquals(1, tx.execute { it.credentials.findByUserId(user).size })
    assertEquals(audit, audits(user))
    assertTrue(tx.execute { it.restrictedSessions.findById(restricted)!!.isUsable(now) })
    sql.execute("alter table audit_events add constraint additional_audit_failure check (request_id <> '$requestId')")
    try {
      assertFails { submit() }
      assertEquals(1, tx.execute { it.credentials.findByUserId(user).size })
      assertEquals(1, tx.execute { it.users.findById(user)!!.credentials.size })
      assertEquals(audit, audits(user))
      assertTrue(tx.execute { it.restrictedSessions.findById(restricted)!!.isUsable(now) })
      assertNull(sql.fetchOne("select consumed_at from webauthn_challenges where restricted_session_id = ?", restricted.value)!!.get("consumed_at"))
    } finally { sql.execute("alter table audit_events drop constraint additional_audit_failure") }
    val receipt = json.readTree(submit().andExpect(status().isOk).andReturn().response.contentAsString)
    val managementId = assertNotNull(receipt["passkeyId"]).asText()
    assertEquals(setOf("success", "passkeyId"), receipt.properties().map { it.key }.toSet())
    val decoded = String(java.util.Base64.getUrlDecoder().decode(managementId))
    assertTrue(decoded.startsWith("v1:Passkey:"))
    assertEquals(1, sql.fetchCount(sql.selectFrom("passkey_credentials").where("user_id = ? and management_id = ?::uuid", user.value, decoded.removePrefix("v1:Passkey:"))))
    val credentialId = CredentialId(json.readTree(body)["publicKey"]["credential"]["id"].asText())
    val receipts = dev.moreal.finds.persistence.JooqPasskeyRegistrationReceipt(sql)
    assertEquals(UUID.fromString(decoded.removePrefix("v1:Passkey:")), receipts.managementId(user, credentialId))
    assertNull(receipts.managementId(UserId(UUID.randomUUID()), credentialId))
    submit().andExpect(status().isOk).andExpect(content().json(receipt.toString(), true))
    assertEquals(2, tx.execute { it.credentials.findByUserId(user).size })
    assertEquals(audit + 1, audits(user))
    assertEquals(browserId, session.id)
    assertEquals(before.sessionId, principal(session).sessionId)
    assertEquals(before.actor.userId, principal(session).actor.userId)
    assertEquals(before.actor.authenticatedAt, principal(session).actor.authenticatedAt)
    assertSame(auth, authentication(session))
    assertNull(session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION))
    mvc.perform(get("/auth/session").secure(true).session(session)).andExpect(status().isOk)
    mvc.perform(post("/webauthn/authenticate/options").secure(true).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk)
    begin(session, beginKey).andExpect(status().isForbidden)
    assertEquals(1, scopes(user))
    tx.execute {
      val current = it.users.lockByEmail(it.users.findById(user)!!.email)!!
      it.credentials.remove(credentialId)
      it.users.save((current.removeCredential(credentialId) as UserChange.Updated).user)
    }
    assertNull(receipts.managementId(user, credentialId))
    submit().andExpect(status().isOk).andExpect(content().json(receipt.toString(), true))
    assertEquals(1, tx.execute { it.credentials.findByUserId(user).size })
    assertEquals(audit + 1, audits(user))
  }

  @Test fun `same command in another authenticated browser creates its own scope instead of replaying the first`() {
    val fixture = Fixture()
    val (user, first) = enroll(fixture)
    val second = login(user, fixture, MockHttpSession(), 2)
    val key = UUID.randomUUID()
    begin(first, key).andExpect(status().isOk)
    val oldScope = scope(first)
    begin(second, key).andExpect(status().isOk)
    assertNotEquals(oldScope, scope(second))
    assertEquals(2, scopes(user))
    begin(first, key).andExpect(status().isForbidden)
    assertEquals(2, scopes(user))
    postJson("/webauthn/register/options", "{}", first).andExpect(status().isUnauthorized)
  }

  @Test fun `ceremony cannot complete in another browser or for another owner`() {
    val (user, session) = enroll()
    val key = UUID.randomUUID()
    begin(session, key).andExpect(status().isOk)
    val originalScope = scope(session)
    val body = registration(session, Fixture())
    val audit = audits(user)
    val (_, other) = enroll()
    // Even stolen server attributes cannot cross the challenge's HTTP-session binding.
    val attributes = session.attributeNames.toList().filter { it.startsWith("finds.") }
    attributes.forEach { other.setAttribute(it, session.getAttribute(it)) }
    begin(other, key).andExpect(status().isForbidden)
    postJson("/webauthn/register", body, other).andExpect(status().isUnauthorized)
    val originalAuthentication = authentication(session)
    (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext).authentication = authentication(other)
    postJson("/webauthn/register", body, session).andExpect(status().isUnauthorized)
    (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext).authentication = originalAuthentication
    assertEquals(1, tx.execute { it.credentials.findByUserId(user).size })
    assertEquals(audit, audits(user))
    assertTrue(tx.execute { it.restrictedSessions.findById(originalScope)!!.isUsable(now) })
  }

  @Test fun `begin bound to another account cannot create or replace a restricted scope`() {
    val (original, originalSession) = enroll()
    val (user, session) = enroll()
    val expected = dev.moreal.finds.graphql.GlobalIdCodec.encode(dev.moreal.finds.graphql.NodeType.User, original.value)
    val key = UUID.randomUUID()
    for (retry in listOf(false, true)) {
      if (retry) begin(originalSession, key).andExpect(status().isOk)
      val count = scopes(user)
      postJson("/webauthn/register/begin", """{"expectedUserId":"$expected"}""", session, key)
        .andExpect(status().isForbidden).andExpect(header().string("Cache-Control", "no-store"))
      assertEquals(count, scopes(user))
      assertNull(session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION))
    }
  }

  @Test fun `restricted scope permits no-store session account preflight with the viewer global identity`() {
    val (user, session) = enroll()
    begin(session).andExpect(status().isOk)
    mvc.perform(get("/auth/session").secure(true).session(session)).andExpect(status().isOk)
      .andExpect(header().string("Cache-Control", "no-store"))
      .andExpect(jsonPath("$.userId").value(user.value.toString()))
      .andExpect(jsonPath("$.userGlobalId").value(dev.moreal.finds.graphql.GlobalIdCodec.encode(dev.moreal.finds.graphql.NodeType.User, user.value)))
    mvc.perform(post("/graphql").secure(true).session(session).contentType("application/json")
      .content("""{"query":"{ viewer { user { id } } }"}""")).andExpect(status().isForbidden)
  }

  private fun begin(session: MockHttpSession, key: UUID = UUID.randomUUID()): org.springframework.test.web.servlet.ResultActions {
    val authentication = (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as? SecurityContext)?.authentication
    val actor = context.getBean(ActorResolver::class.java).sessionPrincipal(authentication)
    val id = actor?.let { dev.moreal.finds.graphql.GlobalIdCodec.encode(dev.moreal.finds.graphql.NodeType.User, it.actor.userId) }
    return postJson("/webauthn/register/begin", json.writeValueAsString(mapOf("expectedUserId" to id)), session, key)
  }
  private fun scope(session: MockHttpSession) = session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION) as RestrictedSessionId
  private fun scopes(user: UserId) = sql.fetchCount(sql.selectFrom("restricted_sessions").where("user_id = ? and scope = 'ADDITIONAL_PASSKEY'", user.value))
  private fun audits(user: UserId) = sql.fetchCount(sql.selectFrom("audit_events").where("target_id = ? and action = 'passkey.registered'", user.value.toString()))
  private fun authentication(session: MockHttpSession) = (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext).authentication
  private fun principal(session: MockHttpSession) = context.getBean(ActorResolver::class.java).sessionPrincipal(authentication(session))!!
  private fun registration(session: MockHttpSession, fixture: Fixture): String {
    val begin = session.getAttribute(AdditionalPasskeyController.BEGIN) as? AdditionalPasskeyController.Begin
    val body = begin?.let {
      json.writeValueAsString(mapOf("expectedUserId" to dev.moreal.finds.graphql.GlobalIdCodec.encode(
        dev.moreal.finds.graphql.NodeType.User, principal(session).actor.userId), "beginKey" to it.key.toString()))
    } ?: "{}"
    val options = json.readTree(postJson("/webauthn/register/options", body, session).andExpect(status().isOk).andReturn().response.contentAsString)
    return json.writeValueAsString(fixture.registration(options["challenge"].asText()))
  }
  private fun enroll(fixture: Fixture = Fixture()): Pair<UserId, MockHttpSession> {
    val email = email()
    challenge(email, VerificationPurpose.ENROLLMENT)
    val session = verify("enrollment", email).andExpect(status().isOk).andReturn().request.session as MockHttpSession
    postJson("/webauthn/register", registration(session, fixture), session).andExpect(status().isOk)
    val user = tx.execute { it.users.lockByEmail(email)!! }
    return user.id to login(user.id, fixture, session, 1)
  }
  private fun login(user: UserId, fixture: Fixture, session: MockHttpSession, count: Int): MockHttpSession {
    val options = json.readTree(postJson("/webauthn/authenticate/options", "{}", session).andExpect(status().isOk).andReturn().response.contentAsString)
    postJson("/login/webauthn", json.writeValueAsString(fixture.assertion(options["challenge"].asText(),
      tx.execute { it.users.findUserHandle(user)!! }, count = count)), session).andExpect(status().isOk)
    return session
  }

  private fun gated(original: MockHttpSession) = GatedSession().apply {
    original.attributeNames.toList().forEach { setAttribute(it, original.getAttribute(it)) }
    org.springframework.web.util.HttpSessionMutexListener().sessionCreated(jakarta.servlet.http.HttpSessionEvent(this))
  }
  private class GatedSession : MockHttpSession() {
    @Volatile var pauseOn: String? = null
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val paused = AtomicBoolean()
    override fun setAttribute(name: String, value: Any?) {
      if (name == pauseOn && paused.compareAndSet(false, true)) {
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "Test publication barrier timed out" }
      }
      super.setAttribute(name, value)
    }
  }
  private class Pending<T>(val result: CompletableFuture<T>, val thread: Thread)
  private fun <T> concurrently(block: () -> T): Pending<T> {
    val result = CompletableFuture<T>()
    val thread = Thread.ofPlatform().start {
      try { result.complete(block()) } catch (failure: Throwable) { result.completeExceptionally(failure) }
    }
    return Pending(result, thread)
  }
  /** Reach either the unprotected interleaving or the shared mutex, never rely on a sleep. */
  private fun awaitFinishedOrSessionLock(pending: Pending<*>, session: MockHttpSession) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    val locks = setOf(System.identityHashCode(session), System.identityHashCode(org.springframework.web.util.WebUtils.getSessionMutex(session)))
    val threads = java.lang.management.ManagementFactory.getThreadMXBean()
    while (!pending.result.isDone) {
      val info = threads.getThreadInfo(pending.thread.threadId())
      if (info?.threadState == Thread.State.BLOCKED && info.lockInfo?.identityHashCode in locks) return
      check(System.nanoTime() < deadline) { "Concurrent request did not reach the session transition" }
      Thread.yield()
    }
  }
}
