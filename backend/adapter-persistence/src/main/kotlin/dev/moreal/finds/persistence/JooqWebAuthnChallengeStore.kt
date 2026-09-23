package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.UserId
import dev.moreal.finds.persistence.jooq.generated.tables.references.WEBAUTHN_CHALLENGES as C
import dev.moreal.finds.persistence.jooq.generated.tables.references.RESTRICTED_SESSIONS as S
import java.time.Instant
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.impl.DSL

internal class JooqWebAuthnChallengeStore(private val db: DSLContext, private val access: IdentityAccess) : WebAuthnChallengeRepository {
  override fun purgeExpired(now: Instant, limit: Int): Int = access.access {
    require(limit in 1..1000) { "Invalid identity cleanup limit" }
    // Registration rows cascade only with their restricted-session tombstone, whose replay
    // deadline can be later. Login challenges retain 24h of diagnostics after expiry.
    val expired = db.select(C.ID).from(C).where(C.RESTRICTED_SESSION_ID.isNull)
      .and(C.EXPIRES_AT.le(now.minusSeconds(RestrictedSession.REPLAY_RETENTION_SECONDS).sqlTime()))
      .orderBy(C.EXPIRES_AT, C.ID).limit(limit).forUpdate().skipLocked()
    db.deleteFrom(C).where(C.ID.`in`(expired)).execute()
  }
  override fun findById(id: UUID): WebAuthnChallenge? = access.access {
    db.selectFrom(C).where(C.ID.eq(id)).fetchOne()?.let {
      WebAuthnChallenge(id, WebAuthnChallengePurpose.valueOf(checkNotNull(it.purpose)), checkNotNull(it.rpId),
        KeyedIdentityHash(checkNotNull(it.pepperVersion), checkNotNull(it.challengeHash)),
        KeyedIdentityHash(checkNotNull(it.sessionPepperVersion), checkNotNull(it.sessionBindingHash)),
        it.userId?.let(::UserId), it.restrictedSessionId?.let(::RestrictedSessionId),
        checkNotNull(it.createdAt).toInstant(), checkNotNull(it.expiresAt).toInstant(), it.consumedAt?.toInstant())
    }
  }
  override fun save(challenge: WebAuthnChallenge) = access.access {
    challenge.userId?.let(access::requireUserLock)
    check(challenge.consumedAt == null) { "New ceremony must be unconsumed" }
    challenge.restrictedSessionId?.let { sessionId ->
      val session = JooqRestrictedSessionRepository(db, access).findById(sessionId)
      check(session != null && session.userId == challenge.userId && session.isUsable(challenge.createdAt)) { "Invalid ceremony session" }
    }
    db.insertInto(C).set(C.ID, challenge.id).set(C.PURPOSE, challenge.purpose.name).set(C.RP_ID, challenge.rpId)
      .set(C.CHALLENGE_HASH, challenge.hash.bytes).set(C.PEPPER_VERSION, challenge.hash.pepperVersion)
      .set(C.SESSION_BINDING_HASH, challenge.sessionBinding.bytes).set(C.SESSION_PEPPER_VERSION, challenge.sessionBinding.pepperVersion)
      .set(C.USER_ID, challenge.userId?.value).set(C.RESTRICTED_SESSION_ID, challenge.restrictedSessionId?.value)
      .set(C.CREATED_AT, challenge.createdAt.sqlTime()).set(C.EXPIRES_AT, challenge.expiresAt.sqlTime()).execute()
    Unit
  }
  override fun consume(expected: WebAuthnChallenge, now: Instant): Boolean = access.access {
    // Use the stored owner for locking; an altered expected owner must simply fail comparison.
    val actual = findById(expected.id) ?: return@access false
    actual.userId?.let(access::requireUserLock)
    db.update(C).set(C.CONSUMED_AT, now.sqlTime()).where(C.ID.eq(expected.id)).and(C.CONSUMED_AT.isNull)
      .and(C.PURPOSE.eq(expected.purpose.name)).and(C.RP_ID.eq(expected.rpId))
      .and(C.CHALLENGE_HASH.eq(expected.hash.bytes)).and(C.PEPPER_VERSION.eq(expected.hash.pepperVersion))
      .and(C.SESSION_BINDING_HASH.eq(expected.sessionBinding.bytes)).and(C.SESSION_PEPPER_VERSION.eq(expected.sessionBinding.pepperVersion))
      .and(C.USER_ID.isNotDistinctFrom(expected.userId?.value)).and(C.RESTRICTED_SESSION_ID.isNotDistinctFrom(expected.restrictedSessionId?.value))
      .and(C.CREATED_AT.eq(expected.createdAt.sqlTime())).and(C.EXPIRES_AT.eq(expected.expiresAt.sqlTime()))
      .and(C.CREATED_AT.le(now.sqlTime())).and(C.EXPIRES_AT.gt(now.sqlTime()))
      .and(C.RESTRICTED_SESSION_ID.isNull.or(DSL.exists(DSL.selectOne().from(S).where(S.ID.eq(C.RESTRICTED_SESSION_ID))
        .and(S.USER_ID.eq(C.USER_ID)).and(S.INVALIDATED_AT.isNull).and(S.CREATED_AT.le(now.sqlTime())).and(S.EXPIRES_AT.gt(now.sqlTime())))))
      .execute() == 1
  }
}
