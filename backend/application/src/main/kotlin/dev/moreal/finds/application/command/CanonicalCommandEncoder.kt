package dev.moreal.finds.application.command

import dev.moreal.finds.application.port.CommandRequestHash
import java.security.MessageDigest

/**
 * Explicit semantic fields, never reflective HTTP/request serialization. Callers supply every field,
 * including null defaults, after validation/normalization. Maps sort recursively; list order matters.
 * Values are null, String, Boolean, Byte/Short/Int/Long, lists or string-keyed maps. Convert domain
 * values explicitly; floating point and arbitrary objects are rejected. Do not log the encoding or
 * include authentication proofs. This stable format must be versioned at the operation if changed.
 */
object CanonicalCommandEncoder {
  fun encode(fields: Map<String, Any?>): String = buildString { appendValue(fields) }

  fun hash(fields: Map<String, Any?>): CommandRequestHash = CommandRequestHash(
    MessageDigest.getInstance("SHA-256").digest(encode(fields).toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) },
  )

  private fun StringBuilder.appendValue(value: Any?, depth: Int = 0) {
    require(depth <= 64) { "Command fields are too deeply nested" }
    when (value) {
      null -> append("null")
      is String -> appendQuoted(value)
      is Boolean, is Byte, is Short, is Int, is Long -> append(value)
      is Map<*, *> -> {
        require(value.keys.all { it is String }) { "Command field names must be strings" }
        append('{')
        value.keys.map { it as String }.sorted().forEachIndexed { index, name ->
          if (index > 0) append(',')
          appendQuoted(name)
          append(':')
          appendValue(value[name], depth + 1)
        }
        append('}')
      }
      is List<*> -> {
        append('[')
        value.forEachIndexed { index, element ->
          if (index > 0) append(',')
          appendValue(element, depth + 1)
        }
        append(']')
      }
      else -> throw IllegalArgumentException("Unsupported command field type")
    }
  }

  private fun StringBuilder.appendQuoted(value: String) {
    append('"')
    value.forEachIndexed { index, char ->
      when (char) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000c' -> append("\\f")
        else -> {
          // UTF-8 replacement of lone surrogates would create hash collisions between inputs.
          require(!char.isHighSurrogate() || value.getOrNull(index + 1)?.isLowSurrogate() == true) {
            "Invalid Unicode command field"
          }
          require(!char.isLowSurrogate() || value.getOrNull(index - 1)?.isHighSurrogate() == true) {
            "Invalid Unicode command field"
          }
          if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
      }
    }
    append('"')
  }
}
