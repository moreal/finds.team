package dev.moreal.finds.persistence

import dev.moreal.finds.application.audit.*
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.domain.identity.EmailAddress
import dev.moreal.finds.notification.*
import dev.moreal.mail.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlin.test.*

class NotificationTransactionTest : PostgresIntegrationTest() {
  private val start = Instant.parse("2026-09-23T00:00:00Z")
  private var now = start
  private val crypto = AesGcmMailPayloadCrypto(1, mapOf(1 to ByteArray(32) { 9 }))
  private val id = DeliveryRequestId(UUID.randomUUID())

  @Test
  fun `semantic enqueue preserves correlation through PostgreSQL lease and dispatch`() = runBlocking {
    val (_, db) = migratedContext()
    val request = CommandMetadata(UUID.randomUUID(), UUID.randomUUID())
    JooqTransactionAdapter(db) { crypto }.execute {
      MailVerificationCodeNotifier(ClockPort { now }).deliver(it, EmailAddress("private@example.test"),
        VerificationPurpose.ENROLLMENT, VerificationCode("01234567"), start.plusSeconds(600), id,
        correlationId = request.correlationId)
    }
    val transport = object : MailTransport {
      override val provider = MailProvider("integration")
      override suspend fun send(message: MailMessage): MailDeliveryResult {
        val context = assertNotNull(currentCoroutineContext()[MailDeliveryContext])
        assertEquals(request.correlationId, context.correlationId)
        assertEquals(1, context.attempt)
        assertEquals("ENROLLMENT", context.purpose)
        assertEquals(id.value, message.id.value)
        return MailDeliveryResult.Accepted(provider, "receipt")
      }
    }
    assertEquals(1, MailOutboxDispatcher(JooqMailOutbox(db, crypto), crypto, transport,
      Mailbox("verify@finds.team"), ClockPort { now }, MailDispatchPolicy()).dispatch())
    assertEquals("ACCEPTED", db.fetchValue("SELECT state FROM mail_outbox"))
    assertEquals(request.correlationId, db.fetchValue("SELECT correlation_id FROM mail_outbox"))
  }

  @Test
  fun `notifier enqueue audit and caller rollback share one PostgreSQL transaction`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { crypto }
    val notifier = MailVerificationCodeNotifier(ClockPort { now })
    assertFailsWith<IllegalStateException> {
      tx.execute { context ->
        notify(notifier, context)
        context.auditLog.append(event())
        assertEquals(0L, db.fetchValue("SELECT count(*) FROM mail_outbox"))
        assertEquals(0L, db.fetchValue("SELECT count(*) FROM audit_events"))
        error("caller rolls back challenge")
      }
    }
    assertEquals(0L, db.fetchValue("SELECT count(*) FROM mail_outbox"))
    assertEquals(0L, db.fetchValue("SELECT count(*) FROM audit_events"))
    tx.execute { notify(notifier, it); it.auditLog.append(event()) }
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM mail_outbox"))
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM audit_events"))
    assertFalse((db.fetchValue("SELECT encode(payload_ciphertext, 'escape') FROM mail_outbox") as String).contains("01234567"))
  }

  @Test
  fun `decrypt and provider IO follow lease commit and acceptance redacts ciphertext`() = runBlocking {
    val (_, db) = migratedContext()
    JooqTransactionAdapter(db) { crypto }.execute { notify(MailVerificationCodeNotifier(ClockPort { now }), it) }
    fun outsideTransaction() {
      assertEquals(0L, db.fetchValue("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND state = 'idle in transaction'"))
    }
    val guardedCrypto = object : MailPayloadCrypto by crypto {
      override fun decrypt(metadata: MailPayloadMetadata, payload: EncryptedMailPayload): ByteArray {
        outsideTransaction()
        return crypto.decrypt(metadata, payload)
      }
    }
    val transport = object : MailTransport {
      override val provider = MailProvider("integration")
      override suspend fun send(message: MailMessage): MailDeliveryResult {
        outsideTransaction()
        assertEquals(id.value, message.id.value)
        assertContains(assertNotNull(message.content.text), "01234567")
        return MailDeliveryResult.Accepted(provider, "private@example.test provider receipt")
      }
    }
    MailOutboxDispatcher(JooqMailOutbox(db, crypto), guardedCrypto, transport, Mailbox("verify@finds.team"),
      ClockPort { now }, MailDispatchPolicy()).dispatch()
    assertEquals("ACCEPTED", db.fetchValue("SELECT state FROM mail_outbox"))
    assertNull(db.fetchValue("SELECT payload_ciphertext FROM mail_outbox"))
    assertEquals(1, db.fetchValue("SELECT attempt_count FROM mail_outbox"))
    assertTrue((db.fetchValue("SELECT provider_receipt_fingerprint FROM mail_outbox") as String).startsWith("hmac-sha256:v1:"))
  }

  @Test
  fun `crash after provider acceptance and attempt record never sends recovered mail`() = runBlocking {
    val (_, db) = migratedContext()
    JooqTransactionAdapter(db) { crypto }.execute { notify(MailVerificationCodeNotifier(ClockPort { now }), it) }
    val outbox = JooqMailOutbox(db, crypto)
    val old = outbox.leaseBatch("crashed-worker", now, Duration.ofSeconds(1), 1).single()
    outbox.recordAttempt(old, MailDeliveryResult.Accepted(MailProvider("smtp"), "receipt"), now)
    now = start.plusSeconds(2)
    val transport = object : MailTransport {
      override val provider = MailProvider("smtp")
      override suspend fun send(message: MailMessage): MailDeliveryResult = error("must not resend")
    }
    MailOutboxDispatcher(outbox, crypto, transport, Mailbox("verify@finds.team"), ClockPort { now }, MailDispatchPolicy()).dispatch()
    assertEquals("INDETERMINATE", db.fetchValue("SELECT state FROM mail_outbox"))
    assertNotNull(db.fetchValue("SELECT payload_ciphertext FROM mail_outbox"))
    assertFalse(outbox.complete(old, MailDeliveryResult.Accepted(transport.provider, "late receipt"), now))
    assertEquals(1, outbox.redactExpired(start.plusSeconds(600)))
  }

  private fun notify(notifier: VerificationCodeNotifier, context: TransactionContext) = notifier.deliver(context,
    EmailAddress("private@example.test"), VerificationPurpose.ENROLLMENT, VerificationCode("01234567"),
    start.plusSeconds(600), id)

  private fun event() = AuditEvent(UUID.randomUUID(), 1, now, Actor.System, AuditAction.PASSKEY_REGISTERED,
    "otp_challenge", UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(), AuditOutcome.SUCCEEDED)
}
