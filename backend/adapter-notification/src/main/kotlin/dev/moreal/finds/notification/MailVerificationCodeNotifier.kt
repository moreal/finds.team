package dev.moreal.finds.notification

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.EmailAddress
import dev.moreal.mail.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit

class MailVerificationCodeNotifier(private val clock: ClockPort) : VerificationCodeNotifier {
  override fun deliver(transaction: TransactionContext, recipient: EmailAddress, purpose: VerificationPurpose,
    code: VerificationCode, expiresAt: Instant, idempotencyKey: DeliveryRequestId): DeliveryRequestId {
    Mailbox(recipient.value) // The domain's normalization policy is independent of transport validation.
    val now = clock.now()
    val metadata = MailPayloadMetadata(MailMessageId(idempotencyKey.value), purpose.name,
      expiresAt.truncatedTo(ChronoUnit.MICROS))
    require(metadata.expiresAt > now) { "Verification challenge has expired" }
    val payload = VerificationMail.encode(recipient, code)
    try { transaction.outbox.enqueue(metadata, payload, now) } finally { payload.fill(0) }
    // This is an internal correlation id, never a provider receipt or an account-existence result.
    return idempotencyKey
  }
}

internal object VerificationMail {
  fun encode(recipient: EmailAddress, code: VerificationCode): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { out ->
      out.writeInt(1)
      out.writeUTF(recipient.value)
      out.writeUTF(code.value)
    }
    bytes.toByteArray()
  }

  fun render(metadata: MailPayloadMetadata, payload: ByteArray, sender: Mailbox): MailMessage {
    val (recipient, code) = DataInputStream(ByteArrayInputStream(payload)).use { input ->
      require(input.readInt() == 1) { "Unsupported verification mail payload" }
      val values = Mailbox(input.readUTF()) to VerificationCode(input.readUTF())
      require(input.available() == 0) { "Invalid verification mail payload" }
      values
    }
    val purpose = VerificationPurpose.valueOf(metadata.purpose)
    val label = when (purpose) {
      VerificationPurpose.ENROLLMENT -> "이메일 인증"
      VerificationPurpose.RECOVERY -> "계정 복구 인증"
    }
    val expiry = metadata.expiresAt.toString()
    val text = "$label 코드: ${code.value}\n이 코드는 $expiry (UTC)까지 사용할 수 있습니다.\n" +
      "직접 요청한 화면에서만 입력하세요. 요청하지 않았다면 이 메일을 무시하세요."
    // Only validated digits, fixed copy and an ISO instant are interpolated into HTML.
    val html = "<html lang=\"ko\"><body><p>$label 코드: <strong>${code.value}</strong></p>" +
      "<p>이 코드는 <time>$expiry</time> (UTC)까지 사용할 수 있습니다.</p>" +
      "<p>직접 요청한 화면에서만 입력하세요. 요청하지 않았다면 이 메일을 무시하세요.</p></body></html>"
    return MailMessage(metadata.id, sender, Recipients(to = listOf(recipient)), "[finds.team] $label 코드",
      MailContent(text, html), tags = setOf(purpose.name))
  }
}
