package dev.moreal.finds.domain.identity

import java.util.UUID
import kotlin.test.*

class CredentialPolicyTest {
  @Test
  fun `only active accounts may receive normal sessions`() {
    listOf(UserStatus.PENDING_PASSKEY to IdentityRejection.NOT_ACTIVE,
      UserStatus.SUSPENDED to IdentityRejection.SUSPENDED, UserStatus.ACTIVE to null).forEach { (status, rejection) ->
      val user = User(UserId(UUID(0, 1)), EmailAddress("a@example.test"), status,
        credentials = if (status == UserStatus.PENDING_PASSKEY) emptySet() else setOf(CredentialId("one")))
      assertEquals(rejection?.let(CredentialDecision::Denied) ?: CredentialDecision.Allowed,
        CredentialPolicy.authenticate(user))
    }
  }
}
