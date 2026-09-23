package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.CommandRequest
import dev.moreal.finds.application.port.CommandRequestKey
import dev.moreal.finds.application.port.CommandRequestStore
import dev.moreal.finds.application.port.CommandReservation
import dev.moreal.finds.application.port.StoredCommandResult
import dev.moreal.finds.persistence.jooq.generated.tables.references.COMMAND_REQUESTS
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.impl.DSL

internal class JooqCommandRequestStore(
  private val db: DSLContext,
  private val scope: TransactionScope,
) : CommandRequestStore {
  private val reserved = mutableSetOf<CommandRequestKey>()

  override fun reserve(request: CommandRequest): CommandReservation = scope.access {
    check(request.key !in reserved) { "Command already reserved in this transaction" }
    repeat(MAX_RESERVATION_ATTEMPTS) {
      val inserted = db.insertInto(COMMAND_REQUESTS)
        .set(COMMAND_REQUESTS.SCOPE, request.key.scope)
        .set(COMMAND_REQUESTS.OPERATION, request.key.operation)
        .set(COMMAND_REQUESTS.IDEMPOTENCY_KEY, request.key.idempotencyKey)
        .set(COMMAND_REQUESTS.REQUEST_HASH, request.requestHash.value)
        .set(COMMAND_REQUESTS.CREATED_AT, request.createdAt.atOffset(ZoneOffset.UTC))
        .set(COMMAND_REQUESTS.RETENTION, request.retention.name)
        .set(COMMAND_REQUESTS.EXPIRES_AT, request.expiresAt?.atOffset(ZoneOffset.UTC))
        .onConflict(COMMAND_REQUESTS.SCOPE, COMMAND_REQUESTS.OPERATION, COMMAND_REQUESTS.IDEMPOTENCY_KEY)
        .doNothing()
        .execute()
      if (inserted == 1) {
        reserved += request.key
        return@access CommandReservation.Reserved
      }
      // At READ COMMITTED, ordinary retention may delete the committed conflict between these
      // statements. Re-enter unique-key arbitration if it vanished; never treat a missing row as
      // a reservation we own. Serialization/lock failures still propagate without retry.
      val row = db.selectFrom(COMMAND_REQUESTS).where(keyCondition(request.key)).forUpdate().fetchOne()
        ?: return@repeat
      return@access if (row.requestHash != request.requestHash.value) CommandReservation.Conflict
      else CommandReservation.Replay(CommandResultCodec.decode(request.key.operation,
        checkNotNull(row.result) { "Command reservation has no completed result" }))
    }
    error("Command reservation changed repeatedly during retention cleanup")
  }

  override fun complete(key: CommandRequestKey, result: StoredCommandResult) = scope.access {
    check(key in reserved) { "Command was not reserved by this transaction" }
    val encoded = CommandResultCodec.encode(key.operation, result)
    val updated = db.update(COMMAND_REQUESTS)
      .set(COMMAND_REQUESTS.RESULT, encoded)
      .set(COMMAND_REQUESTS.COMPLETED_AT, DSL.greatest(COMMAND_REQUESTS.CREATED_AT, DSL.currentOffsetDateTime()))
      .where(keyCondition(key))
      .and(COMMAND_REQUESTS.RESULT.isNull)
      .execute()
    check(updated == 1) { "Command reservation could not be completed" }
    reserved.remove(key)
    Unit
  }

  internal fun checkCompleted() = check(reserved.isEmpty()) { "Uncompleted command reservation" }

  private fun keyCondition(key: CommandRequestKey) = COMMAND_REQUESTS.SCOPE.eq(key.scope)
    .and(COMMAND_REQUESTS.OPERATION.eq(key.operation))
    .and(COMMAND_REQUESTS.IDEMPOTENCY_KEY.eq(key.idempotencyKey))

  private companion object { const val MAX_RESERVATION_ATTEMPTS = 3 }
}
