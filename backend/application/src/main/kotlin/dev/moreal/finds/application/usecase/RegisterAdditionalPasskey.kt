package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import java.util.Base64

sealed interface AdditionalPasskeySessionResult {
  data class Ready(val session: RestrictedSession) : AdditionalPasskeySessionResult
  data object Forbidden : AdditionalPasskeySessionResult
}
class BeginAdditionalPasskeyRegistration(private val transactions: TransactionPort, private val clock: ClockPort, private val random: SecureRandomPort) {
  fun execute(principal: SessionPrincipal): AdditionalPasskeySessionResult = transactions.execute { tx ->
    val id = UserId(principal.actor.userId)
    val user = tx.lockUsers(setOf(id))[id]
    val now = clock.now()
    if (!tx.authorize(principal, user, now, recent = true)) return@execute AdditionalPasskeySessionResult.Forbidden
    tx.restrictedSessions.invalidateForUser(id, RestrictedSessionScope.ADDITIONAL_PASSKEY, now)
    val session = RestrictedSession(RestrictedSessionId(random.uuid()), id, RestrictedSessionScope.ADDITIONAL_PASSKEY, now, now.plusSeconds(600))
    tx.restrictedSessions.save(session)
    AdditionalPasskeySessionResult.Ready(session)
  }
}
data class RegisterAdditionalPasskeyCommand(val principal: SessionPrincipal, val sessionId: RestrictedSessionId,
  val registration: VerifiedPasskeyRegistration, val label: String, val metadata: CommandMetadata) {
  init { require(metadata.idempotencyKey != null) { "Passkey registration requires an idempotency key" } }
  override fun toString(): String = "RegisterAdditionalPasskeyCommand(<redacted>)"
}

class RegisterAdditionalPasskey(private val transactions: TransactionPort, private val clock: ClockPort, private val random: SecureRandomPort) {
  fun execute(command: RegisterAdditionalPasskeyCommand): SecurityChangeResult = transactions.execute { tx ->
    val id = UserId(command.principal.actor.userId)
    val user = tx.lockUsers(setOf(id))[id]
    val now = clock.now()
    if (!tx.authorize(command.principal, user, now, recent = true)) return@execute SecurityChangeResult.Forbidden
    val label = validPasskeyLabel(command.label) ?: return@execute SecurityChangeResult.InvalidLabel
    val proof = command.registration
    if (proof.userId != id || proof.sessionId != command.sessionId) return@execute SecurityChangeResult.Forbidden
    val session = tx.restrictedSessions.findById(command.sessionId) ?: return@execute SecurityChangeResult.Forbidden
    if (session.userId != id || session.scope != RestrictedSessionScope.ADDITIONAL_PASSKEY) return@execute SecurityChangeResult.Forbidden
    val material = proof.credential
    tx.securityCommand(id, "passkey.register", command.metadata, now, mapOf("label" to label, "credential" to mapOf(
      "id" to material.id.value, "publicKeyCose" to Base64.getEncoder().encodeToString(material.publicKeyCose),
      "signatureCount" to material.signatureCount, "transports" to material.transports.sorted(),
      "backupEligible" to material.backupEligible, "backedUp" to material.backedUp))) {
      if (!session.isUsable(now)) return@securityCommand SecurityChangeResult.Forbidden
      val updated = (checkNotNull(user).registerCredential(material.id) as? UserChange.Updated)?.user
        ?: return@securityCommand SecurityChangeResult.CredentialAlreadyExists
      if (!tx.credentials.insert(PasskeyCredential(id, material, now, label))) return@securityCommand SecurityChangeResult.CredentialAlreadyExists
      tx.users.save(updated)
      tx.restrictedSessions.invalidateForUser(id, RestrictedSessionScope.ADDITIONAL_PASSKEY, now)
      tx.auditSecurity(random, now, command.principal.actor, AuditAction.PASSKEY_REGISTERED, id, command.metadata)
      SecurityChangeResult.Changed
    }
  }
}
