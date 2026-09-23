package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class DiscoveryQueriesTest {
  @Test
  fun `forward requests reject zero negative and excessive bounds with typed errors`() {
    listOf(Int.MIN_VALUE, -1, 0, 101, Int.MAX_VALUE).forEach { first ->
      assertFailsWith<InvalidConnectionRequest> { ConnectionRequest(first) }
    }
    assertEquals(1, ConnectionRequest(1).first)
    assertEquals(100, ConnectionRequest(100).first)
    assertEquals(20, ConnectionRequest().first)
  }

  @Test
  fun `cursor round trips order values without exposing transport IDs`() {
    val cursor = ConnectionCursors.encode("postings:updated-desc:filter", listOf("2026-09-22T00:00:00.123456Z", "42"))
    assertEquals(listOf("2026-09-22T00:00:00.123456Z", "42"),
      ConnectionCursors.decode(cursor, "postings:updated-desc:filter", 2))
    assertFailsWith<InvalidConnectionCursor> { ConnectionCursors.decode(cursor, "postings:updated-desc:other", 2) }
    assertFailsWith<InvalidConnectionCursor> { ConnectionCursors.decode(cursor, "sites:id-asc", 2) }
    assertFailsWith<InvalidConnectionCursor> { ConnectionCursors.decode(cursor, "postings:updated-desc:filter", 1) }
  }

  @Test
  fun `malformed noncanonical oversized and tampered cursors fail with typed errors`() {
    val valid = ConnectionCursors.encode("scope", listOf("42"))
    val bytes = Base64.getUrlDecoder().decode(valid.value)
    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
    val tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    listOf("", "not a cursor", "a", "A".repeat(8192), valid.value + "=", tampered).forEach { value ->
      assertFailsWith<InvalidConnectionCursor>(value.take(50)) {
        ConnectionCursors.decode(ApplicationCursor(value), "scope", 1)
      }
    }
  }

  @Test
  fun `unsupported cursor version is rejected even with a valid checksum`() {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output -> listOf("v2", "scope", "42").forEach(output::writeUTF) }
    val payload = bytes.toByteArray()
    val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(payload + checksum)
    assertFailsWith<InvalidConnectionCursor> { ConnectionCursors.decode(ApplicationCursor(token), "scope", 1) }
  }

  @Test
  fun `scope hashing is unambiguous for arbitrary length filter text`() {
    assertNotEquals(ConnectionCursors.scope("a", "bc"), ConnectionCursors.scope("ab", "c"))
    val large = "한".repeat(70_000)
    assertNotEquals(ConnectionCursors.scope(large), ConnectionCursors.scope(large + "a"))
  }
}
