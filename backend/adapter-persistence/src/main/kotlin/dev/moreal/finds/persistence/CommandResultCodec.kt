package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.CommandResourceId
import dev.moreal.finds.application.port.StoredCommandResult
import dev.moreal.finds.application.port.UnsupportedCommandResultException
import java.util.UUID
import kotlinx.serialization.json.*
import org.jooq.JSONB

/** Extend deliberately with each command: a valid UUID or key name alone is not proof of safety. */
internal object CommandResultCodec {
  fun encode(operation: String, result: StoredCommandResult): JSONB {
    validate(operation, result)
    return JSONB.valueOf(buildJsonObject {
      put("version", result.version)
      put("kind", result.kind)
      put("outcome", result.outcome)
      putJsonObject("resourceIds") {
        result.resourceIds.toSortedMap().forEach { (name, id) ->
          putJsonObject(name) {
            when (id) {
              is CommandResourceId.Number -> { put("type", "number"); put("value", id.value) }
              is CommandResourceId.Uuid -> { put("type", "uuid"); put("value", id.value.toString()) }
            }
          }
        }
      }
    }.toString())
  }

  fun decode(operation: String, json: JSONB): StoredCommandResult = try {
    val envelope = Json.parseToJsonElement(json.data()).jsonObject
    require(envelope.keys == setOf("version", "kind", "outcome", "resourceIds"))
    val version = envelope.getValue("version").jsonPrimitive
    require(!version.isString)
    val resources = envelope.getValue("resourceIds").jsonObject.mapValues { (_, element) ->
      val resource = element.jsonObject
      require(resource.keys == setOf("type", "value"))
      val value = resource.getValue("value").jsonPrimitive
      when (resource.string("type")) {
        "number" -> { require(!value.isString); CommandResourceId.Number(value.long) }
        "uuid" -> { require(value.isString); CommandResourceId.Uuid(UUID.fromString(value.content)) }
        else -> throw UnsupportedCommandResultException()
      }
    }
    StoredCommandResult(version.int, envelope.string("kind"), envelope.string("outcome"), resources)
      .also { validate(operation, it) }
  } catch (_: IllegalArgumentException) {
    throw UnsupportedCommandResultException()
  } catch (_: NoSuchElementException) {
    throw UnsupportedCommandResultException()
  }

  private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
    require(it.isString)
    it.content
  }

  private fun validate(operation: String, result: StoredCommandResult) {
    result.requireSupported(operation, 1)
    val supported = when (operation) {
      "career_site.register" -> result.outcome in setOf("CREATED", "ALREADY_REGISTERED") &&
        result.resourceIds.keys == setOf("career_site") && result.resourceIds["career_site"] is CommandResourceId.Number
      else -> false
    }
    if (!supported) throw UnsupportedCommandResultException()
  }
}
