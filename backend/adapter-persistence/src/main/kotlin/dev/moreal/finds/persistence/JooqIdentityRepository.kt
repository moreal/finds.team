package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.UserRepository
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds.persistence.jooq.generated.tables.references.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL

/** One lock set shared by every identity store in exactly one transaction. */
internal class IdentityAccess(private val db: DSLContext, private val scope: TransactionScope) {
  private val locked = mutableSetOf<String>()
  fun <T> access(block: () -> T): T = scope.access {
    // Driver diagnostics can contain bind values. Never propagate them into public/logging paths.
    try { block() } catch (failure: DataAccessException) {
      throw IllegalStateException("Identity persistence failed (${failure.sqlState()})")
    }
  }
  fun lock(email: EmailAddress) {
    val key = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(
      ("finds.identity:" + email.normalized).toByteArray(Charsets.UTF_8))).long
    db.fetch("SELECT pg_advisory_xact_lock(?)", key)
    locked += email.normalized
  }
  fun requireLock(email: EmailAddress) = check(email.normalized in locked) { "Identity mutation requires the account lock" }
  fun requireUserLock(id: UserId) {
    val email = db.select(USERS.NORMALIZED_EMAIL).from(USERS).where(USERS.ID.eq(id.value)).fetchOne(USERS.NORMALIZED_EMAIL)
    check(email != null && email in locked) { "Identity mutation requires the account lock" }
  }
}

internal class JooqIdentityRepository(private val db: DSLContext, private val access: IdentityAccess) : UserRepository {
  override fun lockByEmail(email: EmailAddress): User? = access.access {
    access.lock(email)
    select().where(USERS.NORMALIZED_EMAIL.eq(email.normalized)).fetchOne()?.toUser()
  }
  override fun findById(id: UserId): User? = access.access {
    select().where(USERS.ID.eq(id.value)).fetchOne()?.toUser()
  }
  override fun findUserHandle(id: UserId): ByteArray? = access.access {
    db.select(USERS.USER_HANDLE).from(USERS).where(USERS.ID.eq(id.value)).fetchOne(USERS.USER_HANDLE)?.copyOf()
  }
  override fun save(user: User) = access.access {
    access.requireLock(user.email)
    val count = db.insertInto(USERS).set(USERS.ID, user.id.value).set(USERS.EMAIL, user.email.value)
      .set(USERS.NORMALIZED_EMAIL, user.email.normalized).set(USERS.STATUS, user.status.name)
      .onConflict(USERS.ID).doUpdate().set(USERS.STATUS, user.status.name)
      .where(USERS.NORMALIZED_EMAIL.eq(user.email.normalized)).execute()
    check(count == 1) { "User email is immutable" }
    val actual = db.select(PASSKEY_CREDENTIALS.CREDENTIAL_ID).from(PASSKEY_CREDENTIALS)
      .where(PASSKEY_CREDENTIALS.USER_ID.eq(user.id.value)).fetch(PASSKEY_CREDENTIALS.CREDENTIAL_ID)
      .map { CredentialId(checkNotNull(it)) }.toSet()
    check(actual == user.credentials) { "Credential aggregate does not match stored material" }
    db.deleteFrom(USER_ROLES).where(USER_ROLES.USER_ID.eq(user.id.value)).execute()
    user.roles.forEach { db.insertInto(USER_ROLES).set(USER_ROLES.USER_ID, user.id.value).set(USER_ROLES.ROLE, it.name).execute() }
    Unit
  }
  private val roles = DSL.array(DSL.select(USER_ROLES.ROLE).from(USER_ROLES).where(USER_ROLES.USER_ID.eq(USERS.ID)))
  private val credentials = DSL.array(DSL.select(PASSKEY_CREDENTIALS.CREDENTIAL_ID).from(PASSKEY_CREDENTIALS).where(PASSKEY_CREDENTIALS.USER_ID.eq(USERS.ID)))
  // One READ COMMITTED statement prevents a mixed user/credential snapshot during recovery.
  private fun select() = db.select(USERS.ID, USERS.EMAIL, USERS.STATUS, roles, credentials).from(USERS)
  private fun Record.toUser() = User(UserId(checkNotNull(get(USERS.ID))), EmailAddress(checkNotNull(get(USERS.EMAIL))),
    UserStatus.valueOf(checkNotNull(get(USERS.STATUS))),
    checkNotNull(get(roles)).map { UserRole.valueOf(checkNotNull(it)) }.toSet(),
    checkNotNull(get(credentials)).map { CredentialId(checkNotNull(it)) }.toSet())
}

internal fun Instant.sqlTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
