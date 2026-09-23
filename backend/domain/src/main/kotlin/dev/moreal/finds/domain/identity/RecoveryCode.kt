package dev.moreal.finds.domain.identity

import java.util.Locale

sealed interface RecoveryCodeResult {
  data class Valid(val code: RecoveryCode) : RecoveryCodeResult
  data object Invalid : RecoveryCodeResult
}

/**
 * A plaintext 128-bit recovery secret. Only [format] deliberately reveals it; never persist it.
 * Entropy, keyed hashing, constant-time proof verification, and single consumption are caller duties.
 * Encoding is RFC 4648 Base32 without padding, grouped 5-5-5-5-6 for manual entry.
 */
class RecoveryCode private constructor(private val encoded: String) {
  fun format(): String = encoded.take(20).chunked(5).joinToString("-") + "-" + encoded.drop(20)
  override fun equals(other: Any?): Boolean = other is RecoveryCode && encoded == other.encoded
  override fun hashCode(): Int = encoded.hashCode()
  override fun toString(): String = "RecoveryCode(<redacted>)"

  companion object {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val compactPattern = Regex("[A-Z2-7]{26}")
    private val groupedPattern = Regex("[A-Z2-7]{5}(?:-[A-Z2-7]{5}){3}-[A-Z2-7]{6}")

    /** Caller must supply sixteen cryptographically random bytes; no domain RNG or retained array. */
    fun fromBytes(bytes: ByteArray): RecoveryCode {
      require(bytes.size == 16) { "Recovery code requires 128 bits" }
      val encoded = buildString {
        var buffer = 0
        var bits = 0
        bytes.forEach { byte ->
          buffer = (buffer shl 8) or (byte.toInt() and 255)
          bits += 8
          while (bits >= 5) {
            bits -= 5
            append(ALPHABET[(buffer ushr bits) and 31])
          }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
      }
      return RecoveryCode(encoded)
    }

    fun parse(input: String): RecoveryCodeResult {
      if (input.length != 26 && input.length != 30) return RecoveryCodeResult.Invalid
      // ASCII only: Unicode case expansion must not turn malformed input into a valid secret.
      if (input.any { it.code > 127 }) return RecoveryCodeResult.Invalid
      val uppercase = input.uppercase(Locale.ROOT)
      if (!compactPattern.matches(uppercase) && !groupedPattern.matches(uppercase)) return RecoveryCodeResult.Invalid
      val compact = uppercase.replace("-", "")
      // 26 Base32 digits hold 130 bits; the final two must be zero to avoid secret aliases.
      if (ALPHABET.indexOf(compact.last()) and 3 != 0) return RecoveryCodeResult.Invalid
      return RecoveryCodeResult.Valid(RecoveryCode(compact))
    }
  }
}
