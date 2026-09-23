package dev.moreal.finds.graphql

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

enum class NodeType { JobPosting, CareerSite, User, Skill, CrawlRun, AuditEvent }
data class GlobalId(val type: NodeType, val value: String)
open class GraphqlRequestException(val code: ApiErrorCode, message: String) : IllegalArgumentException(message)
class GlobalIdException : GraphqlRequestException(ApiErrorCode.INVALID_INPUT, "Invalid global ID")

/** The wire format is owned by this adapter; domain/application IDs never depend on it. */
object GlobalIdCodec {
  private val alphabet = Regex("[A-Za-z0-9_-]+")
  private val number = Regex("[1-9][0-9]{0,18}")
  private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

  fun encode(type: NodeType, value: Long): String = encode(type, value.toString())
  fun encode(type: NodeType, value: UUID): String = encode(type, value.toString())
  fun encode(type: NodeType, value: String): String {
    validate(type, value)
    return base64("v1:${type.name}:$value".toByteArray(StandardCharsets.UTF_8))
  }

  fun decode(id: String): GlobalId {
    // Bound work and reject padding, whitespace, alternate alphabets and unused-bit aliases.
    if (id.length !in 1..128 || !alphabet.matches(id)) throw GlobalIdException()
    val bytes = try { Base64.getUrlDecoder().decode(id) } catch (_: IllegalArgumentException) {
      throw GlobalIdException()
    }
    if (base64(bytes) != id) throw GlobalIdException()
    val parts = String(bytes, StandardCharsets.UTF_8).split(':')
    if (parts.size != 3 || parts[0] != "v1") throw GlobalIdException()
    val type = NodeType.entries.firstOrNull { it.name == parts[1] } ?: throw GlobalIdException()
    validate(type, parts[2])
    return GlobalId(type, parts[2])
  }

  fun decode(expectedType: NodeType, id: String): String {
    val decoded = decode(id)
    if (decoded.type != expectedType) throw GlobalIdException()
    return decoded.value
  }

  private fun validate(type: NodeType, value: String) {
    val valid = when (type) {
      NodeType.User, NodeType.AuditEvent -> uuid.matches(value)
      else -> number.matches(value) && value.toLongOrNull() != null
    }
    if (!valid) throw GlobalIdException()
  }

  private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
