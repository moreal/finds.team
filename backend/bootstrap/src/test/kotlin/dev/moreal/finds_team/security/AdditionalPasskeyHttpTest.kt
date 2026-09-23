package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import java.util.UUID
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
    repeat(2) { submit().andExpect(status().isOk).andExpect(content().string("""{"success":true}""")) }
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

  private fun begin(session: MockHttpSession, key: UUID = UUID.randomUUID()) = postJson("/webauthn/register/begin", "{}", session, key)
  private fun scope(session: MockHttpSession) = session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION) as RestrictedSessionId
  private fun scopes(user: UserId) = sql.fetchCount(sql.selectFrom("restricted_sessions").where("user_id = ? and scope = 'ADDITIONAL_PASSKEY'", user.value))
  private fun audits(user: UserId) = sql.fetchCount(sql.selectFrom("audit_events").where("target_id = ? and action = 'passkey.registered'", user.value.toString()))
  private fun authentication(session: MockHttpSession) = (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext).authentication
  private fun principal(session: MockHttpSession) = context.getBean(ActorResolver::class.java).sessionPrincipal(authentication(session))!!
  private fun registration(session: MockHttpSession, fixture: Fixture): String {
    val options = json.readTree(postJson("/webauthn/register/options", "{}", session).andExpect(status().isOk).andReturn().response.contentAsString)
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
}
