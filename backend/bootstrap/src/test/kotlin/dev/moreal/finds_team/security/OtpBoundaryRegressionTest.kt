package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.RecoveryCode
import jakarta.servlet.http.Cookie
import java.util.Base64
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class OtpBoundaryRegressionTest : OtpHttpSupport() {
  @Test fun `invalid device cookies are replaced without bypassing normalized email or IP budgets`() {
    val validHeader = request("recovery", email()).andExpect(status().isAccepted).andReturn().response.getHeader("Set-Cookie")!!
    val valid = validHeader.substringBefore(';').substringAfter('=')
    val parts = valid.split('.')
    fun signed(expiry: Long): String {
      val mac = hashes.hash(IdentityHashPurpose.COMMAND_SCOPE, "http.device", "${parts[0]}.$expiry")
      return "${parts[0]}.${mac.pepperVersion}.$expiry.${Base64.getUrlEncoder().withoutPadding().encodeToString(mac.bytes)}"
    }
    val invalid = listOf(
      "malformed" to listOf("broken"),
      "tampered" to listOf(valid.replaceRange(0, 1, if (valid[0] == 'A') "B" else "A")),
      "expired" to listOf(signed(now.epochSecond)),
      "future" to listOf(signed(now.epochSecond + 2592001)),
      "unknown version" to listOf("${parts[0]}.999999.${parts[2]}.${parts[3]}"),
      "invalid Base64" to listOf("${parts[0]}.${parts[1]}.${parts[2]}.!"),
      "wrong digest length" to listOf("${parts[0]}.${parts[1]}.${parts[2]}.YQ"),
      "duplicate" to listOf(valid, valid),
      "oversized" to listOf("a".repeat(161)),
    )
    fun send(values: List<String>, address: String, ip: String, expected: Int) {
      val response = mvc.perform(post("/auth/recovery/otp/request").secure(true).with(csrf())
        .cookie(*values.map { Cookie(OtpHttpBoundary.DEVICE_COOKIE, it) }.toTypedArray())
        .with { it.remoteAddr = ip; it }.header("Idempotency-Key", UUID.randomUUID())
        .contentType("application/json").content(json.writeValueAsString(mapOf("email" to address))))
        .andExpect(status().`is`(expected)).andReturn().response
      val replacement = response.getHeaders("Set-Cookie").single { it.startsWith("${OtpHttpBoundary.DEVICE_COOKIE}=") }
      listOf("Secure", "HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=2592000").forEach { assertContains(replacement, it) }
      assertFalse(replacement.contains("Domain="))
      assertTrue(values.none { replacement.substringBefore(';').substringAfter('=') == it })
    }
    invalid.forEachIndexed { index, (_, values) ->
      val address = email().value
      repeat(3) { send(values, address, "192.0.2.${index * 4 + it}", 202) }
      send(values, address.uppercase(), "192.0.2.${index * 4 + 3}", 429)
    }
    repeat(30) { send(invalid[it % invalid.size].second, email().value, "198.51.100.40", 202) }
    send(invalid.last().second, email().value, "198.51.100.40", 429)
  }

  @Test fun `warmed interleaved registered and unknown requests share timing floor and response`() {
    // Warm both database paths before sampling, alternate the order, and avoid rate-limit windows.
    val user = seed(true)
    val registered = user.email
    tx.execute {
      it.users.lockByEmail(registered)
      it.recoveryCodes.save(RecoveryCodeHash(user.id, hashes.hash(IdentityHashPurpose.RECOVERY_CODE,
        user.id.value.toString(), RecoveryCode.fromBytes(ByteArray(16) { 42 }).format()), now))
    }
    val unknown = email()
    listOf("enrollment", "recovery").forEach { purpose ->
      repeat(2) {
        request(purpose, registered).andExpect(status().isAccepted)
        request(purpose, unknown).andExpect(status().isAccepted)
        now = now.plusSeconds(61)
      }
      val samples = mutableMapOf(true to mutableListOf<Long>(), false to mutableListOf<Long>())
      repeat(8) { round ->
        val order = if (round % 2 == 0) listOf(true, false) else listOf(false, true)
        order.forEach { exists ->
          val started = System.nanoTime()
          val body = request(purpose, if (exists) registered else unknown).andExpect(status().isAccepted)
            .andExpect(header().string("Cache-Control", "no-store")).andReturn().response.contentAsString
          samples.getValue(exists) += (System.nanoTime() - started) / 1_000_000
          assertEquals("{\"accepted\":true}", body)
        }
        now = now.plusSeconds(61)
      }
      samples.values.flatten().forEach { assertTrue(it >= 200, "common floor must apply") }
      val medians = samples.values.map { it.sorted()[it.size / 2] }
      assertTrue(kotlin.math.abs(medians[0] - medians[1]) < 100, "$purpose warmed timing classes diverged: $medians")
    }
  }
}
