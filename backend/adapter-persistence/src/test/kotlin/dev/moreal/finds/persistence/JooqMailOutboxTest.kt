package dev.moreal.finds.persistence

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.moreal.finds.application.port.MailPayloadMetadata
import dev.moreal.mail.MailDeliveryResult
import dev.moreal.mail.MailFailure
import dev.moreal.mail.MailMessageId
import dev.moreal.mail.MailProvider
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JooqMailOutboxTest : PostgresIntegrationTest() {
  @Test
  fun `migrations create encrypted outbox and attempt storage`() {
    val (dataSource, _) = migratedContext()
    dataSource.connection.use { connection ->
      val columns = connection.metaData.getColumns(null, "public", "mail_outbox", "%").use {
        generateSequence { if (it.next()) it.getString("COLUMN_NAME") else null }.toSet()
      }
      assertTrue(columns.containsAll(setOf("message_id", "payload_ciphertext", "payload_nonce", "key_version")))
      val attempts = connection.metaData.getTables(null, "public", "mail_delivery_attempts", arrayOf("TABLE")).use {
        it.next()
      }
      assertTrue(attempts)
    }
  }

  @Test
  fun `enqueue encrypts before SQL and database rows and TRACE logs contain no plaintext or key`() {
    val (dataSource, context) = migratedContext()
    val box = JooqMailOutbox(context, crypto())
    val metadata = metadata().let { it.copy(expiresAt = it.expiresAt.plusNanos(123456789)) }
    val secret = "recipient-private@example.com OTP=419573 private-template-body".encodeToByteArray()
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val previous = root.level
    val logs = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(logs)
    root.level = Level.TRACE
    try {
      box.enqueue(metadata, secret, NOW)
      val leases = box.leaseBatch("one", NOW, TTL, 1)
      assertEquals(1, leases.size)
      assertContentEquals(secret, crypto().decrypt(leases.single().metadata, leases.single().payload))
      assertFalse(leases.single().recovered)
      assertEquals(7, leases.single().payload.keyVersion)
      assertEquals(12, leases.single().payload.nonce.size)
      assertEquals(secret.size + 16, leases.single().payload.ciphertext.size)
      assertEquals(metadata.expiresAt.truncatedTo(ChronoUnit.MICROS), leases.single().metadata.expiresAt)
      assertEquals(1, box.recordAttempt(leases.single(), ACCEPTED, NOW.plusSeconds(1)))

      dataSource.connection.use { connection ->
        val fields = connection.prepareStatement(
          "SELECT table_name, column_name, data_type FROM information_schema.columns " +
            "WHERE table_schema='public' AND data_type IN ('text','character varying','character','bytea')",
        ).use { statement ->
          statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(Triple(rows.getString(1), rows.getString(2), rows.getString(3))) }
          }
        }
        fields.forEach { (table, column, type) ->
          connection.createStatement().use { statement ->
            statement.executeQuery("SELECT \"$column\" FROM \"$table\"").use { rows ->
              while (rows.next()) {
                val bytes = if (type == "bytea") rows.getBytes(1) else rows.getString(1)?.encodeToByteArray()
                if (bytes != null) {
                  assertFalse(bytes.decodeToString().contains("419573"), "$table.$column leaked OTP")
                  assertFalse(bytes.decodeToString().contains("recipient-private"), "$table.$column leaked recipient")
                  assertFalse(bytes.toList().windowed(KEY.size).any { it == KEY.toList() }, "$table.$column leaked key")
                }
              }
            }
          }
        }
      }
      assertTrue(box.complete(leases.single(), ACCEPTED, NOW.plusSeconds(1)))
      val output = logs.list.joinToString("\n") { it.formattedMessage }
      assertTrue(logs.list.isNotEmpty(), "TRACE capture must observe actual SQL logs")
      assertFalse(output.contains("419573"))
      assertFalse(output.contains("recipient-private"))
      assertFalse(output.contains("private-template-body"))
      val stolen = leases.single()
      assertFailsWith<IllegalStateException> {
        AesGcmMailPayloadCrypto(7, mapOf(7 to ByteArray(32) { 99 }))
          .decrypt(stolen.metadata, stolen.payload)
      }
    } finally {
      root.level = previous
      root.detachAppender(logs)
      logs.stop()
    }
  }

  @Test
  fun `message id is unique and expired payloads cannot be enqueued`() {
    val (_, context) = migratedContext()
    val box = JooqMailOutbox(context, crypto())
    val metadata = metadata()
    box.enqueue(metadata, PAYLOAD, NOW)
    assertFailsWith<DataAccessException> { box.enqueue(metadata, PAYLOAD, NOW) }
    assertFailsWith<IllegalArgumentException> { box.enqueue(metadata().copy(expiresAt = NOW), PAYLOAD, NOW) }
    assertEquals(1, context.fetchCount(DSL.table("mail_outbox")))
  }

  @Test
  fun `enqueue participates in caller transaction rollback`() {
    val (_, context) = migratedContext()
    assertFailsWith<IllegalStateException> {
      context.transaction { configuration ->
        JooqMailOutbox(DSL.using(configuration), crypto()).enqueue(metadata(), PAYLOAD, NOW)
        assertEquals(1, DSL.using(configuration).fetchCount(DSL.table("mail_outbox")))
        error("Rollback transaction")
      }
    }
    assertEquals(0, context.fetchCount(DSL.table("mail_outbox")))
  }

  @Test
  fun `second worker skips a row locked by first worker without blocking`() {
    val (dataSource, context) = migratedContext()
    val box = JooqMailOutbox(context, crypto())
    val first = metadata()
    val second = metadata()
    box.enqueue(first, PAYLOAD, NOW)
    box.enqueue(second, PAYLOAD, NOW.plusSeconds(1))
    val pool = Executors.newSingleThreadExecutor()
    try {
      dataSource.connection.use { connection ->
        connection.autoCommit = false
        val transaction = DSL.using(connection, SQLDialect.POSTGRES)
        val locked = JooqMailOutbox(transaction, crypto()).leaseBatch("one", NOW.plusSeconds(2), TTL, 1)
        assertEquals(listOf(first.id), locked.map { it.metadata.id })
        val other = pool.submit<List<dev.moreal.finds.application.port.MailOutboxLease>> {
          JooqMailOutbox(DSL.using(dataSource, SQLDialect.POSTGRES), crypto())
            .leaseBatch("two", NOW.plusSeconds(2), TTL, 10)
        }.get(5, TimeUnit.SECONDS)
        assertEquals(listOf(second.id), other.map { it.metadata.id })
        assertEquals(emptyList(), box.leaseBatch("three", NOW.plusSeconds(2), TTL, 10))
        connection.commit()
      }
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `expired leases recover with new token and reject every stale worker mutation`() {
    val (_, context) = migratedContext()
    val box = JooqMailOutbox(context, crypto())
    box.enqueue(metadata(), PAYLOAD, NOW)
    val leases = box.leaseBatch("same-worker", NOW, TTL, 1)
    assertEquals(1, leases.size)
    val first = leases.single()
    assertTrue(box.leaseBatch("two", NOW.plusSeconds(29), TTL, 1).isEmpty())
    assertNull(box.recordAttempt(first, ACCEPTED, NOW.plusSeconds(30)))
    assertFalse(box.complete(first, ACCEPTED, NOW.plusSeconds(30)))
    assertFalse(box.reschedule(first, NOW.plusSeconds(35), NOW.plusSeconds(30)))
    val recovered = box.leaseBatch("same-worker", NOW.plusSeconds(30), TTL, 1).single()
    assertTrue(recovered.recovered)
    assertTrue(first.token != recovered.token)
    assertEquals(first.metadata.id, recovered.metadata.id)
    assertNull(box.recordAttempt(first, ACCEPTED, NOW.plusSeconds(31)))
    assertFalse(box.complete(first, ACCEPTED, NOW.plusSeconds(31)))
    assertFalse(box.reschedule(first, NOW.plusSeconds(35), NOW.plusSeconds(31)))
    assertEquals(1, box.recordAttempt(recovered, INDETERMINATE, NOW.plusSeconds(31)))
    assertTrue(box.complete(recovered, INDETERMINATE, NOW.plusSeconds(31)))
    assertTrue(box.leaseBatch("three", NOW.plusSeconds(90), TTL, 1).isEmpty())
  }

  @Test
  fun `attempts persist sequential outcomes and reschedule delays next lease`() {
    val (_, context) = migratedContext()
    val box = JooqMailOutbox(context, crypto())
    val metadata = metadata()
    box.enqueue(metadata, PAYLOAD, NOW)
    val leases = box.leaseBatch("one", NOW, TTL, 1)
    assertEquals(1, leases.size)
    val first = leases.single()
    assertEquals(1, box.recordAttempt(first, RETRYABLE, NOW.plusSeconds(1)))
    assertEquals(2, box.recordAttempt(first, RETRYABLE, NOW.plusSeconds(2)))
    assertFailsWith<IllegalArgumentException> { box.complete(first, RETRYABLE, NOW.plusSeconds(2)) }
    assertTrue(box.reschedule(first, NOW.plusSeconds(60), NOW.plusSeconds(2)))
    assertTrue(box.leaseBatch("two", NOW.plusSeconds(59), TTL, 1).isEmpty())
    val next = box.leaseBatch("two", NOW.plusSeconds(60), TTL, 1).single()
    assertEquals(2, next.attemptCount)
    assertFalse(next.recovered)
    assertEquals(metadata.id, next.metadata.id)
    assertEquals(3, box.recordAttempt(next, ACCEPTED, NOW.plusSeconds(61)))
    assertTrue(box.complete(next, ACCEPTED, NOW.plusSeconds(61)))
    val attempts = context.fetch("SELECT * FROM mail_delivery_attempts ORDER BY attempt_number")
    assertEquals(listOf(1, 2, 3), attempts.map { it.get("attempt_number") })
    assertEquals(listOf("REJECTED", "REJECTED", "ACCEPTED"), attempts.map { it.get("outcome") })
    assertEquals("receipt", attempts.last().get("provider_message_id"))
    assertEquals("THROTTLED", attempts.first().get("failure"))
    assertNull(box.recordAttempt(next, ACCEPTED, NOW.plusSeconds(62)))
    assertFalse(box.complete(next, ACCEPTED, NOW.plusSeconds(62)))
    assertEquals("ACCEPTED", row(context).get("state"))
    assertNull(row(context).get("payload_ciphertext"))
    assertNull(row(context).get("payload_nonce"))
    assertNull(row(context).get("lease_token"))
    assertEquals("receipt", row(context).get("provider_message_id"))
    assertTrue(box.leaseBatch("three", NOW.plusSeconds(120), TTL, 1).isEmpty())
  }

  @Test
  fun `expired pending leased and indeterminate rows are redacted and never delivered`() {
    val (_, context) = migratedContext()
    val box = JooqMailOutbox(context, crypto())
    repeat(3) { box.enqueue(metadata().copy(expiresAt = NOW.plusSeconds(20)), PAYLOAD, NOW) }
    val leases = box.leaseBatch("one", NOW, TTL, 2)
    assertEquals(2, leases.size)
    assertTrue(box.complete(leases.first(), INDETERMINATE, NOW.plusSeconds(1)))
    assertNotNull(context.fetchOne("SELECT payload_ciphertext FROM mail_outbox WHERE state = 'INDETERMINATE'")!!.get(0))
    assertTrue(box.leaseBatch("two", NOW.plusSeconds(20), TTL, 5).isEmpty())
    assertFalse(box.complete(leases.last(), ACCEPTED, NOW.plusSeconds(20)))
    assertEquals(3, box.redactExpired(NOW.plusSeconds(20)))
    assertEquals(0, box.redactExpired(NOW.plusSeconds(21)))
    context.fetch("SELECT * FROM mail_outbox").forEach { row ->
      assertEquals("EXPIRED", row.get("state"))
      assertNull(row.get("payload_ciphertext"))
      assertNull(row.get("payload_nonce"))
      assertNull(row.get("lease_token"))
    }
  }

  @Test
  fun `permanent rejection redacts and accepted history survives expiry cleanup`() {
    val (_, context) = migratedContext()
    val box = JooqMailOutbox(context, crypto())
    repeat(2) { box.enqueue(metadata(), PAYLOAD, NOW) }
    val leases = box.leaseBatch("one", NOW, TTL, 2)
    assertEquals(2, leases.size)
    assertTrue(box.complete(leases.first(), ACCEPTED, NOW.plusSeconds(1)))
    assertTrue(box.complete(leases.last(), MailDeliveryResult.Rejected(PROVIDER, MailFailure.INVALID_RECIPIENT, false), NOW.plusSeconds(1)))
    assertEquals(0, box.redactExpired(NOW.plusSeconds(600)))
    assertEquals(setOf("ACCEPTED", "FAILED"), context.fetch("SELECT state FROM mail_outbox").map { it.get(0) }.toSet())
    assertEquals(0, context.fetchCount(DSL.table("mail_outbox"), DSL.field("payload_ciphertext").isNotNull))
  }

  private fun row(context: DSLContext) = context.fetchOne("SELECT * FROM mail_outbox")!!
  private fun metadata() = MailPayloadMetadata(MailMessageId.new(), "VERIFY_EMAIL", NOW.plusSeconds(300))
  private fun crypto() = AesGcmMailPayloadCrypto(7, mapOf(7 to KEY))

  private companion object {
    val NOW: Instant = Instant.parse("2026-09-23T00:00:00Z")
    val TTL: Duration = Duration.ofSeconds(30)
    val KEY = ByteArray(32) { (it + 1).toByte() }
    val PAYLOAD = "recipient@example.com 419573".encodeToByteArray()
    val PROVIDER = MailProvider("test")
    val ACCEPTED = MailDeliveryResult.Accepted(PROVIDER, "receipt")
    val RETRYABLE = MailDeliveryResult.Rejected(PROVIDER, MailFailure.THROTTLED, true)
    val INDETERMINATE = MailDeliveryResult.Indeterminate(PROVIDER, MailFailure.TIMEOUT)
  }
}
