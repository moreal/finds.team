package dev.moreal.mail

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class MailTypesTest {
  @Test
  fun `message id parses canonical UUID and remains stable`() {
    val id = MailMessageId.parse("550E8400-E29B-41D4-A716-446655440000")

    assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), id.value)
    assertEquals("550e8400-e29b-41d4-a716-446655440000", id.toString())
    assertEquals(id, MailMessageId.parse(id.toString()))
    assertNotEquals(id, MailMessageId.new())
  }

  @Test
  fun `message id rejects malformed or noncanonical UUID`() {
    listOf("not-a-uuid", "1-1-1-1-1", " 550e8400-e29b-41d4-a716-446655440000").forEach {
      assertFailsWith<IllegalArgumentException>(it) { MailMessageId.parse(it) }
    }
  }

  @Test
  fun `message id parse errors never reveal the invalid input`() {
    val error = assertFailsWith<IllegalArgumentException> {
      MailMessageId.parse("private-local@example.com")
    }

    assertEquals("Invalid mail message id", error.message)
    assertEquals(null, error.cause)
  }

  @Test
  fun `mailbox accepts UTF-8 display names and validates address syntax`() {
    val mailbox = Mailbox("sender@example.com", "보내는 사람")

    assertEquals("sender@example.com", mailbox.address)
    assertEquals("보내는 사람", mailbox.name)
    listOf("", "plainaddress", "@example.com", "person@", "person@localhost", "a b@example.com", "person@example.com\r\nBcc: victim@example.com").forEach {
      assertFailsWith<IllegalArgumentException>(it) { Mailbox(it) }
    }
  }

  @Test
  fun `mailbox rejects injected or blank display names`() {
    listOf("", "   ", "Alice\r\nBcc: victim@example.com", "Alice\u0000Bob").forEach {
      assertFailsWith<IllegalArgumentException>(it) { Mailbox("sender@example.com", it) }
    }
  }

  @Test
  fun `mailbox enforces local part and DNS label octet limits`() {
    assertEquals("a".repeat(64) + "@example.com", Mailbox("a".repeat(64) + "@example.com").address)
    assertFailsWith<IllegalArgumentException> { Mailbox("a".repeat(65) + "@example.com") }

    assertEquals("a@" + "b".repeat(63) + ".com", Mailbox("a@" + "b".repeat(63) + ".com").address)
    assertFailsWith<IllegalArgumentException> { Mailbox("a@" + "b".repeat(64) + ".com") }
  }

  @Test
  fun `mailbox accepts 254 octets and rejects 255 octets`() {
    val domain189 = listOf("a".repeat(63), "b".repeat(63), "c".repeat(61)).joinToString(".")
    val domain190 = listOf("a".repeat(63), "b".repeat(63), "c".repeat(62)).joinToString(".")
    val accepted = "x".repeat(64) + "@" + domain189
    val rejected = "x".repeat(64) + "@" + domain190

    assertEquals(254, accepted.length)
    assertEquals(255, rejected.length)
    assertEquals(accepted, Mailbox(accepted).address)
    assertFailsWith<IllegalArgumentException> { Mailbox(rejected) }
  }

  @Test
  fun `recipients require at least one unique mailbox across all kinds`() {
    val first = Mailbox("person@example.com")
    assertFailsWith<IllegalArgumentException> { Recipients() }
    assertFailsWith<IllegalArgumentException> { Recipients(to = listOf(first, first)) }
    assertFailsWith<IllegalArgumentException> {
      Recipients(to = listOf(first), cc = listOf(Mailbox("PERSON@example.com")))
    }
    assertFailsWith<IllegalArgumentException> {
      Recipients(to = listOf(first), bcc = listOf(Mailbox("person@example.com", "Other name")))
    }
  }

  @Test
  fun `recipients copy mutable input lists`() {
    val input = mutableListOf(Mailbox("person@example.com"))
    val recipients = Recipients(to = input)

    input += Mailbox("later@example.com")
    assertEquals(listOf(Mailbox("person@example.com")), recipients.to)
  }

  @Test
  fun `content requires at least one nonblank alternative`() {
    assertFailsWith<IllegalArgumentException> { MailContent() }
    assertFailsWith<IllegalArgumentException> { MailContent(text = " \n ") }
    assertFailsWith<IllegalArgumentException> { MailContent(html = "\t") }
    assertEquals("Hello", MailContent(text = "Hello").text)
    assertEquals("<p>안녕하세요</p>", MailContent(html = "<p>안녕하세요</p>").html)
  }

  @Test
  fun `message rejects blank or injected subject and tags`() {
    listOf("", "  ", "Hello\r\nBcc: victim@example.com", "Hello\u0000there").forEach {
      assertFailsWith<IllegalArgumentException>(it) { message(subject = it) }
    }
    listOf("", " ", "campaign\r\nBcc: victim@example.com").forEach {
      assertFailsWith<IllegalArgumentException>(it) { message(tags = setOf(it)) }
    }
  }

  @Test
  fun `message retains reply-to and immutable tags`() {
    val input = mutableSetOf("verification")
    val replyTo = Mailbox("reply@example.com", "Reply Team")
    val message = message(tags = input, replyTo = replyTo)

    input += "added-later"
    assertEquals(setOf("verification"), message.tags)
    assertEquals(replyTo, message.replyTo)
  }

  @Test
  fun `provider and accepted receipt reject unsafe header values`() {
    assertFailsWith<IllegalArgumentException> { MailProvider(" ") }
    assertFailsWith<IllegalArgumentException> { MailProvider("smtp\r\nBcc: victim@example.com") }
    assertFailsWith<IllegalArgumentException> {
      MailDeliveryResult.Accepted(MailProvider("smtp"), "receipt\r\nBcc: victim@example.com")
    }
  }

  @Test
  fun `accepted delivery diagnostics redact provider receipt`() {
    val receipt = "private-local@example.com"
    val result = MailDeliveryResult.Accepted(MailProvider("smtp"), receipt)

    assertEquals(receipt, result.providerMessageId)
    assertFalse(result.toString().contains("private-local"))
  }

  @Test
  fun `mailbox and content diagnostics never expose recipient or body data`() {
    val mailbox = Mailbox("private-local@example.com", "Private Name")
    val content = MailContent(text = "secret-otp-123456", html = "<p>secret-otp-123456</p>")

    assertFalse(mailbox.toString().contains("private-local"))
    assertFalse(mailbox.toString().contains("Private Name"))
    assertFalse(content.toString().contains("secret-otp-123456"))
  }

  private fun message(
    subject: String = "인증 코드",
    tags: Set<String> = emptySet(),
    replyTo: Mailbox? = null,
  ) = MailMessage(
    id = MailMessageId.parse("550e8400-e29b-41d4-a716-446655440000"),
    from = Mailbox("sender@example.com", "보내는 사람"),
    recipients = Recipients(to = listOf(Mailbox("recipient@example.com"))),
    subject = subject,
    content = MailContent(text = "Code: 123456", html = "<p>Code: 123456</p>"),
    tags = tags,
    replyTo = replyTo,
  )
}
