package dev.moreal.finds.domain.identity

/** Identity owns normalization; this early value supplies the semantic notification boundary. */
data class EmailAddress(val value: String) {
  init {
    require(value.length in 3..254 && value.count { it == '@' } == 1 &&
      value.none { it.isWhitespace() || it.isISOControl() }) { "Invalid email address" }
  }
  override fun toString(): String = "EmailAddress(<redacted>)"
}
