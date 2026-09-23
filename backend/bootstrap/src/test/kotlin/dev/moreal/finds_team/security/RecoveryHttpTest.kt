package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.system.CapturedOutput

@ExtendWith(OutputCaptureExtension::class)
class RecoveryHttpTest : OtpHttpSupport() {
  private fun recovery(user: User): String {
    val code = RecoveryCode.fromBytes(ByteArray(16) { 42 }).format()
    tx.execute { it.users.lockByEmail(user.email); it.recoveryCodes.save(RecoveryCodeHash(user.id,
      hashes.hash(IdentityHashPurpose.RECOVERY_CODE, user.id.value.toString(), code), now)) }
    return code
  }
  @Test fun `HTTP proof and delivery diagnostics never contain email OTP recovery or digest`(output: CapturedOutput) {
    val user = seed(true); val code = recovery(user); challenge(user.email, VerificationPurpose.RECOVERY)
    val loggerNames = listOf("org.jooq.tools.LoggerListener", "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor")
    val loggers = loggerNames.map { org.slf4j.LoggerFactory.getLogger(it) as ch.qos.logback.classic.Logger }
    val levels = loggers.map { it.level }
    try {
      loggers.forEach { it.level = ch.qos.logback.classic.Level.DEBUG }
      request("recovery", user.email).andExpect(status().isAccepted)
      verify("recovery", user.email, "87654321", code).andExpect(status().isUnauthorized)
      assertFalse(output.all.contains(user.email.value)); assertFalse(output.all.contains("87654321")); assertFalse(output.all.contains(code))
      val digest = hashes.hash(IdentityHashPurpose.RECOVERY_CODE, user.id.value.toString(), code).bytes
      assertFalse(output.all.contains(java.util.Base64.getEncoder().encodeToString(digest)))
    } finally { loggers.zip(levels).forEach { (logger, level) -> logger.level = level } }
  }
  @Test fun `registered and unknown recovery requests share public status body and timing`() {
    val user = seed(true); recovery(user)
    val elapsed = mutableListOf<Long>()
    val bodies = listOf(user.email, email()).map { email ->
      val start = System.nanoTime()
      val response = request("recovery", email).andExpect(status().isAccepted).andReturn().response
      elapsed += (System.nanoTime() - start) / 1_000_000
      assertFalse(response.contentAsString.contains(email.value))
      repeat(2) { request("recovery", email).andExpect(status().isAccepted) }
      request("recovery", email).andExpect(status().isTooManyRequests)
      response.contentAsString
    }
    assertTrue(elapsed.all { it in 200..1500 }); assertTrue(kotlin.math.abs(elapsed[0] - elapsed[1]) < 500)
    assertEquals(bodies[0], bodies[1]); assertEquals("{\"accepted\":true}", bodies[0])
  }
  @Test fun `recovery reissue waits sixty seconds and retains failure count`() {
    val user = seed(true); recovery(user)
    request("recovery", user.email).andExpect(status().isAccepted)
    val first = tx.execute { it.otpChallenges.find(user.email, VerificationPurpose.RECOVERY)!! }
    verify("recovery", user.email, "00000000").andExpect(status().isUnauthorized)
    now = now.plusSeconds(59)
    request("recovery", user.email).andExpect(status().isAccepted)
    assertEquals(first.challenge!!.deliveryId, tx.execute { it.otpChallenges.find(user.email, VerificationPurpose.RECOVERY)!!.challenge!!.deliveryId })
    now = now.plusSeconds(1)
    request("recovery", user.email).andExpect(status().isAccepted)
    val next = tx.execute { it.otpChallenges.find(user.email, VerificationPurpose.RECOVERY)!! }
    assertNotEquals(first.challenge!!.deliveryId, next.challenge!!.deliveryId); assertEquals(1, next.consecutiveFailures)
  }
  @Test fun `OTP alone never logs in and two proofs only authorize restricted registration`() {
    val user = seed(true); val code = recovery(user); challenge(user.email, VerificationPurpose.RECOVERY)
    verify("recovery", user.email).andExpect(status().isUnauthorized)
    verify("recovery", user.email, recovery = code).andExpect(status().isOk).andReturn().let {
      assertRestricted(it.request.session as MockHttpSession, RestrictedSessionScope.RECOVERY)
    }
    assertTrue(tx.execute { it.userSessions.findByUserId(user.id).isEmpty() })
    assertEquals(UserStatus.ACTIVE, tx.execute { it.users.findById(user.id)!!.status })
  }
  @Test fun `completed recovery replay is secret-free original-session bound and cannot authorize fresh work`() {
    val user = seed(true); val code = recovery(user); challenge(user.email, VerificationPurpose.RECOVERY)
    val session = verify("recovery", user.email, recovery = code).andExpect(status().isOk).andReturn().request.session as MockHttpSession
    val options = mvc.perform(post("/webauthn/register/options").session(session).secure(true).with(csrf())).andExpect(status().isOk).andReturn()
    val fixture = Fixture(); val body = json.writeValueAsString(fixture.registration(json.readTree(options.response.contentAsString)["challenge"].asText()))
    val key = UUID.randomUUID(); val requestId = UUID.randomUUID(); val correlation = UUID.randomUUID()
    postJson("/webauthn/register", body, session, key, correlation = correlation, requestId = requestId)
      .andExpect(status().isOk).andExpect(jsonPath("$.recoveryCode").isString)
    postJson("/webauthn/register", body, session, key).andExpect(status().isOk).andExpect(jsonPath("$.recoveryCode").doesNotExist())
    val db = context.getBean(org.jooq.DSLContext::class.java)
    val audit = db.fetchOne("select request_id, correlation_id from audit_events where target_id = ? and action = 'recovery.completed'", user.id.value.toString())!!
    assertEquals(requestId, audit.get("request_id", UUID::class.java)); assertEquals(correlation, audit.get("correlation_id", UUID::class.java))
    postJson("/webauthn/register", body, session).andExpect(status().isUnauthorized)
    mvc.perform(post("/webauthn/register/options").session(session).secure(true).with(csrf())).andExpect(status().isUnauthorized)
    val other = MockHttpSession(); session.attributeNames.toList().forEach { other.setAttribute(it, session.getAttribute(it)) }
    postJson("/webauthn/register", body, other, key).andExpect(status().isUnauthorized)
    val changed = json.writeValueAsString(fixture.registration("changed"))
    postJson("/webauthn/register", changed, session, key).andExpect(status().isConflict)
    now = now.plusSeconds(87000)
    postJson("/webauthn/register", body, session, key).andExpect(status().isUnauthorized)
    assertEquals(setOf(fixture.id), tx.execute { it.users.findById(user.id)!!.credentials })
  }
  @Test fun `application completion conflict is mapped to 409 without consuming the ceremony`() {
    val user = seed(true); val code = recovery(user); challenge(user.email, VerificationPurpose.RECOVERY)
    val session = verify("recovery", user.email, recovery = code).andExpect(status().isOk).andReturn().request.session as MockHttpSession
    val options = mvc.perform(post("/webauthn/register/options").session(session).secure(true).with(csrf())).andExpect(status().isOk).andReturn()
    val body = json.writeValueAsString(Fixture().registration(json.readTree(options.response.contentAsString)["challenge"].asText()))
    val key = UUID.randomUUID()
    tx.execute {
      val requestKey = CommandRequestKey(user.id.value.toString(), "recovery.complete", key)
      it.commandRequests.reserve(CommandRequest(requestKey, CommandRequestHash("0".repeat(64)), now, CommandRetention.AUDIT))
      it.commandRequests.complete(requestKey, StoredCommandResult(1, "recovery.complete", "REJECTED"))
    }
    postJson("/webauthn/register", body, session, key).andExpect(status().isConflict)
      .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
    postJson("/webauthn/register", body, session).andExpect(status().isOk)
  }
}
