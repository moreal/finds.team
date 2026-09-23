package dev.moreal.finds.application.port

import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID

/** Scope is a user ID, SYSTEM plus job identity, or a keyed normalized-email hash; never raw email. */
data class CommandRequestKey(val scope: String, val operation: String, val idempotencyKey: UUID) {
  init {
    require(scope.matches(Regex("[A-Za-z0-9:_-]{1,160}"))) { "Invalid command scope" }
    require(operation.matches(Regex("[a-z][a-z0-9_.]{0,95}"))) { "Invalid command operation" }
  }

  override fun toString(): String = "CommandRequestKey(operation=$operation, <redacted>)"
}

@JvmInline
value class CommandRequestHash(val value: String) {
  init {
    require(value.matches(Regex("[0-9a-f]{64}"))) { "Invalid command request hash" }
  }

  override fun toString(): String = "CommandRequestHash(<redacted>)"
}

enum class CommandRetention { ORDINARY, AUDIT }

data class CommandRequest(
  val key: CommandRequestKey,
  val requestHash: CommandRequestHash,
  val createdAt: Instant,
  val retention: CommandRetention = CommandRetention.ORDINARY,
) {
  val expiresAt: Instant? get() = if (retention == CommandRetention.ORDINARY) createdAt.plus(Duration.ofHours(24)) else null
}

/** Only stable non-secret resource identifiers; never session IDs, challenges or credential material. */
sealed interface CommandResourceId {
  data class Number(val value: Long) : CommandResourceId {
    init { require(value > 0) { "Resource ID must be positive" } }
  }

  data class Uuid(val value: UUID) : CommandResourceId
}

/**
 * Versioned semantic result, not an HTTP response or arbitrary JSON. Kind/outcome are developer-owned
 * categorical constants. Resource names/IDs must be explicitly allowlisted by each command codec;
 * syntax validation cannot establish that an otherwise valid identifier is non-secret.
 */
class StoredCommandResult(
  val version: Int,
  val kind: String,
  val outcome: String,
  resourceIds: Map<String, CommandResourceId> = emptyMap(),
) {
  val resourceIds: Map<String, CommandResourceId> = Collections.unmodifiableMap(LinkedHashMap(resourceIds))

  init {
    require(version > 0) { "Command result version must be positive" }
    require(kind.matches(Regex("[a-z][a-z0-9_.]{0,95}"))) { "Invalid command result kind" }
    require(outcome.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) { "Invalid command result outcome" }
    require(resourceIds.keys.all { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) }) { "Invalid resource name" }
  }

  /** Call before decoding; unsupported persisted data must never be treated as a fresh reservation. */
  fun requireSupported(expectedKind: String, expectedVersion: Int) {
    if (kind != expectedKind || version != expectedVersion) throw UnsupportedCommandResultException()
  }

  override fun toString(): String = "StoredCommandResult(version=$version, kind=$kind, outcome=$outcome)"
}

class UnsupportedCommandResultException : IllegalStateException("Unsupported stored command result")

sealed interface CommandReservation {
  data object Reserved : CommandReservation
  data class Replay(val result: StoredCommandResult) : CommandReservation
  data object Conflict : CommandReservation
}

interface CommandRequestStore {
  /**
   * Atomically reserve (scope, operation, key). A unique constraint arbitrates concurrent inserts:
   * the loser waits for the winner to commit/roll back, then reloads the completed result or reserves
   * after rollback. A different hash conflicts; the same hash replays regardless of new timestamps.
   * Never expose another transaction's pending row as Reserved. Lock/serialization failures propagate
   * as infrastructure failures, not conflicts. A repeated pending reservation in this transaction is
   * a programming error. Expiry is handled by retention maintenance, not an overwrite here.
   */
  fun reserve(request: CommandRequest): CommandReservation

  /**
   * Complete a key newly reserved by this transaction exactly once. Completion of absent, replayed or
   * already completed keys fails. Every reservation must complete before commit; on failure throw and
   * roll back. Business writes, audit, outbox, reservation and result commit together.
   */
  fun complete(key: CommandRequestKey, result: StoredCommandResult)
}
