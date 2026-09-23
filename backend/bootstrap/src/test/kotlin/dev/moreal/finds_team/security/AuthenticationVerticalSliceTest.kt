package dev.moreal.finds_team.security

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.usecase.*
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds.notification.MailOutboxDispatcher
import dev.moreal.mail.testing.RecordingMailTransport
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import tools.jackson.databind.JsonNode

/** No identity seeding: every account, credential and proof comes through the assembled adapters. */
class AuthenticationVerticalSliceTest : OtpHttpSupport() {
  private val sql get() = context.getBean(DSLContext::class.java)

  @Test fun `additional registration preserves ordinary access principal and CSRF after completion and replay`() {
    val (userId, session) = enroll(email())
    val before = principal(session)
    val authentication = (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext).authentication
    val sessionId = session.id
    val scope = assertIs<AdditionalPasskeySessionResult.Ready>(BeginAdditionalPasskeyRegistration(tx,
      context.getBean(ClockPort::class.java), context.getBean(SecureRandomPort::class.java)).execute(before)).session
    session.setAttribute(WebAuthnCeremonies.RESTRICTED_SESSION, scope.id)
    val second = Fixture()
    val body = registration(session, second)
    val csrf = json.readTree(mvc.perform(get("/auth/csrf").secure(true).session(session))
      .andExpect(status().isOk).andReturn().response.contentAsString)["token"].asText()
    val command = UUID.randomUUID()
    fun submit(encoded: String, key: UUID = command) = mvc.perform(post("/webauthn/register").secure(true).session(session)
      .header("X-CSRF-TOKEN", csrf).header("Idempotency-Key", key).contentType("application/json").content(encoded))
    // A cryptographically invalid ceremony cannot consume the scope or add a credential.
    submit(json.writeValueAsString(second.registration("wrong-challenge"))).andExpect(status().isUnauthorized)
    assertEquals(scope.id, session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION))
    assertEquals(1, tx.execute { it.credentials.findByUserId(userId).size })
    assertEquals(before.sessionId, principal(session).sessionId)
    assertSame(authentication, (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext).authentication)
    val encoded = json.writeValueAsString(body)
    submit(encoded).andExpect(status().isOk)
    assertGraphqlAllowed(session)
    assertEquals(sessionId, session.id)
    assertEquals(before.sessionId, principal(session).sessionId)
    assertSame(authentication, (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext).authentication)
    mvc.perform(post("/webauthn/authenticate/options").secure(true).session(session)
      .header("X-CSRF-TOKEN", csrf).contentType("application/json").content("{}"))
      .andExpect(status().isOk)
    mvc.perform(post("/webauthn/authenticate/options").secure(true).session(session)
      .contentType("application/json").content("{}"))
      .andExpect(status().isForbidden)
    submit(encoded).andExpect(status().isOk).andExpect(jsonPath("$.recoveryCode").doesNotExist())
    submit(encoded, UUID.randomUUID()).andExpect(status().isUnauthorized)
    assertGraphqlAllowed(session)
    assertEquals(2, tx.execute { it.credentials.findByUserId(userId).size })
  }

  @Test fun `delivered enrollment then discoverable login and two proof recovery replace every old credential and session`() {
    val email = email()
    val otp = deliver("enrollment", email)
    val previous = MockHttpSession().apply { setAttribute("untrusted", "discard") }
    val result = verify("enrollment", email, otp, session = previous).andExpect(status().isOk).andReturn()
    val restricted = result.request.session as MockHttpSession
    assertTrue(previous.isInvalid); assertNotEquals(previous.id, restricted.id)
    assertNull(restricted.getAttribute("untrusted"))
    assertRestricted(restricted, RestrictedSessionScope.ENROLLMENT)
    val user = tx.execute { it.users.lockByEmail(email)!! }
    assertEquals(UserStatus.PENDING_PASSKEY, user.status)
    assertTrue(tx.execute { it.userSessions.findByUserId(user.id).isEmpty() })
    verify("enrollment", email, otp).andExpect(status().isUnauthorized)
    postJson("/login/webauthn", json.writeValueAsString(mapOf("email" to email.value, "otp" to otp)))
      .andExpect(status().isBadRequest)
    postJson("/login", "username=anything&password=anything").andExpect(status().isUnauthorized)

    val first = Fixture()
    val registration = registration(restricted, first)
    val code = complete(restricted, registration)
    assertGraphqlDenied(restricted)
    assertEquals("Laptop", tx.execute { it.credentials.findById(first.id)!!.label })
    assertIs<RecoveryCodeResult.Valid>(RecoveryCode.parse(code))
    assertEquals(26, code.replace("-", "").length)
    assertEquals(setOf(UserRole.USER), tx.execute { it.users.findById(user.id)!!.roles })
    mvc.perform(get("/auth/session").secure(true).session(restricted)).andExpect(status().isUnauthorized)
    val handle = tx.execute { it.users.findUserHandle(user.id)!! }
    val oldSession = login(first, handle, restricted, count = 1)
    val otherSession = login(first, handle, count = 2)
    // A second real registration makes revocation plural, not just a one-credential happy path.
    val principal = principal(oldSession)
    val scope = BeginAdditionalPasskeyRegistration(tx, context.getBean(ClockPort::class.java), context.getBean(SecureRandomPort::class.java))
      .execute(principal)
    assertIs<AdditionalPasskeySessionResult.Ready>(scope)
    oldSession.setAttribute(WebAuthnCeremonies.RESTRICTED_SESSION, scope.session.id)
    val second = Fixture()
    postJson("/webauthn/register", json.writeValueAsString(registration(oldSession, second)), oldSession)
      .andExpect(status().isOk).andExpect(jsonPath("$.recoveryCode").doesNotExist())
    val secondSession = login(second, handle, count = 1)

    val recoveryOtp = deliver("recovery", email)
    verify("recovery", email, recoveryOtp).andExpect(status().isUnauthorized)
    verify("recovery", email, if (recoveryOtp == "00000000") "00000001" else "00000000", code).andExpect(status().isUnauthorized)
    assertEquals(UserStatus.ACTIVE, tx.execute { it.users.findById(user.id)!!.status })
    mvc.perform(get("/auth/session").secure(true).session(otherSession)).andExpect(status().isOk)
    val recovered = verify("recovery", email, recoveryOtp, code).andExpect(status().isOk).andReturn().request.session as MockHttpSession
    assertRestricted(recovered, RestrictedSessionScope.RECOVERY)
    val replacement = Fixture()
    val newCode = complete(recovered, registration(recovered, replacement))
    assertGraphqlDenied(recovered)
    assertEquals("Laptop", tx.execute { it.credentials.findById(replacement.id)!!.label })
    assertTrue(code != newCode, "recovery must rotate the saved code")
    listOf(oldSession, otherSession, secondSession).forEach {
      mvc.perform(get("/auth/session").secure(true).session(it)).andExpect(status().isUnauthorized)
    }
    listOf(first, second).forEach { old ->
      val (options, session) = options()
      postJson("/login/webauthn", json.writeValueAsString(old.assertion(options["challenge"].asText(), handle, count = 3)), session)
        .andExpect(status().isUnauthorized)
    }
    tx.execute {
      assertEquals(listOf(replacement.id), it.credentials.findByUserId(user.id).map { c -> c.material.id })
      assertTrue(it.userSessions.findByUserId(user.id).all { s -> s.revokedAt != null })
      val hash = it.recoveryCodes.findByUserId(user.id)!!.hash
      assertTrue(hashes.matches(hash, IdentityHashPurpose.RECOVERY_CODE, user.id.value.toString(), newCode))
      assertFalse(hashes.matches(hash, IdentityHashPurpose.RECOVERY_CODE, user.id.value.toString(), code))
    }
    login(replacement, handle, recovered, count = 1)
    now = now.plusSeconds(61)
    val nextOtp = deliver("recovery", email)
    verify("recovery", email, nextOtp, code).andExpect(status().isUnauthorized)
    val again = verify("recovery", email, nextOtp, newCode).andExpect(status().isOk).andReturn().request.session as MockHttpSession
    val finalCode = complete(again, registration(again, Fixture()))
    assertTrue(newCode != finalCode, "the next completion must rotate the code again")
    now = now.plusSeconds(61)
    verify("recovery", email, deliver("recovery", email), newCode).andExpect(status().isUnauthorized)
    assertEquals(listOf("passkey.registered", "passkey.registered", "recovery.completed", "recovery.completed").sorted(),
      sql.fetch("select action from audit_events where target_id = ?", user.id.value.toString()).map { it.get("action", String::class.java) }.sorted())
    val durable = sql.fetch("select result from command_requests").toString() + sql.fetch("select details from audit_events").toString()
    listOf(otp, code, newCode, finalCode, "Laptop").forEach { assertFalse(durable.contains(it)) }
  }

  @Test fun `outbox insertion failure rolls back OTP and command reservation then same key retries`() {
    val email = email(); val key = UUID.randomUUID(); val correlation = UUID.randomUUID()
    sql.execute("alter table mail_outbox add constraint test_outbox_failure check (correlation_id <> '$correlation')")
    try {
      assertFails { request("enrollment", email, key = key, correlation = correlation) }
      assertNull(tx.execute { it.otpChallenges.find(email, VerificationPurpose.ENROLLMENT) })
      assertEquals(0, sql.fetchCount(sql.selectFrom("command_requests").where("idempotency_key = ?", key)))
      assertEquals(0, sql.fetchCount(sql.selectFrom("mail_outbox").where("correlation_id = ?", correlation)))
    } finally { sql.execute("alter table mail_outbox drop constraint test_outbox_failure") }
    request("enrollment", email, key = key, correlation = correlation).andExpect(status().isAccepted)
    assertTrue(readOtp(email).matches(Regex("[0-9]{8}")))
  }

  @Test fun `audit failure rolls back registration challenge credential activation and recovery secret`() {
    val email = email()
    val session = verify("enrollment", email, deliver("enrollment", email)).andExpect(status().isOk).andReturn().request.session as MockHttpSession
    val user = tx.execute { it.users.lockByEmail(email)!! }
    val body = json.writeValueAsString(registration(session, Fixture()))
    val key = UUID.randomUUID(); val requestId = UUID.randomUUID(); val correlation = UUID.randomUUID()
    sql.execute("alter table audit_events add constraint test_audit_failure check (request_id <> '$requestId')")
    try {
      assertFails { postJson("/webauthn/register", body, session, key, requestId = requestId, correlation = correlation) }
      tx.execute {
        assertEquals(UserStatus.PENDING_PASSKEY, it.users.findById(user.id)!!.status)
        assertTrue(it.credentials.findByUserId(user.id).isEmpty())
        assertNull(it.recoveryCodes.findByUserId(user.id))
      }
      assertNull(sql.fetchOne("select consumed_at from webauthn_challenges where user_id = ?", user.id.value)!!.get("consumed_at"))
      assertEquals(0, sql.fetchCount(sql.selectFrom("audit_events").where("request_id = ?", requestId)))
      assertEquals(0, sql.fetchCount(sql.selectFrom("command_requests").where("idempotency_key = ?", key)))
    } finally { sql.execute("alter table audit_events drop constraint test_audit_failure") }
    postJson("/webauthn/register", body, session, key, requestId = requestId, correlation = correlation)
      .andExpect(status().isOk).andExpect(jsonPath("$.recoveryCode").isString)
    postJson("/webauthn/register", body, session, key).andExpect(status().isOk).andExpect(jsonPath("$.recoveryCode").doesNotExist())
    val audit = sql.fetchOne("select action, correlation_id from audit_events where request_id = ?", requestId)!!
    assertEquals("passkey.registered", audit.get("action")); assertEquals(correlation, audit.get("correlation_id"))
    assertNotNull(sql.fetchOne("select consumed_at from webauthn_challenges where user_id = ?", user.id.value)!!.get("consumed_at"))
  }

  @Test fun `only normalized allowlisted enrollee gains ADMIN and live recent Passkey actor may grant roles`() {
    val admin = enroll(EmailAddress("Admin@Example.test"))
    val ordinary = enroll(EmailAddress("admin+other@example.test"))
    val roles = ManageRoles(tx, context.getBean(ClockPort::class.java), context.getBean(SecureRandomPort::class.java))
    val metadata = CommandMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    assertEquals(setOf(UserRole.USER, UserRole.ADMIN), principal(admin.second).actor.roles)
    assertEquals(setOf(UserRole.USER), principal(ordinary.second).actor.roles)
    assertEquals(SecurityChangeResult.Forbidden, roles.grant(principal(ordinary.second), ordinary.first, UserRole.ADMIN, metadata))
    assertEquals(SecurityChangeResult.Changed, roles.grant(principal(admin.second), ordinary.first, UserRole.ADMIN, metadata))
    assertTrue(UserRole.ADMIN in principal(ordinary.second).actor.roles)
    now = now.plusSeconds(301)
    assertEquals(SecurityChangeResult.Forbidden, roles.revoke(principal(admin.second), ordinary.first, UserRole.ADMIN,
      CommandMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
    assertEquals(1, sql.fetchCount(sql.selectFrom("audit_events").where("action = 'role.granted' and request_id = ?", metadata.requestId)))
  }

  private fun enroll(email: EmailAddress): Pair<UserId, MockHttpSession> {
    val session = verify("enrollment", email, deliver("enrollment", email)).andExpect(status().isOk).andReturn().request.session as MockHttpSession
    val key = Fixture(); complete(session, registration(session, key))
    val user = tx.execute { it.users.lockByEmail(email)!! }
    return user.id to login(key, tx.execute { it.users.findUserHandle(user.id)!! }, session, 1)
  }
  private fun principal(session: MockHttpSession): SessionPrincipal = context.getBean(ActorResolver::class.java).sessionPrincipal(
    (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext).authentication)!!
  private fun deliver(purpose: String, email: EmailAddress): String {
    request(purpose, email).andExpect(status().isAccepted).andExpect(content().json("""{"accepted":true}"""))
    return readOtp(email)
  }
  private fun readOtp(email: EmailAddress): String = runBlocking {
    context.getBean(MailOutboxDispatcher::class.java).dispatch()
    val delivered = context.getBean(RecordingMailTransport::class.java).messages().last { it.recipients.to.single().address == email.value }
    val challenge = tx.execute { it.otpChallenges.find(email, VerificationPurpose.valueOf(delivered.tags.single()))!!.challenge!! }
    assertEquals(challenge.deliveryId.value, delivered.id.value)
    val row = sql.fetchOne("select state, payload_ciphertext from mail_outbox where message_id = ?", delivered.id.value)!!
    assertEquals("ACCEPTED", row.get("state")); assertNull(row.get("payload_ciphertext"))
    Regex("[0-9]{8}").find(delivered.content.text!!)!!.value
  }
  private fun options(session: MockHttpSession = MockHttpSession()): Pair<JsonNode, MockHttpSession> {
    val response = postJson("/webauthn/authenticate/options", "{}", session).andExpect(status().isOk).andReturn()
    val options = json.readTree(response.response.contentAsString)
    assertEquals(0, options["allowCredentials"].size()); assertEquals("required", options["userVerification"].asText())
    return options to (response.request.session as MockHttpSession)
  }
  private fun login(key: Fixture, handle: ByteArray, session: MockHttpSession = MockHttpSession(), count: Int): MockHttpSession {
    val (options, browser) = options(session); val oldId = browser.id
    val body = json.writeValueAsString(key.assertion(options["challenge"].asText(), handle, count = count))
    mvc.perform(post("/login/webauthn").session(browser).secure(true).contentType("application/json").content(body)).andExpect(status().isForbidden)
    postJson("/login/webauthn", body, browser).andExpect(status().isOk)
    assertNotEquals(oldId, browser.id)
    mvc.perform(get("/auth/session").secure(true).session(browser)).andExpect(status().isOk)
    assertGraphqlAllowed(browser)
    return browser
  }
  private fun assertGraphqlAllowed(session: MockHttpSession) {
    val query = mvc.perform(post("/graphql").secure(true).session(session).contentType("application/json")
      .content("""{"query":"{ jobPostings { totalCount } }"}""")).andExpect(status().isOk).andExpect(request().asyncStarted()).andReturn()
    mvc.perform(asyncDispatch(query)).andExpect(status().isOk)
  }
  private fun assertGraphqlDenied(session: MockHttpSession) {
    mvc.perform(post("/graphql").secure(true).session(session).contentType("application/json")
      .content("""{"query":"{ jobPostings { totalCount } }"}""")).andExpect(status().isForbidden)
  }
  private fun registration(session: MockHttpSession, key: Fixture): Map<String, Any> {
    val options = json.readTree(postJson("/webauthn/register/options", "{}", session).andExpect(status().isOk).andReturn().response.contentAsString)
    return key.registration(options["challenge"].asText())
  }
  private fun complete(session: MockHttpSession, body: Map<String, Any>): String {
    val command = UUID.randomUUID(); val encoded = json.writeValueAsString(body)
    val result = postJson("/webauthn/register", encoded, session, command).andExpect(status().isOk).andReturn()
    postJson("/webauthn/register", encoded, session, command).andExpect(status().isOk).andExpect(jsonPath("$.recoveryCode").doesNotExist())
    return json.readTree(result.response.contentAsString)["recoveryCode"].asText()
  }
}
