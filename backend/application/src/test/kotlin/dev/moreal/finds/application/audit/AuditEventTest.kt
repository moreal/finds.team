package dev.moreal.finds.application.audit

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.domain.identity.UserRole
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditEventTest {
  private val requestId = UUID.fromString("11111111-1111-4111-8111-111111111111")
  private val correlationId = UUID.fromString("22222222-2222-4222-8222-222222222222")
  private val idempotencyKey = UUID.fromString("33333333-3333-4333-8333-333333333333")
  private val userId = UUID.fromString("44444444-4444-4444-8444-444444444444")
  private val now = Instant.parse("2026-09-22T12:00:00Z")

  @Test
  fun `command metadata parses canonical UUIDs without retaining invalid input`() {
    val metadata = CommandMetadata.parse(
      requestId.toString(), correlationId.toString(), idempotencyKey.toString(),
    )

    assertEquals(requestId, metadata.requestId)
    assertEquals(correlationId, metadata.correlationId)
    assertEquals(idempotencyKey, metadata.idempotencyKey)
    assertFailsWith<IllegalArgumentException> {
      CommandMetadata.parse("invalid-token", correlationId.toString(), idempotencyKey.toString())
    }
    assertFailsWith<IllegalArgumentException> {
      CommandMetadata.parse(requestId.toString(), "1-1-1-1-1", idempotencyKey.toString())
    }
    assertFailsWith<IllegalArgumentException> {
      CommandMetadata.parse(requestId.toString(), correlationId.toString(), "cookie=secret")
    }
  }

  @Test
  fun `audit target requires a stable nonblank identifier`() {
    assertFailsWith<IllegalArgumentException> { event(targetId = " ") }
    assertFailsWith<IllegalArgumentException> { event(targetType = "\t") }
    assertFailsWith<IllegalArgumentException> { event(targetId = "person@example.com") }
    assertFailsWith<IllegalArgumentException> { event(targetId = "{" + "body".repeat(100) + "}") }
  }

  @Test
  fun `audit actions expose stable wire names`() {
    assertEquals("role.granted", AuditAction.ROLE_GRANTED.wireName)
    assertEquals("career_site.registered", AuditAction.CAREER_SITE_REGISTERED.wireName)
    assertEquals("crawl.manually_triggered", AuditAction.MANUAL_CRAWL_TRIGGERED.wireName)
    assertEquals("passkey.registered", AuditAction.PASSKEY_REGISTERED.wireName)
    assertEquals("recovery.completed", AuditAction.ACCOUNT_RECOVERY_COMPLETED.wireName)
    assertEquals(AuditAction.ROLE_GRANTED, AuditAction.fromWireName("role.granted"))
    assertFailsWith<IllegalArgumentException> { AuditAction.fromWireName("otp.secret") }
  }

  @Test
  fun `recent authentication requires a passkey within the inclusive five minute window`() {
    val passkeyActor = Actor.User(userId, setOf(UserRole.USER), now, AuthenticationStrength.PASSKEY)
    assertTrue(passkeyActor.hasRecentPasskeyAuthentication(now.plus(Duration.ofMinutes(5))))
    assertFalse(passkeyActor.hasRecentPasskeyAuthentication(now.plus(Duration.ofMinutes(5)).plusNanos(1)))
    assertFalse(passkeyActor.hasRecentPasskeyAuthentication(now.minusNanos(1)))
    assertFalse(
      Actor.User(userId, setOf(UserRole.USER), now, AuthenticationStrength.RECOVERY_PROOFS)
        .hasRecentPasskeyAuthentication(now.plusSeconds(1)),
    )
  }

  @Test
  fun `audit detail keys are allowlisted by action and secret shaped keys are rejected`() {
    for (key in listOf("otp", "token", "cookie", "credential", "recoveryCode")) {
      assertFailsWith<IllegalArgumentException> {
        AuditDetails.from(AuditAction.ROLE_GRANTED, mapOf(key to "secret"))
      }
    }
    assertFailsWith<IllegalArgumentException> {
      AuditDetails.from(AuditAction.ROLE_GRANTED, mapOf("provider" to "FLEX"))
    }
    assertFailsWith<IllegalArgumentException> {
      AuditDetails.from(AuditAction.ROLE_GRANTED, mapOf("role" to "person@example.com"))
    }
    assertEquals(
      mapOf("role" to "ADMIN"),
      AuditDetails.from(AuditAction.ROLE_GRANTED, mapOf("role" to "ADMIN")).fields,
    )
    assertEquals(
      mapOf("provider" to "FLEX"),
      AuditDetails.from(AuditAction.CAREER_SITE_REGISTERED, mapOf("provider" to "FLEX")).fields,
    )
  }

  @Test
  fun `audit details cannot be mutated after validation`() {
    val source = mutableMapOf("role" to "ADMIN")
    val details = AuditDetails.from(AuditAction.ROLE_GRANTED, source)
    source["role"] = "USER"
    assertEquals(mapOf("role" to "ADMIN"), details.fields)
    assertFailsWith<UnsupportedOperationException> {
      (details.fields as MutableMap<String, String>)["role"] = "USER"
    }
    assertFailsWith<IllegalArgumentException> {
      event(action = AuditAction.ROLE_GRANTED, details = AuditDetails.from(AuditAction.CAREER_SITE_REGISTERED, mapOf("provider" to "FLEX")))
    }
  }

  @Test
  fun `audit diagnostics omit target identifiers and details`() {
    val auditEvent = event(targetId = "sessionSecret123")
    val diagnostic = auditEvent.toString()
    assertFalse(diagnostic.contains("sessionSecret123"))
    assertTrue(diagnostic.contains("career_site.registered"))
  }

  private fun event(
    action: AuditAction = AuditAction.CAREER_SITE_REGISTERED,
    targetType: String = "career_site",
    targetId: String = "123",
    details: AuditDetails = AuditDetails.empty(action),
  ) = AuditEvent(
    id = UUID.fromString("55555555-5555-4555-8555-555555555555"),
    schemaVersion = 1,
    occurredAt = now,
    actor = Actor.User(userId, setOf(UserRole.ADMIN), now, AuthenticationStrength.PASSKEY),
    action = action,
    targetType = targetType,
    targetId = targetId,
    requestId = requestId,
    correlationId = correlationId,
    outcome = AuditOutcome.SUCCEEDED,
    details = details,
  )
}
