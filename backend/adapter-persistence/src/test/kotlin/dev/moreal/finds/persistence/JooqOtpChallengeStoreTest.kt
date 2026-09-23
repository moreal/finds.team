package dev.moreal.finds.persistence

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.testing.*
import dev.moreal.finds.application.usecase.*
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds.notification.MailVerificationCodeNotifier
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class JooqOtpChallengeStoreTest : PostgresIntegrationTest() {
  private val start = Instant.parse("2026-09-23T00:00:00Z")
  private var now = start
  private val email = EmailAddress("private@example.test")
  private val hashes = TestIdentityHashes()
  private val crypto = AesGcmMailPayloadCrypto(1, mapOf(1 to ByteArray(32) { 9 }))

  @Test fun `challenge command and encrypted mail commit together without plaintext persistence`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { crypto }
    val random = DeterministicIdentityRandom()
    val notifier = MailVerificationCodeNotifier(ClockPort { now })
    val failing = object : VerificationCodeNotifier {
      override fun deliver(transaction: TransactionContext, recipient: EmailAddress, purpose: VerificationPurpose,
        code: VerificationCode, expiresAt: Instant, idempotencyKey: DeliveryRequestId, correlationId: UUID): DeliveryRequestId {
        notifier.deliver(transaction, recipient, purpose, code, expiresAt, idempotencyKey, correlationId)
        error("rollback")
      }
    }
    val command = RequestEnrollmentOtpCommand(email, metadata())
    assertFailsWith<IllegalStateException> { RequestEnrollmentOtp(tx, ClockPort { now }, random, hashes, failing).execute(command) }
    for (table in listOf("otp_challenges", "command_requests", "mail_outbox")) assertEquals(0L, db.fetchValue("SELECT count(*) FROM $table"))
    RequestEnrollmentOtp(tx, ClockPort { now }, random, hashes, notifier).execute(command)
    for (table in listOf("otp_challenges", "command_requests", "mail_outbox")) {
      assertEquals(1L, db.fetchValue("SELECT count(*) FROM $table"))
      assertFalse(db.fetchValue("SELECT row_to_json(t)::text FROM $table t").toString().contains("00000042"))
    }
    assertEquals(32, db.fetchValue("SELECT octet_length(otp_hash) FROM otp_challenges"))
    assertEquals(command.metadata.correlationId, db.fetchValue("SELECT correlation_id FROM mail_outbox"))
  }

  @Test fun `DEBUG and TRACE diagnostics redact OTP email and digest on successful and failed persistence`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { crypto }
    val recipient = EmailAddress("Private@Example.test")
    val otp = "00000042"
    val digest = hashes.hash(IdentityHashPurpose.ENROLLMENT_OTP, recipient.normalized, otp).bytes
    val forbidden = mapOf(
      "digest hex" to digest.joinToString("") { "%02x".format(it) },
      "digest uppercase hex" to digest.joinToString("") { "%02X".format(it) },
      "truncated digest hex" to digest.take(16).joinToString("") { "%02x".format(it) },
      "digest base64" to java.util.Base64.getEncoder().encodeToString(digest),
      "digest base64url" to java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest),
      "digest byte array" to digest.contentToString(),
      "OTP plaintext" to otp, "delivery email" to recipient.value, "normalized email" to recipient.normalized,
    )
    val request = RequestEnrollmentOtp(tx, ClockPort { now }, DeterministicIdentityRandom(), hashes, MailVerificationCodeNotifier(ClockPort { now }))
    val root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
    val loggers = listOf("org.jooq", "dev.moreal.finds.persistence").map {
      org.slf4j.LoggerFactory.getLogger(it) as ch.qos.logback.classic.Logger
    }
    for (level in listOf(ch.qos.logback.classic.Level.DEBUG, ch.qos.logback.classic.Level.TRACE)) {
      // NOT VALID preserves the previous challenge, but the next save must hit a real SQLSTATE 23514.
      fun attempt(fail: Boolean): String {
        if (fail) db.execute("ALTER TABLE otp_challenges ADD CONSTRAINT reject_diagnostic_fixture CHECK (false) NOT VALID")
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previous = loggers.map { it.level }
        loggers.forEach { it.level = level }
        root.addAppender(appender)
        val diagnostic = try {
          loggers.first().debug("identity diagnostic capture enabled")
          val thrown = if (fail) assertFails { request.execute(RequestEnrollmentOtpCommand(recipient, metadata())) }
            else { request.execute(RequestEnrollmentOtpCommand(recipient, metadata())); null }
          appender.list.joinToString("\n") { event ->
            event.formattedMessage + (event.throwableProxy?.let(ch.qos.logback.classic.spi.ThrowableProxyUtil::asString) ?: "")
          } + (thrown?.stackTraceToString() ?: "")
        } finally {
          root.detachAppender(appender)
          loggers.zip(previous).forEach { (logger, old) -> logger.level = old }
          appender.stop()
          if (fail) db.execute("ALTER TABLE otp_challenges DROP CONSTRAINT reject_diagnostic_fixture")
        }
        assertContains(diagnostic, "identity diagnostic capture enabled")
        for ((kind, value) in forbidden) assertFalse(diagnostic.contains(value), "$level diagnostics leaked $kind")
        if (fail) assertContains(diagnostic, "Identity persistence failed (23514)")
        return diagnostic
      }
      attempt(fail = false)
      val previousHash = tx.execute { assertNotNull(it.otpChallenges.find(recipient, VerificationPurpose.ENROLLMENT)).challenge!!.hash.bytes }
      attempt(fail = true)
      assertContentEquals(previousHash, tx.execute { it.otpChallenges.find(recipient, VerificationPurpose.ENROLLMENT)!!.challenge!!.hash.bytes })
    }
    assertEquals(2L, db.fetchValue("SELECT count(*) FROM mail_outbox"))
    assertEquals(2L, db.fetchValue("SELECT count(*) FROM command_requests"))
  }

  @Test fun `concurrent failures stop at five survive reissue and unlock at fifteen minutes`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { crypto }
    val request = RequestEnrollmentOtp(tx, ClockPort { now }, DeterministicIdentityRandom(), hashes, MailVerificationCodeNotifier(ClockPort { now }))
    request.execute(RequestEnrollmentOtpCommand(email, metadata()))
    val verify = VerifyEnrollmentOtp(tx, ClockPort { now }, DeterministicIdentityRandom(), hashes)
    race(8) { verify.execute(VerifyEnrollmentOtpCommand(email, VerificationCode("99999999"))) }
    tx.execute { assertEquals(5, it.otpChallenges.find(email, VerificationPurpose.ENROLLMENT)?.consecutiveFailures) }
    now = start.plusSeconds(899)
    request.execute(RequestEnrollmentOtpCommand(email, metadata()))
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM mail_outbox"))
    now = start.plusSeconds(900)
    request.execute(RequestEnrollmentOtpCommand(email, metadata()))
    assertEquals(2L, db.fetchValue("SELECT count(*) FROM mail_outbox"))
    tx.execute { assertEquals(5, it.otpChallenges.find(email, VerificationPurpose.ENROLLMENT)?.consecutiveFailures) }
    val results = race(2) { verify.execute(VerifyEnrollmentOtpCommand(email, VerificationCode("00000042"))) }
    assertEquals(1, results.count { it is VerifyEnrollmentOtpResult.Verified })
    assertEquals(1, results.count { it == VerifyEnrollmentOtpResult.Rejected })
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM restricted_sessions"))
    assertEquals(0, db.fetchValue("SELECT consecutive_failures FROM otp_challenges"))
  }

  @Test fun `recovery reissue has sixty second spacing and persisted five failure cooldown with two proofs`() {
    val (_, db) = migratedContext()
    val tx = JooqTransactionAdapter(db) { crypto }
    val id = UserId(UUID.randomUUID())
    val recovery = RecoveryCode.fromBytes(ByteArray(16) { 1 })
    tx.execute {
      it.users.lockByEmail(email); it.users.save(User(id, email))
      it.credentials.insert(credential(id, "existing", now))
      it.users.save(User(id, email, UserStatus.ACTIVE, credentials = setOf(CredentialId("existing"))))
      it.recoveryCodes.save(RecoveryCodeHash(id, hashes.hash(IdentityHashPurpose.RECOVERY_CODE, id.value.toString(), recovery.format()), now))
    }
    val random = DeterministicIdentityRandom()
    val request = RequestRecoveryOtp(tx, ClockPort { now }, random, hashes, MailVerificationCodeNotifier(ClockPort { now }))
    val verify = VerifyRecoveryProofs(tx, ClockPort { now }, random, hashes)
    fun issue() = request.execute(RequestRecoveryOtpCommand(email, metadata()))
    fun wrong() = verify.execute(VerifyRecoveryProofsCommand(email, VerificationCode("99999999"), recovery.format()))
    issue(); repeat(2) { wrong() }
    now = start.plusSeconds(59); issue()
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM mail_outbox"))
    now = start.plusSeconds(60); issue()
    assertEquals(2L, db.fetchValue("SELECT count(*) FROM mail_outbox"))
    assertEquals(2, db.fetchValue("SELECT consecutive_failures FROM otp_challenges"))
    repeat(3) { wrong() }
    now = start.plusSeconds(959); issue()
    assertEquals(2L, db.fetchValue("SELECT count(*) FROM mail_outbox"))
    now = start.plusSeconds(960); issue()
    assertEquals(3L, db.fetchValue("SELECT count(*) FROM mail_outbox"))
    assertEquals(5, db.fetchValue("SELECT consecutive_failures FROM otp_challenges"))
    val results = race(2) { verify.execute(VerifyRecoveryProofsCommand(email, VerificationCode("00000042"), recovery.format())) }
    assertEquals(1, results.count { it is VerifyRecoveryProofsResult.Verified })
    assertEquals(1, results.count { it == VerifyRecoveryProofsResult.Rejected })
    assertEquals(0, db.fetchValue("SELECT consecutive_failures FROM otp_challenges"))
    assertEquals(1L, db.fetchValue("SELECT count(*) FROM passkey_credentials"))
    assertFails { db.execute("UPDATE otp_challenges SET consumed_at = NULL") }
    assertFails { db.execute("UPDATE otp_challenges SET otp_hash = decode('00','hex')") }
    assertFails { db.execute("UPDATE recovery_codes SET pepper_version = 0") }
  }
}

internal fun metadata() = CommandMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
internal fun <T> race(count: Int, block: () -> T): List<T> {
  val pool = Executors.newFixedThreadPool(count) { Thread(it).apply { isDaemon = true } }
  val start = CountDownLatch(1)
  return try {
    val futures = (1..count).map { pool.submit(Callable { check(start.await(5, TimeUnit.SECONDS)); block() }) }
    start.countDown()
    futures.map { it.get(15, TimeUnit.SECONDS) }
  } finally { pool.shutdownNow(); check(pool.awaitTermination(5, TimeUnit.SECONDS)) }
}
