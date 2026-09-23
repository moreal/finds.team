package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.User
import org.springframework.security.web.webauthn.api.*
import org.springframework.security.web.webauthn.management.*

/**
 * Ceremony-local snapshots implement Spring's contracts without holding a transaction during
 * verification. Spring's save is staged only: application commands own the atomic durable write.
 * No unknown Spring username may provision an account or create an alternative in-memory identity.
 */
internal class WebAuthnPersistenceAdapters(user: User, handle: ByteArray, credentials: List<PasskeyCredential>) {
  private val entity = ImmutablePublicKeyCredentialUserEntity.builder().id(Bytes(handle))
    .name(user.id.value.toString()).displayName(user.email.value).build()
  private val records = credentials.map { credential ->
    val material = credential.material
    ImmutableCredentialRecord.builder().credentialId(Bytes.fromBase64(material.id.value))
      .credentialType(PublicKeyCredentialType.PUBLIC_KEY).userEntityUserId(entity.id)
      .publicKey(ImmutablePublicKeyCose(material.publicKeyCose)).signatureCount(material.signatureCount)
      .uvInitialized(true).backupEligible(material.backupEligible).backupState(material.backedUp)
      .transports(material.transports.map(AuthenticatorTransport::valueOf).toSet())
      .created(credential.createdAt).also { builder -> credential.lastUsedAt?.let(builder::lastUsed) }.label(credential.label).build()
  }
  val users = object : PublicKeyCredentialUserEntityRepository {
    override fun findById(id: Bytes): PublicKeyCredentialUserEntity? = entity.takeIf { it.id == id }
    override fun findByUsername(username: String): PublicKeyCredentialUserEntity? = entity.takeIf { it.name == username }
    override fun save(user: PublicKeyCredentialUserEntity) { throw CeremonyRejected() }
    override fun delete(id: Bytes) { throw CeremonyRejected() }
  }
  val credentials = object : UserCredentialRepository {
    override fun findByCredentialId(id: Bytes): CredentialRecord? = records.find { it.credentialId == id }
    override fun findByUserId(id: Bytes): List<CredentialRecord> = if (id == entity.id) records else emptyList()
    override fun save(record: CredentialRecord) { if (record.userEntityUserId != entity.id) throw CeremonyRejected() }
    override fun delete(id: Bytes) { throw CeremonyRejected() }
  }
}

internal fun CredentialRecord.material() = PasskeyCredentialMaterial(
  dev.moreal.finds.domain.identity.CredentialId(credentialId.toBase64UrlString()), publicKey.bytes,
  signatureCount, transports.map { it.value }.toSet(), isBackupEligible, isBackupState,
)
internal class CeremonyRejected : RuntimeException()
internal class CeremonyConflict : RuntimeException()
