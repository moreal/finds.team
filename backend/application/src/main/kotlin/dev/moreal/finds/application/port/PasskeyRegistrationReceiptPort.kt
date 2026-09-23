package dev.moreal.finds.application.port

import dev.moreal.finds.domain.identity.CredentialId
import dev.moreal.finds.domain.identity.UserId
import java.util.UUID

/** Resolve only an owning user's opaque management identity after verified registration. */
fun interface PasskeyRegistrationReceiptPort {
  fun managementId(userId: UserId, credentialId: CredentialId): UUID?
}
