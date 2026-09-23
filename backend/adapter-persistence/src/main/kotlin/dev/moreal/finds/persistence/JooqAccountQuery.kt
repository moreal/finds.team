package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.*
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.usecase.SessionSummary
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds.persistence.jooq.generated.tables.references.*
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.ZoneOffset.UTC
import java.util.UUID

class JooqAccountQuery(private val context: DSLContext) : AccountQueryPort {
  override fun passkeys(keys: List<PasskeyPageKey>) = boundedBatch(context, keys.map { key -> {
    val p = PASSKEY_CREDENTIALS
    val scope = ConnectionCursors.scope("passkeys", "id-asc", key.userId.value.toString())
    val id = key.page.after?.let { uuidCursorId(it, scope) }
    // Select metadata only: never hydrate public key COSE, credential bytes, or authenticator state.
    val metadata = DSL.row(p.MANAGEMENT_ID, p.LABEL, p.CREATED_AT, p.LAST_USED_AT).mapping { managementId, label, created, used ->
      ManagedPasskey(managementId!!, label!!, created!!.toInstant(), used?.toInstant())
    }
    pageField(p, metadata, p.USER_ID.eq(key.userId.value), id?.let { p.MANAGEMENT_ID.gt(it) } ?: DSL.trueCondition(), id != null,
      listOf(p.MANAGEMENT_ID.asc()), key.page, scope, { it }) { listOf(it.id.toString()) }
  } })
  override fun sessions(keys: List<SessionPageKey>) = boundedBatch(context, keys.map { key -> {
    val s = USER_SESSIONS
    val scope = ConnectionCursors.scope("sessions", "id-asc", key.userId.value.toString(), key.currentSessionId.value.toString())
    val id = key.page.after?.let { uuidCursorId(it, scope) }
    val metadata = DSL.row(s.ID, s.CREATED_AT, s.EXPIRES_AT).mapping { sessionId, created, expires ->
      SessionSummary(UserSessionId(sessionId!!), created!!.toInstant(), expires!!.toInstant(), sessionId == key.currentSessionId.value)
    }
    val condition = s.USER_ID.eq(key.userId.value).and(s.REVOKED_AT.isNull).and(s.CREATED_AT.le(key.now.atOffset(UTC))).and(s.EXPIRES_AT.gt(key.now.atOffset(UTC)))
    pageField(s, metadata, condition, id?.let { s.ID.gt(it) } ?: DSL.trueCondition(), id != null,
      listOf(s.ID.asc()), key.page, scope, { it }) { listOf(it.id.value.toString()) }
  } })
  override fun credentialId(userId: UserId, managementId: UUID): CredentialId? = context.select(PASSKEY_MANAGEMENT_REFERENCES.CREDENTIAL_ID)
    .from(PASSKEY_MANAGEMENT_REFERENCES).where(PASSKEY_MANAGEMENT_REFERENCES.USER_ID.eq(userId.value)).and(PASSKEY_MANAGEMENT_REFERENCES.MANAGEMENT_ID.eq(managementId))
    .fetchOne()?.value1()?.let(::CredentialId)
}
