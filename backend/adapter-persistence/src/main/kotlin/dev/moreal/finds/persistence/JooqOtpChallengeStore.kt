package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds.persistence.jooq.generated.tables.references.OTP_CHALLENGES as O
import dev.moreal.finds.persistence.jooq.generated.tables.references.RECOVERY_CODES as R
import org.jooq.DSLContext
import org.jooq.Field

internal class JooqOtpChallengeStore(private val db: DSLContext, private val access: IdentityAccess) : OtpChallengeRepository {
  private val revisions = mutableMapOf<Pair<EmailAddress, VerificationPurpose>, Long>()
  override fun find(email: EmailAddress, purpose: VerificationPurpose): OtpAccountState? = access.access {
    val row = db.selectFrom(O).where(O.NORMALIZED_EMAIL.eq(email.normalized)).and(O.PURPOSE.eq(purpose.name)).fetchOne()
      ?: return@access null
    revisions[email to purpose] = checkNotNull(row.revision)
    OtpAccountState(email, purpose, checkNotNull(row.consecutiveFailures),
      row.deliveryId?.let { OtpChallenge(DeliveryRequestId(it),
        KeyedIdentityHash(checkNotNull(row.pepperVersion), checkNotNull(row.otpHash)),
        checkNotNull(row.expiresAt).toInstant(), row.consumedAt?.toInstant()) }, row.lockedUntil?.toInstant(), row.lastIssuedAt?.toInstant())
  }
  override fun save(state: OtpAccountState) = access.access {
    access.requireLock(state.email)
    val key = state.email to state.purpose
    if (key !in revisions) find(state.email, state.purpose)
    val revision = revisions[key]
    val c = state.challenge
    val values: Map<Field<*>, Any?> = mapOf(O.CONSECUTIVE_FAILURES to state.consecutiveFailures,
      O.LOCKED_UNTIL to state.lockedUntil?.sqlTime(), O.LAST_ISSUED_AT to state.lastIssuedAt?.sqlTime(),
      O.DELIVERY_ID to c?.deliveryId?.value, O.OTP_HASH to c?.hash?.bytes, O.PEPPER_VERSION to c?.hash?.pepperVersion,
      O.EXPIRES_AT to c?.expiresAt?.sqlTime(), O.CONSUMED_AT to c?.consumedAt?.sqlTime())
    val changed = if (revision == null) db.insertInto(O).set(values).set(O.NORMALIZED_EMAIL, state.email.normalized)
      .set(O.PURPOSE, state.purpose.name).onConflict(O.NORMALIZED_EMAIL, O.PURPOSE).doNothing().execute()
    else db.update(O).set(values).set(O.REVISION, O.REVISION.plus(1)).where(O.NORMALIZED_EMAIL.eq(state.email.normalized))
      .and(O.PURPOSE.eq(state.purpose.name)).and(O.REVISION.eq(revision)).execute()
    check(changed == 1) { "OTP state changed concurrently" }
    revisions[key] = if (revision == null) 0 else revision + 1
    Unit
  }
}

internal class JooqRecoveryCodeRepository(private val db: DSLContext, private val access: IdentityAccess) : RecoveryCodeRepository {
  private val revisions = mutableMapOf<UserId, Long>()
  override fun findByUserId(userId: UserId): RecoveryCodeHash? = access.access {
    val row = db.selectFrom(R).where(R.USER_ID.eq(userId.value)).fetchOne() ?: return@access null
    revisions[userId] = checkNotNull(row.revision)
    RecoveryCodeHash(userId, KeyedIdentityHash(checkNotNull(row.pepperVersion), checkNotNull(row.codeHash)), checkNotNull(row.createdAt).toInstant())
  }
  override fun save(code: RecoveryCodeHash) = access.access {
    access.requireUserLock(code.userId)
    if (code.userId !in revisions) findByUserId(code.userId)
    val revision = revisions[code.userId]
    val values: Map<Field<*>, Any?> = mapOf(R.CODE_HASH to code.hash.bytes, R.PEPPER_VERSION to code.hash.pepperVersion, R.CREATED_AT to code.createdAt.sqlTime())
    val count = if (revision == null) db.insertInto(R).set(values).set(R.USER_ID, code.userId.value).onConflict(R.USER_ID).doNothing().execute()
    else db.update(R).set(values).set(R.REVISION, R.REVISION.plus(1)).where(R.USER_ID.eq(code.userId.value)).and(R.REVISION.eq(revision)).execute()
    check(count == 1) { "Recovery code changed concurrently" }
    revisions[code.userId] = if (revision == null) 0 else revision + 1
    Unit
  }
}
