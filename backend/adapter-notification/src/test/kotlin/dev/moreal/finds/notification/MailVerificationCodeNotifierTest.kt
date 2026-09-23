package dev.moreal.finds.notification

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.testing.FakeTransaction
import dev.moreal.finds.domain.identity.EmailAddress
import dev.moreal.mail.MailMessageId
import dev.moreal.mail.Mailbox
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class MailVerificationCodeNotifierTest {
  private val now = Instant.parse("2026-09-23T01:02:03Z")
  private val expiry = now.plusSeconds(600)
  private val sender = Mailbox("verify@finds.team", "finds.team")

  @Test
  fun `purpose specific Korean templates include code exact expiry and no account assertion`() {
    val tx = FakeTransaction()
    val notifier = MailVerificationCodeNotifier(ClockPort { now })
    for ((purpose, subject) in listOf(VerificationPurpose.ENROLLMENT to "[finds.team] 이메일 인증 코드",
      VerificationPurpose.RECOVERY to "[finds.team] 계정 복구 인증 코드")) {
      val id = DeliveryRequestId(UUID.randomUUID())
      val result = tx.execute { notifier.deliver(it, EmailAddress("private@example.test"), purpose,
        VerificationCode("01234567"), expiry, id) }
      assertEquals(id, result)
      val queued = tx.outboxMessages.last()
      assertEquals(MailMessageId(id.value), queued.metadata.id)
      assertEquals(purpose.name, queued.metadata.purpose)
      val mail = VerificationMail.render(queued.metadata, queued.plaintext, sender)
      assertEquals(subject, mail.subject)
      assertEquals(MailMessageId(id.value), mail.id)
      assertEquals(sender, mail.from)
      assertEquals("private@example.test", mail.recipients.to.single().address)
      for (body in listOf(assertNotNull(mail.content.text), assertNotNull(mail.content.html))) {
        assertContains(body, "01234567")
        assertContains(body, "2026-09-23T01:12:03Z")
        assertContains(body, "요청하지 않았다면")
        assertFalse(body.contains("등록된 계정"))
        assertFalse(body.contains("존재하지"))
        assertFalse(body.contains("private@example.test"))
      }
    }
  }

  @Test
  fun `caller failure rolls back notification with the transaction`() {
    val tx = FakeTransaction()
    assertFailsWith<IllegalStateException> {
      tx.execute {
        MailVerificationCodeNotifier(ClockPort { now }).deliver(it, EmailAddress("private@example.test"),
          VerificationPurpose.ENROLLMENT, VerificationCode("01234567"), expiry, DeliveryRequestId(UUID.randomUUID()))
        error("Rollback caller's challenge and audit")
      }
    }
    assertTrue(tx.outboxMessages.isEmpty())
  }

  @Test
  fun `invalid code recipient and expired challenge never enqueue`() {
    for (code in listOf("1234567", "123456789", "<script>", "１２３４５６７８"))
      assertFailsWith<IllegalArgumentException> { VerificationCode(code) }
    val tx = FakeTransaction()
    assertFailsWith<IllegalArgumentException> { tx.execute {
      MailVerificationCodeNotifier(ClockPort { now }).deliver(it, EmailAddress("injected\r\n@example.test"),
        VerificationPurpose.RECOVERY, VerificationCode("01234567"), expiry, DeliveryRequestId(UUID.randomUUID()))
    } }
    assertFailsWith<IllegalArgumentException> { tx.execute {
      MailVerificationCodeNotifier(ClockPort { now }).deliver(it, EmailAddress("private@example.test"),
        VerificationPurpose.RECOVERY, VerificationCode("01234567"), now, DeliveryRequestId(UUID.randomUUID()))
    } }
    assertTrue(tx.outboxMessages.isEmpty())
  }
}
