package dev.moreal.finds.domain.identity

import java.util.Locale
import kotlin.test.*

class EmailAddressTest {
  @Test
  fun `normalizes IDNA domains and folds only the lookup local part`() {
    listOf(
      Triple("Alice@EXAMPLE.TEST", "Alice@example.test", "alice@example.test"),
      Triple("A.Lice+Tag@bücher.example", "A.Lice+Tag@xn--bcher-kva.example", "a.lice+tag@xn--bcher-kva.example"),
      Triple("Alice@XN--BCHER-KVA.example", "Alice@xn--bcher-kva.example", "alice@xn--bcher-kva.example"),
      Triple("Alice@채용.example", "Alice@xn--oo5bn6h.example", "alice@xn--oo5bn6h.example"),
    ).forEach { (input, display, lookup) ->
      val email = EmailAddress(input)
      assertEquals(display, email.value)
      assertEquals(lookup, email.normalized)
      assertEquals(email, EmailAddress(lookup))
      assertEquals(email.hashCode(), EmailAddress(lookup).hashCode())
    }
  }

  @Test
  fun `lookup folding is independent of process locale`() {
    val previous = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      assertEquals("info@example.test", EmailAddress("INFO@EXAMPLE.TEST").normalized)
    } finally { Locale.setDefault(previous) }
  }

  @Test
  fun `rejects malformed or unsupported mailbox syntax without disclosing input`() {
    listOf("", "@example.test", "a@", "a@@example.test", "a b@example.test", " a@example.test",
      "a@example.test\n", "a\r\n@example.test", ".a@example.test", "a.@example.test", "a..b@example.test",
      "\"a\"@example.test", "한글@example.test", "a@-example.test", "a@example-.test", "a@example..test",
      "a@example.test.", "a@localhost", "a@[127.0.0.1]", "a@exa_mple.test", "a@\u200b.test",
      "a".repeat(65) + "@example.test", "a@" + "b".repeat(64) + ".test",
      "a".repeat(64) + "@" + List(4) { "b".repeat(50) }.joinToString(".")
    ).forEach { input ->
      val error = assertFailsWith<IllegalArgumentException> { EmailAddress(input) }
      assertEquals("Invalid email address", error.message)
      assertNull(error.cause)
    }
  }

  @Test
  fun `diagnostics redact email material`() {
    assertEquals("EmailAddress(<redacted>)", EmailAddress("Private@example.test").toString())
  }
}
