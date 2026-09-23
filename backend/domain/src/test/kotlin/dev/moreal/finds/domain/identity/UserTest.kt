package dev.moreal.finds.domain.identity

import java.util.UUID
import kotlin.test.*

class UserTest {
  private val id = UserId(UUID.fromString("7d638c52-35ab-48dc-a6cd-3456d73d5e17"))
  private val email = EmailAddress("Private@example.test")
  private val first = CredentialId("credential-1")
  private val second = CredentialId("credential-2")

  @Test
  fun `first credential activates pending account while preserving original`() {
    val pending = User(id, email)
    assertEquals(UserStatus.PENDING_PASSKEY, pending.status)
    assertEquals(setOf(UserRole.USER), pending.roles)
    val active = assertIs<UserChange.Updated>(pending.registerCredential(first)).user
    assertEquals(UserStatus.ACTIVE, active.status)
    assertEquals(setOf(first), active.credentials)
    assertEquals(emptySet(), pending.credentials)
    assertEquals(UserStatus.PENDING_PASSKEY, pending.status)
  }

  @Test
  fun `invalid restored account states cannot be constructed`() {
    listOf(UserStatus.ACTIVE to emptySet(), UserStatus.PENDING_PASSKEY to setOf(first)).forEach { (status, ids) ->
      assertFailsWith<IllegalArgumentException> { User(id, email, status, credentials = ids) }
    }
    listOf(emptySet(), setOf(UserRole.ADMIN)).forEach { roles ->
      assertFailsWith<IllegalArgumentException> { User(id, email, roles = roles) }
    }
  }

  @Test
  fun `role commands are idempotent and base user role cannot be revoked`() {
    val active = User(id, email, UserStatus.ACTIVE, credentials = setOf(first))
    assertIs<UserChange.Unchanged>(active.grantRole(UserRole.USER))
    assertIs<UserChange.Unchanged>(active.revokeRole(UserRole.ADMIN))
    val admin = assertIs<UserChange.Updated>(active.grantRole(UserRole.ADMIN)).user
    assertEquals(setOf(UserRole.USER, UserRole.ADMIN), admin.roles)
    assertIs<UserChange.Unchanged>(admin.grantRole(UserRole.ADMIN))
    assertEquals(setOf(UserRole.USER), assertIs<UserChange.Updated>(admin.revokeRole(UserRole.ADMIN)).user.roles)
    listOf(active, admin).forEach { user ->
      assertEquals(IdentityRejection.REQUIRED_USER_ROLE, assertIs<UserChange.Rejected>(user.revokeRole(UserRole.USER)).reason)
    }
    assertEquals(setOf(UserRole.USER), active.roles)
  }

  @Test
  fun `suspended account rejects security changes and session issuance`() {
    val suspended = User(id, email, UserStatus.SUSPENDED, credentials = setOf(first, second))
    listOf(suspended.registerCredential(CredentialId("new")), suspended.removeCredential(first),
      suspended.completeRecovery(CredentialId("new")), suspended.grantRole(UserRole.ADMIN),
      suspended.revokeRole(UserRole.ADMIN)).forEach { result ->
      assertEquals(IdentityRejection.SUSPENDED, assertIs<UserChange.Rejected>(result).reason)
    }
    assertEquals(CredentialDecision.Denied(IdentityRejection.SUSPENDED), CredentialPolicy.authenticate(suspended))
  }

  @Test
  fun `credential management rejects duplicates missing ids and removal of last credential`() {
    val active = User(id, email, UserStatus.ACTIVE, credentials = setOf(first))
    listOf(active.registerCredential(first) to IdentityRejection.CREDENTIAL_ALREADY_EXISTS,
      active.removeCredential(second) to IdentityRejection.CREDENTIAL_NOT_FOUND,
      active.removeCredential(first) to IdentityRejection.LAST_CREDENTIAL).forEach { (result, reason) ->
      assertEquals(reason, assertIs<UserChange.Rejected>(result).reason)
    }
    val multiple = assertIs<UserChange.Updated>(active.registerCredential(second)).user
    assertEquals(setOf(second), assertIs<UserChange.Updated>(multiple.removeCredential(first)).user.credentials)
    assertEquals(setOf(first, second), multiple.credentials)
  }

  @Test
  fun `recovery replaces all credentials without changing active status`() {
    val active = User(id, email, UserStatus.ACTIVE, credentials = setOf(first, second))
    assertEquals(IdentityRejection.CREDENTIAL_ALREADY_EXISTS,
      assertIs<UserChange.Rejected>(active.completeRecovery(first)).reason)
    val recovered = assertIs<UserChange.Updated>(active.completeRecovery(CredentialId("new"))).user
    assertEquals(setOf(CredentialId("new")), recovered.credentials)
    assertEquals(UserStatus.ACTIVE, recovered.status)
    assertEquals(setOf(first, second), active.credentials)
    assertEquals(IdentityRejection.NOT_ACTIVE,
      assertIs<UserChange.Rejected>(User(id, email).completeRecovery(first)).reason)
  }

  @Test
  fun `aggregate snapshots collections and prevents mutation through exposed sets`() {
    val roles = mutableSetOf(UserRole.USER)
    val credentials = mutableSetOf(first)
    val user = User(id, email, UserStatus.ACTIVE, roles, credentials)
    roles.add(UserRole.ADMIN)
    credentials.add(second)
    assertEquals(setOf(UserRole.USER), user.roles)
    assertEquals(setOf(first), user.credentials)
    assertFailsWith<UnsupportedOperationException> { (user.roles as MutableSet<UserRole>).add(UserRole.ADMIN) }
    assertFailsWith<UnsupportedOperationException> { (user.credentials as MutableSet<CredentialId>).clear() }
    assertFalse(user.toString().contains("Private"))
  }

  @Test
  fun `opaque credential ids reject empty whitespace and control characters`() {
    listOf("", " ", "a b", "a\n").forEach { value ->
      assertFailsWith<IllegalArgumentException> { CredentialId(value) }
    }
  }
}
