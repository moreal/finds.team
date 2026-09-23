package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.PasskeyRegistrationReceiptPort
import dev.moreal.finds.domain.identity.CredentialId
import dev.moreal.finds.domain.identity.UserId
import dev.moreal.finds.persistence.jooq.generated.tables.references.PASSKEY_CREDENTIALS as R
import org.jooq.DSLContext

class JooqPasskeyRegistrationReceipt(private val context: DSLContext) : PasskeyRegistrationReceiptPort {
  // Select only the live registration's management metadata, never cryptographic material.
  override fun managementId(userId: UserId, credentialId: CredentialId) = context.select(R.MANAGEMENT_ID)
    .from(R).where(R.USER_ID.eq(userId.value)).and(R.CREDENTIAL_ID.eq(credentialId.value))
    .fetchOne()?.value1()
}
