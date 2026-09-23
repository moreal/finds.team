package dev.moreal.finds.domain.identity

import java.net.IDN
import java.util.Locale

/**
 * ASCII dot-atom local parts and IDNA domains. SMTPUTF8 and quoted local parts are unsupported.
 * [value] preserves local-part display case and uses a lowercase ASCII domain for delivery.
 * [normalized] deliberately treats local parts as case-insensitive for account lookup and equality;
 * it does not remove dots or plus tags. This is an account policy, not a claim about all mail servers.
 */
class EmailAddress(input: String) {
  val value: String = normalize(input)
  val normalized: String = value.lowercase(Locale.ROOT)

  override fun equals(other: Any?): Boolean = other is EmailAddress && normalized == other.normalized
  override fun hashCode(): Int = normalized.hashCode()
  override fun toString(): String = "EmailAddress(<redacted>)"

  companion object {
    private val localPart = Regex("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*")

    private fun normalize(input: String): String {
      require(input.count { it == '@' } == 1 && input.none { it.isWhitespace() || it.isISOControl() }) {
        "Invalid email address"
      }
      val local = input.substringBefore('@')
      require(local.length in 1..64 && localPart.matches(local)) { "Invalid email address" }
      // Never propagate an IDN exception: its diagnostic may contain the supplied domain.
      val domain = try { IDN.toASCII(input.substringAfter('@'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid email address") }
      val labels = domain.split('.')
      require(labels.size >= 2 && labels.all { it.length in 1..63 } && domain.length <= 253) {
        "Invalid email address"
      }
      return "$local@$domain".also { require(it.length <= 254) { "Invalid email address" } }
    }
  }
}
