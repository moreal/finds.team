package dev.moreal.finds.application.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64

@JvmInline
value class ApplicationCursor(val value: String)

class InvalidConnectionRequest : IllegalArgumentException("First must be between 1 and 100")
class InvalidConnectionCursor : IllegalArgumentException("Invalid connection cursor")

data class ConnectionRequest(val first: Int = 20, val after: ApplicationCursor? = null) {
  init { if (first !in 1..100) throw InvalidConnectionRequest() }
}
data class ConnectionEdge<T>(val node: T, val cursor: ApplicationCursor)
data class ConnectionPageInfo(
  val hasNextPage: Boolean,
  val hasPreviousPage: Boolean,
  val startCursor: ApplicationCursor?,
  val endCursor: ApplicationCursor?,
)
data class ConnectionPage<T>(val edges: List<ConnectionEdge<T>>, val pageInfo: ConnectionPageInfo, val totalCount: Long)
data class SkillRequirementCounts(val required: Long, val preferred: Long, val mentioned: Long)

/**
 * Versioned, canonical opaque cursors. A checksum detects corruption; it is not a signature
 * or an authorization boundary. Public discovery cursors require no deployment secret.
 * Scope includes the collection, ordering and all filters, but deliberately not page size.
 */
object ConnectionCursors {
  private val alphabet = Regex("[A-Za-z0-9_-]+")

  fun scope(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
      val bytes = part.toByteArray(UTF_8)
      digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
      digest.update(bytes)
    }
    return base64(digest.digest())
  }

  fun encode(scope: String, values: List<String>): ApplicationCursor {
    val payload = pack(listOf("v1", scope) + values)
    val token = base64(payload + digest(payload))
    require(token.length <= 2048) { "Cursor is too large" }
    return ApplicationCursor(token)
  }

  fun decode(cursor: ApplicationCursor, scope: String, valueCount: Int): List<String> {
    try {
      val token = cursor.value
      require(token.length in 1..2048 && alphabet.matches(token))
      val bytes = Base64.getUrlDecoder().decode(token)
      require(base64(bytes) == token && bytes.size > 32)
      val payload = bytes.copyOfRange(0, bytes.size - 32)
      require(MessageDigest.isEqual(digest(payload), bytes.copyOfRange(bytes.size - 32, bytes.size)))
      val input = DataInputStream(ByteArrayInputStream(payload))
      require(input.readUTF() == "v1" && input.readUTF() == scope)
      val values = List(valueCount) { input.readUTF() }
      require(input.available() == 0 && pack(listOf("v1", scope) + values).contentEquals(payload))
      return values
    } catch (_: IllegalArgumentException) {
      throw InvalidConnectionCursor()
    } catch (_: IOException) {
      throw InvalidConnectionCursor()
    }
  }

  private fun pack(parts: List<String>): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { output -> parts.forEach(output::writeUTF) }
    bytes.toByteArray()
  }
  private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
  private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
