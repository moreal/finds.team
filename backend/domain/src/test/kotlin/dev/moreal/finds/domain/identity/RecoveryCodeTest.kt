package dev.moreal.finds.domain.identity

import kotlin.test.*

class RecoveryCodeTest {
  @Test
  fun `encodes exactly 128 caller supplied bits using grouped RFC4648 Base32`() {
    listOf(ByteArray(16) to "AAAAA-AAAAA-AAAAA-AAAAA-AAAAAA",
      ByteArray(16) { 0xff.toByte() } to "77777-77777-77777-77777-777774",
      ByteArray(16) { it.toByte() } to "AAAQE-AYEAU-DAOCA-JBIFQ-YDIOB4").forEach { (bytes, expected) ->
      val code = RecoveryCode.fromBytes(bytes)
      assertEquals(expected, code.format())
      assertEquals(code, assertIs<RecoveryCodeResult.Valid>(RecoveryCode.parse(expected)).code)
      assertEquals(code, assertIs<RecoveryCodeResult.Valid>(RecoveryCode.parse(expected.lowercase().replace("-", ""))).code)
      bytes.fill(42)
      assertEquals(expected, code.format())
      assertEquals("RecoveryCode(<redacted>)", code.toString())
      assertFalse(RecoveryCode.parse(expected).toString().contains(expected))
    }
  }

  @Test
  fun `rejects invalid lengths alphabet grouping and noncanonical trailing bits`() {
    listOf("", "AAAAA-AAAAA-AAAAA-AAAAA-AAAAA", "AAAAA-AAAAA-AAAAA-AAAAA-AAAAAAA",
      "AAAAA-AAAAA-AAAAA-AAAAA-AAAAAB", "AAAAA-AAAAA-AAAAA-AAAAA-AAAAA0",
      "AAAAA-AAAAA-AAAAA-AAAAA-AAAAA1", "AAAAA-AAAAA-AAAAA-AAAAA-AAAAA=",
      "AAAA-AAAAAA-AAAAA-AAAAA-AAAAAA", " AAAAA-AAAAA-AAAAA-AAAAA-AAAAAA",
      "AAAAA-AAAAA-AAAAA-AAAAA-AAAAAA\n").forEach { input ->
      assertIs<RecoveryCodeResult.Invalid>(RecoveryCode.parse(input))
    }
    listOf(0, 15, 17, 32).forEach { length ->
      assertFailsWith<IllegalArgumentException> { RecoveryCode.fromBytes(ByteArray(length)) }
    }
  }
}
