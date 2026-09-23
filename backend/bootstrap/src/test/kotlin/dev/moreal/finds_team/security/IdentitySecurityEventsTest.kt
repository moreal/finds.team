package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.VerificationPurpose
import java.util.UUID
import kotlin.test.*
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@ExtendWith(OutputCaptureExtension::class)
class IdentitySecurityEventsTest : OtpHttpSupport() {
  private val database get() = context.getBean(DSLContext::class.java)
  @Test fun `OTP rejection and throttling persist only categorical data after transactions exit`(output: CapturedOutput) {
    val address = email()
    challenge(address, VerificationPurpose.ENROLLMENT, "83649275")
    val correlation = UUID.randomUUID()
    val requestId = UUID.randomUUID()
    val secret = "76543210"
    val failure = postJson("/auth/enrollment/otp/verify", json.writeValueAsString(mapOf("email" to address.value, "otp" to secret)),
      ip = "198.51.100.91", correlation = correlation, requestId = requestId)
      .andExpect(status().isUnauthorized).andReturn().response.contentAsString
    val event = assertNotNull(database.fetchOne("SELECT action, details::text, request_id, correlation_id FROM security_events WHERE request_id = ?", requestId))
    assertEquals("identity.otp_failed", event.get(0))
    assertEquals("{\"reason\": \"INVALID_OTP\"}", event.get(1))
    assertEquals(correlation, event.get(3))
    val recovery = "RECOVERY-SECRET-MUST-NOT-APPEAR"
    val recoveryId = UUID.randomUUID()
    postJson("/auth/recovery/otp/verify", json.writeValueAsString(mapOf("email" to address.value, "otp" to secret, "recoveryCode" to recovery)),
      requestId = recoveryId).andExpect(status().isUnauthorized)
    assertEquals("identity.otp_failed", database.fetchValue("SELECT action FROM security_events WHERE request_id = ?", recoveryId))
    repeat(3) { request("enrollment", address).andExpect(status().isAccepted) }
    val throttleId = UUID.randomUUID()
    postJson("/auth/enrollment/otp/request", json.writeValueAsString(mapOf("email" to address.value)), requestId = throttleId)
      .andExpect(status().isTooManyRequests).andExpect(header().exists("Retry-After"))
    assertEquals("{\"reason\": \"THROTTLED\"}", database.fetchValue("SELECT details::text FROM security_events WHERE request_id = ?", throttleId))
    val rows = database.fetch("SELECT row_to_json(s)::text FROM security_events s").joinToString()
    for (value in listOf(address.value, "83649275", secret, recovery, "198.51.100.91")) {
      assertFalse(rows.contains(value), "secret in ledger")
      assertFalse(output.all.contains(value), "secret in logs")
      assertFalse(failure.contains(value), "secret in response")
    }
    assertEquals(0, database.fetchValue("SELECT count(*)::int FROM audit_events"))
  }
  @Test fun `event storage failure preserves malformed unauthorized forbidden and throttled responses`(output: CapturedOutput) {
    database.execute("ALTER TABLE security_events ADD CONSTRAINT reject_identity_events CHECK (false) NOT VALID")
    try {
      val address = email()
      verify("enrollment", address, "00000000").andExpect(status().isUnauthorized)
      postJson("/auth/enrollment/otp/verify", "{}").andExpect(status().isBadRequest)
      mvc.perform(post("/webauthn/authenticate/options").secure(true)).andExpect(status().isForbidden)
      repeat(3) { request("recovery", address).andExpect(status().isAccepted) }
      request("recovery", address).andExpect(status().isTooManyRequests)
      val metrics = context.getBean(io.micrometer.core.instrument.MeterRegistry::class.java)
      assertTrue(metrics.counter("finds.security.events", "outcome", "write_failed", "action", "identity.otp_failed").count() > 0)
      assertTrue(metrics.counter("finds.security.events", "outcome", "write_failed", "action", "identity.throttled").count() > 0)
      assertEquals(0, database.fetchValue("SELECT count(*)::int FROM audit_events"))
      assertFalse(output.all.contains(address.value))
      assertFalse(output.all.contains("reject_identity_events"))
    } finally { database.execute("ALTER TABLE security_events DROP CONSTRAINT reject_identity_events") }
  }
  @Test fun `runtime maintenance runs bounded batches while retaining sensitive requests`() {
    val scope = "SYSTEM:maintenance_test"
    tx.execute { transaction ->
      repeat(102) { n ->
        val request = dev.moreal.finds.application.port.CommandRequest(
          dev.moreal.finds.application.port.CommandRequestKey(scope, "enrollment.otp.request", UUID.randomUUID()),
          dev.moreal.finds.application.port.CommandRequestHash("a".repeat(64)), now.minusSeconds(86400),
          if (n == 101) dev.moreal.finds.application.port.CommandRetention.AUDIT else dev.moreal.finds.application.port.CommandRetention.ORDINARY)
        transaction.commandRequests.reserve(request)
        transaction.commandRequests.complete(request.key, dev.moreal.finds.application.port.StoredCommandResult(1, "enrollment.otp.request", "ACCEPTED"))
      }
    }
    val maintenance = context.getBean(dev.moreal.finds_team.crawl.ScheduledAuditMaintenance::class.java)
    maintenance.maintain()
    assertEquals(2, database.fetchValue("SELECT count(*)::int FROM command_requests WHERE scope = ?", scope))
    maintenance.maintain()
    assertEquals("AUDIT", database.fetchValue("SELECT retention FROM command_requests WHERE scope = ?", scope))
    val query = context.getBean(dev.moreal.finds.application.usecase.SearchAuditEvents::class.java)
    assertEquals(dev.moreal.finds.application.usecase.SearchAuditEventsResult.Forbidden,
      query.execute(null, dev.moreal.finds.application.port.AuditSearch()))
  }
}
