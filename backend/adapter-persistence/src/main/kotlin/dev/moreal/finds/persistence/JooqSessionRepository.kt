package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.UserId
import dev.moreal.finds.persistence.jooq.generated.tables.references.USER_SESSIONS as S
import dev.moreal.finds.persistence.jooq.generated.tables.references.RESTRICTED_SESSIONS as R
import dev.moreal.finds.persistence.jooq.generated.tables.records.UserSessionsRecord
import java.time.Instant
import org.jooq.DSLContext

internal class JooqSessionRepository(private val db: DSLContext, private val access: IdentityAccess) : UserSessionRepository {
  override fun findById(id: UserSessionId): UserSession? = access.access {
    db.selectFrom(S).where(S.ID.eq(id.value)).fetchOne()?.toSession()
  }
  override fun findByUserId(userId: UserId): List<UserSession> = access.access {
    db.selectFrom(S).where(S.USER_ID.eq(userId.value)).orderBy(S.CREATED_AT, S.ID).fetch().map { it.toSession() }
  }
  override fun save(session: UserSession) = access.access {
    access.requireUserLock(session.userId)
    // An existing management reference can never be rebound, extended or resurrected.
    val old = findById(session.id)
    check(old == null || old == session) { "Session binding is immutable" }
    if (old == null) db.insertInto(S).set(S.ID, session.id.value).set(S.USER_ID, session.userId.value)
      .set(S.CREATED_AT, session.createdAt.sqlTime()).set(S.EXPIRES_AT, session.expiresAt.sqlTime())
      .set(S.AUTHENTICATED_AT, session.authenticatedAt.sqlTime()).set(S.REVOKED_AT, session.revokedAt?.sqlTime()).execute()
    Unit
  }
  override fun revoke(id: UserSessionId, now: Instant) = access.access {
    findById(id)?.let {
      access.requireUserLock(it.userId)
      db.update(S).set(S.REVOKED_AT, now.sqlTime()).where(S.ID.eq(id.value)).and(S.REVOKED_AT.isNull).execute()
    }
    Unit
  }
  override fun revokeForUser(userId: UserId, now: Instant, except: UserSessionId?) = access.access {
    access.requireUserLock(userId)
    db.update(S).set(S.REVOKED_AT, now.sqlTime()).where(S.USER_ID.eq(userId.value)).and(S.REVOKED_AT.isNull)
      .and(S.ID.isDistinctFrom(except?.value)).execute()
    Unit
  }
  private fun UserSessionsRecord.toSession() = UserSession(UserSessionId(checkNotNull(id)), UserId(checkNotNull(userId)),
    checkNotNull(createdAt).toInstant(), checkNotNull(expiresAt).toInstant(), checkNotNull(authenticatedAt).toInstant(), revokedAt?.toInstant())
}

internal class JooqRestrictedSessionRepository(private val db: DSLContext, private val access: IdentityAccess) : RestrictedSessionRepository {
  override fun findById(id: RestrictedSessionId): RestrictedSession? = access.access {
    db.selectFrom(R).where(R.ID.eq(id.value)).fetchOne()?.let {
      RestrictedSession(id, UserId(checkNotNull(it.userId)), RestrictedSessionScope.valueOf(checkNotNull(it.scope)),
        checkNotNull(it.createdAt).toInstant(), checkNotNull(it.expiresAt).toInstant(), it.invalidatedAt?.toInstant())
    }
  }
  override fun save(session: RestrictedSession) = access.access {
    access.requireUserLock(session.userId)
    val old = findById(session.id)
    check(old == null || old == session) { "Restricted session binding is immutable" }
    if (old == null) db.insertInto(R).set(R.ID, session.id.value).set(R.USER_ID, session.userId.value).set(R.SCOPE, session.scope.name)
      .set(R.CREATED_AT, session.createdAt.sqlTime()).set(R.EXPIRES_AT, session.expiresAt.sqlTime()).set(R.INVALIDATED_AT, session.invalidatedAt?.sqlTime()).execute()
    Unit
  }
  override fun invalidateForUser(userId: UserId, scope: RestrictedSessionScope, now: Instant) = access.access {
    access.requireUserLock(userId)
    db.update(R).set(R.INVALIDATED_AT, now.sqlTime()).where(R.USER_ID.eq(userId.value)).and(R.SCOPE.eq(scope.name)).and(R.INVALIDATED_AT.isNull).execute()
    Unit
  }
  override fun purgeExpired(now: Instant, limit: Int): Int = access.access {
    require(limit in 1..1000) { "Invalid identity cleanup limit" }
    // Indexable cutoff, short batches and SKIP LOCKED keep maintenance out of live ceremonies.
    val expired = db.select(R.ID).from(R).where(R.EXPIRES_AT.le(now.minusSeconds(RestrictedSession.REPLAY_RETENTION_SECONDS).sqlTime()))
      .orderBy(R.EXPIRES_AT, R.ID).limit(limit).forUpdate().skipLocked()
    db.deleteFrom(R).where(R.ID.`in`(expired)).execute()
  }
}
