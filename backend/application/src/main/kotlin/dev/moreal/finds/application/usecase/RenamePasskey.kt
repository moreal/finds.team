package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.CredentialId
import dev.moreal.finds.domain.identity.UserId

class RenamePasskey(private val transactions: TransactionPort, private val clock: ClockPort, private val random: SecureRandomPort) {
  fun execute(principal: SessionPrincipal, credentialId: CredentialId, label: String, metadata: CommandMetadata): SecurityChangeResult = transactions.execute { tx ->
    val id = UserId(principal.actor.userId)
    val user = tx.lockUsers(setOf(id))[id]
    val now = clock.now()
    if (!tx.authorize(principal, user, now, recent = true)) return@execute SecurityChangeResult.Forbidden
    val normalizedLabel = validPasskeyLabel(label) ?: return@execute SecurityChangeResult.InvalidLabel
    tx.securityCommand(id, "passkey.rename", metadata, now, mapOf("credential" to credentialId.value, "label" to normalizedLabel)) {
      val credential = tx.credentials.findById(credentialId)
      when {
        credential?.userId != id -> SecurityChangeResult.NotFound
        credential.label == normalizedLabel -> SecurityChangeResult.Unchanged
        else -> {
          tx.credentials.rename(credentialId, normalizedLabel)
          tx.auditSecurity(random, now, principal.actor, AuditAction.PASSKEY_RENAMED, id, metadata)
          SecurityChangeResult.Changed
        }
      }
    }
  }
}

internal fun validPasskeyLabel(label: String): String? = label.trim().takeIf {
  it.length in 1..80 && label.none(Char::isISOControl)
}
