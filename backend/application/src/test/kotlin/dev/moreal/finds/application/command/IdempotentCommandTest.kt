package dev.moreal.finds.application.command

import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.audit.AuditEvent
import dev.moreal.finds.application.audit.AuditOutcome
import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.testing.FakeTransaction
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.mail.MailMessageId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class IdempotentCommandTest {
  private val now = Instant.parse("2026-09-22T00:00:00Z")
  private val key = CommandRequestKey("SYSTEM:test-job", "career_site.register", UUID.randomUUID())
  private val fields = mapOf("url" to "https://example.com", "displayName" to "Example", "optional" to null)

  @Test
  fun `same key and hash replays original typed result without another effect or audit`() {
    val tx = FakeTransaction()
    val first = register(tx)
    assertEquals(Created(CareerSiteId(1)), first)
    assertEquals(first, register(tx))
    assertEquals(1, tx.sites.size)
    assertEquals(1, tx.auditEvents.size)
    assertEquals(1, tx.completedRequests.size)
    assertEquals(1, tx.outboxMessages.size)
  }

  @Test
  fun `different request hash conflicts without another effect or audit`() {
    val tx = FakeTransaction()
    register(tx)
    assertEquals(Conflict, register(tx, fields + ("displayName" to "Changed")))
    assertEquals(1, tx.sites.size)
    assertEquals(1, tx.auditEvents.size)
    assertEquals(1, tx.outboxMessages.size)
  }

  @Test
  fun `failure after completion rolls back business audit outbox and reservation`() {
    val tx = FakeTransaction()
    assertFailsWith<IllegalStateException> { register(tx, failAfterComplete = true) }
    assertTrue(tx.sites.isEmpty())
    assertTrue(tx.auditEvents.isEmpty())
    assertTrue(tx.completedRequests.isEmpty())
    assertTrue(tx.outboxMessages.isEmpty())
    assertEquals(Created(CareerSiteId(1)), register(tx))
  }

  @Test
  fun `audit failure rolls back inserted business row and key can be retried`() {
    val tx = FakeTransaction(auditFailure = { error("audit unavailable") })
    assertFailsWith<IllegalStateException> { register(tx) }
    assertTrue(tx.sites.isEmpty())
    assertTrue(tx.completedRequests.isEmpty())
    tx.auditFailure = null
    assertEquals(Created(CareerSiteId(1)), register(tx))
  }

  @Test
  fun `simultaneous attempts produce one committed effect and replay the same result`() {
    val tx = FakeTransaction()
    Executors.newFixedThreadPool(2).use { pool ->
      try {
        val results = pool.invokeAll(List(2) { Callable { register(tx) } }, 5, TimeUnit.SECONDS)
          .map { it.get(5, TimeUnit.SECONDS) }
        assertEquals(listOf(Created(CareerSiteId(1)), Created(CareerSiteId(1))), results)
      } finally { pool.shutdownNow() }
    }
    assertEquals(1, tx.sites.size)
    assertEquals(1, tx.auditEvents.size)
  }

  @Test
  fun `uncompleted reservation cannot commit and context cannot escape transaction`() {
    val tx = FakeTransaction()
    lateinit var escaped: TransactionContext
    assertFailsWith<IllegalStateException> {
      tx.execute { context ->
        escaped = context
        context.commandRequests.reserve(request(fields))
      }
    }
    assertFailsWith<IllegalStateException> { escaped.careerSites.findEnabled() }
    assertEquals(Created(CareerSiteId(1)), register(tx))
  }

  @Test
  fun `pending reservation errors before comparing hashes`() {
    val tx = FakeTransaction()
    tx.execute { context ->
      context.commandRequests.reserve(request(fields))
      for (values in listOf(fields, fields + ("displayName" to "Different"))) {
        assertFailsWith<IllegalStateException> { context.commandRequests.reserve(request(values)) }
      }
      context.commandRequests.complete(key, storedResult(1))
    }
  }

  @Test
  fun `request scope and operation are part of the key`() {
    val tx = FakeTransaction()
    val first = request(fields)
    tx.execute { context ->
      listOf(first, first.copy(key = key.copy(scope = "SYSTEM:other")),
        first.copy(key = key.copy(operation = "career_site.other"))).forEach {
        assertEquals(CommandReservation.Reserved, context.commandRequests.reserve(it))
        context.commandRequests.complete(it.key, storedResult(1))
      }
    }
    assertEquals(3, tx.completedRequests.size)
  }

  @Test
  fun `unsupported stored result version fails closed without repeating a command`() {
    val tx = FakeTransaction()
    tx.execute { context ->
      context.commandRequests.reserve(request(fields))
      context.commandRequests.complete(key, storedResult(1, version = 99))
    }
    assertFailsWith<UnsupportedCommandResultException> { register(tx) }
    assertTrue(tx.sites.isEmpty())
    assertTrue(tx.auditEvents.isEmpty())
  }

  @Test
  fun `canonical request sorts nested fields and keeps null distinct from missing`() {
    val first = linkedMapOf("z" to null, "a" to linkedMapOf("y" to true, "x" to 1))
    val second = linkedMapOf("a" to linkedMapOf("x" to 1, "y" to true), "z" to null)
    assertEquals("{\"a\":{\"x\":1,\"y\":true},\"z\":null}", CanonicalCommandEncoder.encode(first))
    assertEquals(CanonicalCommandEncoder.hash(first), CanonicalCommandEncoder.hash(second))
    assertNotEquals(CanonicalCommandEncoder.hash(first), CanonicalCommandEncoder.hash(first - "z"))
    assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", CanonicalCommandEncoder.hash(emptyMap()).value)
  }

  @Test
  fun `canonical request escapes strings preserves array order and rejects ambiguous values`() {
    assertEquals("{\"text\":\"한글\\n\\\"\\\\\\u0000\"}", CanonicalCommandEncoder.encode(mapOf("text" to "한글\n\"\\\u0000")))
    assertNotEquals(CanonicalCommandEncoder.hash(mapOf("ids" to listOf(1, 2))), CanonicalCommandEncoder.hash(mapOf("ids" to listOf(2, 1))))
    listOf(Double.NaN, 1.0, Any(), mapOf(1 to "value"), "\uD800").forEach { value ->
      assertFailsWith<IllegalArgumentException> { CanonicalCommandEncoder.hash(mapOf("value" to value)) }
    }
  }

  @Test
  fun `stored result fields cannot carry arbitrary request bodies or secrets`() {
    assertFailsWith<IllegalArgumentException> {
      StoredCommandResult(1, "site.created", "user@example.com", mapOf("site" to CommandResourceId.Number(1)))
    }
    assertFailsWith<IllegalArgumentException> { CommandResourceId.Number(0) }
    val ids = mutableMapOf<String, CommandResourceId>("site" to CommandResourceId.Number(1))
    val stored = StoredCommandResult(1, "site.created", "CREATED", ids)
    ids.clear()
    assertEquals(CommandResourceId.Number(1), stored.resourceIds["site"])
    assertFalse(stored.toString().contains("resourceIds"))
  }

  private fun request(values: Map<String, Any?>) = CommandRequest(key, CanonicalCommandEncoder.hash(values), now)

  private fun storedResult(id: Long, version: Int = 1) =
    StoredCommandResult(version, "site.created", "CREATED", mapOf("site" to CommandResourceId.Number(id)))

  private fun register(tx: TransactionPort, values: Map<String, Any?> = fields, failAfterComplete: Boolean = false): Result =
    tx.execute { context ->
      when (val reservation = context.commandRequests.reserve(request(values))) {
        CommandReservation.Conflict -> Conflict
        is CommandReservation.Replay -> {
          reservation.result.requireSupported("site.created", 1)
          Created(CareerSiteId((reservation.result.resourceIds.getValue("site") as CommandResourceId.Number).value))
        }
        CommandReservation.Reserved -> {
          val site = (context.careerSites.insert(NewCareerSite(
            (SiteUrl.parse("https://example.com") as SiteUrlResult.Valid).url,
            SourceProvider.FLEX, "Example",
          )) as InsertCareerSiteResult.Inserted).site
          context.auditLog.append(AuditEvent(
            UUID.randomUUID(), 1, now, Actor.System, AuditAction.CAREER_SITE_REGISTERED,
            "career_site", site.id.value.toString(), UUID.randomUUID(), UUID.randomUUID(), AuditOutcome.SUCCEEDED,
          ))
          context.outbox.enqueue(MailPayloadMetadata(MailMessageId(UUID.randomUUID()), "TEST", now.plusSeconds(60)), byteArrayOf(1), now)
          context.commandRequests.complete(key, storedResult(site.id.value))
          if (failAfterComplete) error("business failure")
          Created(site.id)
        }
      }
    }

  private sealed interface Result
  private data class Created(val id: CareerSiteId) : Result
  private data object Conflict : Result
}
