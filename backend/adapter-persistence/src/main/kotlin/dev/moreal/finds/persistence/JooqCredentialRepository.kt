package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds.persistence.jooq.generated.tables.references.PASSKEY_CREDENTIALS as C
import dev.moreal.finds.persistence.jooq.generated.tables.records.PasskeyCredentialsRecord
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

internal class JooqCredentialRepository(private val db: DSLContext, private val access: IdentityAccess) : PasskeyCredentialRepository {
  override fun findById(id: CredentialId): PasskeyCredential? = access.access {
    db.selectFrom(C).where(C.CREDENTIAL_ID.eq(id.value)).fetchOne()?.toCredential()
  }
  override fun findByUserId(userId: UserId): List<PasskeyCredential> = access.access {
    db.selectFrom(C).where(C.USER_ID.eq(userId.value)).orderBy(C.CREATED_AT, C.CREDENTIAL_ID).fetch().map { it.toCredential() }
  }
  override fun insert(credential: PasskeyCredential): Boolean = access.access {
    access.requireUserLock(credential.userId)
    val material = credential.material
    db.insertInto(C).set(C.CREDENTIAL_ID, material.id.value).set(C.USER_ID, credential.userId.value)
      .set(C.PUBLIC_KEY_COSE, material.publicKeyCose).set(C.SIGNATURE_COUNT, material.signatureCount)
      .set(C.TRANSPORTS, material.transports.sorted().toTypedArray<String?>()).set(C.BACKUP_ELIGIBLE, material.backupEligible)
      .set(C.BACKED_UP, material.backedUp).set(C.LABEL, credential.label).set(C.CREATED_AT, credential.createdAt.sqlTime())
      .set(C.LAST_USED_AT, credential.lastUsedAt?.sqlTime()).onConflict(C.CREDENTIAL_ID).doNothing().execute() == 1
  }
  override fun remove(id: CredentialId) = access.access {
    findById(id)?.let { access.requireUserLock(it.userId); db.deleteFrom(C).where(C.CREDENTIAL_ID.eq(id.value)).execute() }
    Unit
  }
  override fun rename(id: CredentialId, label: String) = access.access {
    val credential = checkNotNull(findById(id)) { "Credential not found" }
    access.requireUserLock(credential.userId)
    db.update(C).set(C.LABEL, label).where(C.CREDENTIAL_ID.eq(id.value)).execute()
    Unit
  }
  override fun updateUsage(id: CredentialId, expectedSignatureCount: Long, signatureCount: Long, backedUp: Boolean, usedAt: Instant): Boolean = access.access {
    val credential = findById(id) ?: return@access false
    access.requireUserLock(credential.userId)
    if (signatureCount < 0 || (signatureCount <= expectedSignatureCount && (signatureCount != 0L || expectedSignatureCount != 0L))) return@access false
    db.update(C).set(C.SIGNATURE_COUNT, signatureCount).set(C.BACKED_UP, backedUp).set(C.LAST_USED_AT, usedAt.sqlTime())
      .where(C.CREDENTIAL_ID.eq(id.value)).and(C.SIGNATURE_COUNT.eq(expectedSignatureCount))
      .and(if (backedUp) C.BACKUP_ELIGIBLE.isTrue else DSL.trueCondition())
      .and(DSL.coalesce(C.LAST_USED_AT, C.CREATED_AT).le(usedAt.sqlTime())).execute() == 1
  }
  private fun PasskeyCredentialsRecord.toCredential() = PasskeyCredential(UserId(checkNotNull(userId)),
    PasskeyCredentialMaterial(CredentialId(checkNotNull(credentialId)), checkNotNull(publicKeyCose), checkNotNull(signatureCount),
      checkNotNull(transports).map { checkNotNull(it) }.toSet(), checkNotNull(backupEligible), checkNotNull(backedUp)),
    checkNotNull(createdAt).toInstant(), checkNotNull(label), lastUsedAt?.toInstant())
}
