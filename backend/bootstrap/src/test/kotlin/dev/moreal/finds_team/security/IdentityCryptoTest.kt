package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.IdentityHashPurpose
import kotlin.test.*
import org.junit.jupiter.api.Test

class IdentityCryptoTest {
  @Test fun `purpose and framing separate hashes and old versions remain verifiable`() {
    val old = VersionedIdentityHashes(1, mapOf(1 to ByteArray(32) { 11 }))
    val rotated = VersionedIdentityHashes(2, mapOf(1 to ByteArray(32) { 11 }, 2 to ByteArray(32) { 22 }))
    val otp = old.hash(IdentityHashPurpose.ENROLLMENT_OTP, "ab", "c")
    assertTrue(rotated.matches(otp, IdentityHashPurpose.ENROLLMENT_OTP, "ab", "c"))
    assertFalse(rotated.matches(otp, IdentityHashPurpose.RECOVERY_OTP, "ab", "c"))
    assertFalse(rotated.matches(otp, IdentityHashPurpose.ENROLLMENT_OTP, "a", "bc"))
    assertFalse(rotated.matches(otp, IdentityHashPurpose.ENROLLMENT_OTP, "ab", "d"))
    assertEquals(2, rotated.hash(IdentityHashPurpose.RECOVERY_CODE, "account", "code").pepperVersion)
    assertContentEquals(old.hash(IdentityHashPurpose.COMMAND_SCOPE, "scope", "email").bytes,
      rotated.hash(IdentityHashPurpose.COMMAND_SCOPE, "scope", "email").bytes)
  }
}
