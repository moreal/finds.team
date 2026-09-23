package dev.moreal.finds.application.command

import java.util.UUID

data class CommandMetadata(
  val requestId: UUID,
  val correlationId: UUID,
  val idempotencyKey: UUID? = null,
) {
  companion object {
    private val UUID_PATTERN = Regex(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
    )

    fun parse(requestId: String, correlationId: String, idempotencyKey: String? = null): CommandMetadata =
      CommandMetadata(
        requestId = parseUuid(requestId, "Request ID"),
        correlationId = parseUuid(correlationId, "Correlation ID"),
        idempotencyKey = idempotencyKey?.let { parseUuid(it, "Idempotency key") },
      )

    private fun parseUuid(value: String, label: String): UUID {
      require(UUID_PATTERN.matches(value)) { "$label must be a UUID" }
      return UUID.fromString(value)
    }
  }
}
