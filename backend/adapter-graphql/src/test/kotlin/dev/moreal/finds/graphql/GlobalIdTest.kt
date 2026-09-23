package dev.moreal.finds.graphql

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GlobalIdTest {
  @Test fun `every node type round trips its canonical domain identifier`() {
    for (type in NodeType.entries) {
      val value = if (type in setOf(NodeType.User, NodeType.AuditEvent)) UUID_ID else "9223372036854775807"
      val id = GlobalIdCodec.encode(type, value)
      assertEquals(GlobalId(type, value), GlobalIdCodec.decode(id))
      assertEquals(value, GlobalIdCodec.decode(type, id))
    }
    assertEquals("djE6Sm9iUG9zdGluZzoxMjM", GlobalIdCodec.encode(NodeType.JobPosting, "123"))
  }

  @Test fun `malformed noncanonical and out of range identifiers are rejected without echoing input`() {
    val invalid = listOf("", "123", "bad!", "djE6Sm9iUG9zdGluZzoxMjM=", "djE6Sm9iUG9zdGluZzoxMjN",
      wire("v1:123"), wire("v1::123"), wire("v2:JobPosting:123"), wire("v1:Unknown:123"),
      wire("v1:JobPosting:123:extra"), wire("v1:User:1-1-1-1-1"), wire("v1:User:${UUID_ID.uppercase()}"),
      wire("v1:User:123"), wire("v1:AuditEvent:123")) +
      listOf("0", "-1", "+1", "01", " 1", "1 ", "1.0", "9223372036854775808", UUID_ID)
        .map { wire("v1:JobPosting:$it") }
    for (id in invalid) {
      val error = assertFailsWith<GlobalIdException>(id) { GlobalIdCodec.decode(id) }
      assertEquals(ApiErrorCode.INVALID_INPUT, error.code)
      assertEquals("Invalid global ID", error.message)
      assertNull(error.cause)
    }
    for (value in listOf("0", "-1", "01", "9223372036854775808")) {
      assertFailsWith<GlobalIdException> { GlobalIdCodec.encode(NodeType.JobPosting, value) }
    }
  }

  @Test fun `valid IDs cannot be confused across any node types`() {
    for (actual in NodeType.entries) {
      val value = if (actual in setOf(NodeType.User, NodeType.AuditEvent)) UUID_ID else "123"
      val id = GlobalIdCodec.encode(actual, value)
      for (expected in NodeType.entries.filter { it != actual }) {
        val error = assertFailsWith<GlobalIdException> { GlobalIdCodec.decode(expected, id) }
        assertEquals(ApiErrorCode.INVALID_INPUT, error.code)
        assertEquals("Invalid global ID", error.message)
      }
    }
  }

  private fun wire(payload: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
  private companion object { const val UUID_ID = "a6c5b651-4c67-4c17-aa5c-6476f3a1c111" }
}
