package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@ExtendWith(OutputCaptureExtension::class)
class EnrollmentHttpTest : OtpHttpSupport() {
  @Test fun `device and socket IP budgets are independent of email and forwarded spoofing`() {
    val first = request("recovery", email()).andExpect(status().isAccepted).andReturn().response
    val cookie = first.getHeader("Set-Cookie")!!
    assertContains(cookie, "Secure"); assertContains(cookie, "HttpOnly"); assertContains(cookie, "SameSite=Lax")
    val value = cookie.substringBefore(';').substringAfter('=')
    val device = jakarta.servlet.http.Cookie("__Host-finds-device", value)
    fun send(index: Int, reuseDevice: Boolean, ip: String) = mvc.perform(post("/auth/recovery/otp/request").secure(true)
      .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
      .with { it.remoteAddr = ip; it }.header("X-Forwarded-For", "198.51.100.$index")
      .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
      .content(json.writeValueAsString(mapOf("email" to email().value)))
      .also { if (reuseDevice) it.cookie(device) })
    repeat(9) { send(it, true, "192.0.2.$it").andExpect(status().isAccepted) }
    send(10, true, "192.0.2.10").andExpect(status().isTooManyRequests)
    repeat(30) { send(it, false, "203.0.113.1").andExpect(status().isAccepted) }
    send(31, false, "203.0.113.1").andExpect(status().isTooManyRequests)
    val rows = context.getBean(org.jooq.DSLContext::class.java).fetch("select bucket_key from auth_rate_buckets").toString()
    assertFalse(rows.contains(value)); assertFalse(rows.contains("203.0.113.1"))
  }
  @Test fun `real restricted cookie rotates and CSRF must be reacquired after proof verification`() {
    val email = email(); challenge(email, VerificationPurpose.ENROLLMENT)
    val base = "http://localhost:${context.environment.getProperty("local.server.port")}"; val client = java.net.http.HttpClient.newHttpClient()
    fun send(path: String, cookie: String? = null, token: String? = null, body: String? = null): java.net.http.HttpResponse<String> {
      val builder = java.net.http.HttpRequest.newBuilder(java.net.URI(base + path))
      cookie?.let { builder.header("Cookie", it) }; token?.let { builder.header("X-CSRF-TOKEN", it) }
      if (body != null) builder.header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)) else builder.GET()
      return client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
    }
    val csrf = send("/auth/csrf"); val oldCookie = csrf.headers().firstValue("set-cookie").orElseThrow().substringBefore(';')
    val oldToken = json.readTree(csrf.body())["token"].asText()
    val verified = send("/auth/enrollment/otp/verify", oldCookie, oldToken,
      json.writeValueAsString(mapOf("email" to email.value, "otp" to "12345678")))
    assertEquals(200, verified.statusCode())
    val sessionCookie = verified.headers().allValues("set-cookie").single { it.startsWith("JSESSIONID=") }
    assertContains(sessionCookie, "Secure"); assertContains(sessionCookie, "HttpOnly"); assertContains(sessionCookie, "SameSite=Lax")
    val newCookie = sessionCookie.substringBefore(';'); assertNotEquals(oldCookie, newCookie)
    assertEquals(403, send("/webauthn/register/options", newCookie, oldToken, "{}").statusCode())
    assertEquals(401, send("/auth/session", newCookie).statusCode())
    val renewed = json.readTree(send("/auth/csrf", newCookie).body())["token"].asText()
    assertEquals(200, send("/webauthn/register/options", newCookie, renewed, "{}").statusCode())
  }
  @Test fun `registered and unknown requests share public body timing and normalized email throttle`(output: CapturedOutput) {
    val registered = seed(true).email
    val unknown = email()
    val elapsed = mutableListOf<Long>()
    val responses = listOf(registered, unknown).map { email ->
      val start = System.nanoTime()
      val result = request("enrollment", email).andExpect(status().isAccepted)
        .andExpect(header().string("Cache-Control", "no-store")).andReturn().response.contentAsString
      elapsed += (System.nanoTime() - start) / 1_000_000
      result
    }
    assertTrue(elapsed.all { it in 200..1500 }, "both account states have the bounded public timing class")
    assertTrue(kotlin.math.abs(elapsed[0] - elapsed[1]) < 500, "account state must not select a different timing class")
    assertEquals(responses[0], responses[1])
    assertEquals("{\"accepted\":true}", responses[0])
    listOf(registered, unknown).forEach { email ->
      repeat(2) { request("enrollment", email).andExpect(status().isAccepted) }
      val limited = request("enrollment", EmailAddress(email.value.uppercase())).andExpect(status().isTooManyRequests)
        .andExpect(content().contentTypeCompatibleWith("application/problem+json")).andReturn().response
      assertTrue(limited.getHeader("Retry-After")!!.toInt() in 1..60)
      assertFalse(output.all.contains(email.value)); assertFalse(limited.contentAsString.contains(email.value))
    }
  }
  @Test fun `request metadata replay preserves encrypted delivery and original correlation`() {
    val email = email(); val key = UUID.randomUUID(); val correlation = UUID.randomUUID()
    request("enrollment", email, key = key, correlation = correlation).andExpect(status().isAccepted)
    val before = tx.execute { it.otpChallenges.find(email, VerificationPurpose.ENROLLMENT)!! }
    request("enrollment", email, key = key).andExpect(status().isAccepted)
    val replay = tx.execute { it.otpChallenges.find(email, VerificationPurpose.ENROLLMENT)!! }
    assertEquals(before.challenge!!.deliveryId, replay.challenge!!.deliveryId)
    assertContentEquals(before.challenge!!.hash.bytes, replay.challenge!!.hash.bytes)
    assertEquals(before.copy(challenge = null), replay.copy(challenge = null))
    val db = context.getBean(org.jooq.DSLContext::class.java)
    val row = db.fetchOne("select correlation_id, payload_ciphertext from mail_outbox where message_id = ?", before.challenge!!.deliveryId.value)!!
    assertEquals(correlation, row.get("correlation_id", UUID::class.java))
    assertFalse(String(row.get("payload_ciphertext", ByteArray::class.java)!!).contains(email.value))
  }
  @Test fun `malformed requests and missing CSRF fail without reflecting secrets`() {
    val path = "/auth/enrollment/otp/request"
    mvc.perform(post(path).secure(true).contentType("application/json").content("{}"))
      .andExpect(status().isForbidden).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
    listOf("null", "{}", "[]", """{"email":"secret-not-an-email"}""").forEach { body ->
      val response = postJson(path, body).andExpect(status().isBadRequest)
        .andExpect(content().contentTypeCompatibleWith("application/problem+json")).andReturn().response
      assertFalse(response.contentAsString.contains("secret-not-an-email"))
    }
    mvc.perform(post(path).secure(true).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
      .contentType("application/json").content("""{"email":"valid@example.test"}"""))
      .andExpect(status().isBadRequest)
  }
  @Test fun `verified OTP replaces browser session with enrollment scope and never logs in`() {
    val email = email(); challenge(email, VerificationPurpose.ENROLLMENT)
    val old = MockHttpSession().apply { setAttribute("untrusted", "must not survive") }
    val response = verify("enrollment", email, session = old).andExpect(status().isOk).andReturn()
    val session = response.request.session as MockHttpSession
    assertNotEquals(old.id, session.id); assertNull(session.getAttribute("untrusted"))
    assertFalse(response.response.contentAsString.contains(email.value))
    assertRestricted(session, RestrictedSessionScope.ENROLLMENT)
    val user = tx.execute { it.users.lockByEmail(email)!! }
    assertEquals(UserStatus.PENDING_PASSKEY, user.status)
    assertTrue(tx.execute { it.userSessions.findByUserId(user.id).isEmpty() })
    verify("enrollment", email).andExpect(status().isUnauthorized)
  }
  @Test fun `five failures survive reissue and unlock only after fifteen minute cooldown`() {
    val email = email(); challenge(email, VerificationPurpose.ENROLLMENT)
    repeat(4) { verify("enrollment", email, "00000000").andExpect(status().isUnauthorized) }
    request("enrollment", email).andExpect(status().isAccepted)
    verify("enrollment", email, "00000000").andExpect(status().isUnauthorized)
    val locked = tx.execute { it.otpChallenges.find(email, VerificationPurpose.ENROLLMENT)!! }
    assertEquals(5, locked.consecutiveFailures); assertEquals(now.plusSeconds(900), locked.lockedUntil)
    request("enrollment", email).andExpect(status().isAccepted)
    val stillLocked = tx.execute { it.otpChallenges.find(email, VerificationPurpose.ENROLLMENT)!! }
    assertEquals(locked.challenge!!.deliveryId, stillLocked.challenge!!.deliveryId)
    assertEquals(locked.copy(challenge = null), stillLocked.copy(challenge = null))
    now = now.plusSeconds(899)
    verify("enrollment", email).andExpect(status().isUnauthorized)
    now = now.plusSeconds(1); challenge(email, VerificationPurpose.ENROLLMENT)
    verify("enrollment", email).andExpect(status().isOk)
    assertEquals(0, tx.execute { it.otpChallenges.find(email, VerificationPurpose.ENROLLMENT)!!.consecutiveFailures })
  }
}
