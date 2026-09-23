package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.*
import java.util.UUID
import kotlin.test.*

class IdentityCommandResultCodecTest {
  @Test fun `identity result codec admits each operation only its secret free outcomes`() {
    val outcomes = mapOf(
      "enrollment.otp.request" to listOf("ACCEPTED"), "recovery.otp.request" to listOf("ACCEPTED"),
      "recovery.complete" to listOf("COMPLETED", "REJECTED"), "recovery_code.rotate" to listOf("ROTATED"),
      "role.grant" to listOf("CHANGED", "UNCHANGED", "FORBIDDEN", "NOT_FOUND", "REQUIRED_USER_ROLE"),
      "role.revoke" to listOf("CHANGED", "UNCHANGED", "FORBIDDEN", "NOT_FOUND", "REQUIRED_USER_ROLE"),
      "passkey.remove" to listOf("CHANGED", "NOT_FOUND", "LAST_CREDENTIAL"),
      "passkey.rename" to listOf("CHANGED", "UNCHANGED", "NOT_FOUND"),
      "passkey.register" to listOf("CHANGED", "FORBIDDEN", "CREDENTIAL_ALREADY_EXISTS"),
      "session.revoke" to listOf("CHANGED", "UNCHANGED", "NOT_FOUND", "SIGNED_OUT"),
      "session.revoke_others" to listOf("CHANGED", "UNCHANGED"),
    )
    for ((operation, values) in outcomes) {
      for (outcome in values) {
        val encoded = CommandResultCodec.encode(operation, StoredCommandResult(1, operation, outcome))
        assertEquals(outcome, CommandResultCodec.decode(operation, encoded).outcome)
      }
      assertFailsWith<UnsupportedCommandResultException> { CommandResultCodec.encode(operation, StoredCommandResult(1, operation, "UNKNOWN")) }
      assertFailsWith<UnsupportedCommandResultException> { CommandResultCodec.encode(operation,
        StoredCommandResult(1, operation, values.first(), mapOf("user" to CommandResourceId.Uuid(UUID.randomUUID())))) }
    }
    val user = mapOf("user" to CommandResourceId.Uuid(UUID.randomUUID()))
    val complete = StoredCommandResult(1, "enrollment.complete", "COMPLETED", user)
    assertEquals(user, CommandResultCodec.decode("enrollment.complete", CommandResultCodec.encode("enrollment.complete", complete)).resourceIds)
    CommandResultCodec.encode("enrollment.complete", StoredCommandResult(1, "enrollment.complete", "REJECTED"))
    assertFailsWith<UnsupportedCommandResultException> { CommandResultCodec.encode("enrollment.complete", StoredCommandResult(1, "enrollment.complete", "COMPLETED")) }
    assertFailsWith<UnsupportedCommandResultException> { CommandResultCodec.encode("role.grant", StoredCommandResult(1, "role.grant", "SIGNED_OUT")) }
  }
}
