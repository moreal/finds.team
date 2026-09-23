package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.command.CanonicalCommandEncoder
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.domain.identity.*
import kotlin.test.*

class SecurityManagementTest {
  @Test fun `logout same request replays SignedOut after revocation without exposing session or auditing again`() {
    val f = SecurityFixture()
    val manage = ManageSessions(f.tx, f.clock, f.random)
    val metadata = f.metadata()
    val principal = f.principal()
    assertEquals(SecurityChangeResult.SignedOut, manage.logout(principal, metadata))
    assertEquals(SecurityChangeResult.SignedOut, manage.logout(principal, metadata))
    assertEquals(1, f.tx.auditEvents.size)
    assertEquals(AuditAction.SESSION_REVOKED, f.tx.auditEvents.single().action)
    assertTrue(f.tx.auditEvents.single().details.fields.isEmpty())
    assertEquals(f.userId.value.toString(), f.tx.auditEvents.single().targetId)
    assertEquals("SIGNED_OUT", f.tx.completedRequests.values.single().outcome)
    assertTrue(f.tx.completedRequests.values.single().resourceIds.isEmpty())
    assertNull(f.tx.userSessions.single { it.id == f.otherSessionId }.revokedAt)
    assertEquals(SecurityChangeResult.IdempotencyConflict, manage.logout(principal.copy(sessionId = f.otherSessionId), metadata))
  }

  @Test fun `logout refuses a session belonging to another trusted authentication context`() {
    val f = SecurityFixture()
    val manage = ManageSessions(f.tx, f.clock, f.random)
    val principal = f.principal()
    for (invalid in listOf(principal.copy(sessionId = UserSessionId(java.util.UUID.randomUUID())),
      principal.copy(actor = dev.moreal.finds.application.security.Actor.User(principal.actor.userId,
        principal.actor.roles, f.start.minusSeconds(1), AuthenticationStrength.PASSKEY)),
      f.principal(AuthenticationStrength.EMAIL_OTP))) {
      assertEquals(SecurityChangeResult.Forbidden, manage.logout(invalid, f.metadata()))
    }
    assertTrue(f.tx.userSessions.all { it.revokedAt == null })
    assertTrue(f.tx.auditEvents.isEmpty())
    assertTrue(f.tx.completedRequests.isEmpty())
  }
  @Test fun `role replay rejects an outcome from a different security operation`() {
    val f = SecurityFixture()
    f.addAdminAndTarget()
    val metadata = f.metadata()
    f.tx.execute { tx ->
      val key = CommandRequestKey(f.userId.value.toString(), "role.grant", metadata.idempotencyKey!!)
      tx.commandRequests.reserve(CommandRequest(key, CanonicalCommandEncoder.hash(mapOf("target" to f.otherId.value.toString(), "role" to "ADMIN")), f.now, CommandRetention.AUDIT))
      tx.commandRequests.complete(key, StoredCommandResult(1, "role.grant", "SIGNED_OUT"))
    }
    assertFailsWith<UnsupportedCommandResultException> { ManageRoles(f.tx, f.clock, f.random).grant(f.principal(), f.otherId, UserRole.ADMIN, metadata) }
    assertTrue(f.tx.auditEvents.isEmpty())
    assertEquals(setOf(UserRole.USER), f.tx.users.single { it.id == f.otherId }.roles)
  }

  @Test fun `active account adds a verified new passkey with a label and idempotent audit`() {
    val f = SecurityFixture()
    val session = assertIs<AdditionalPasskeySessionResult.Ready>(BeginAdditionalPasskeyRegistration(f.tx, f.clock, f.random).execute(f.principal())).session
    assertEquals(RestrictedSessionScope.ADDITIONAL_PASSKEY, session.scope)
    val proof = VerifiedPasskeyRegistration(f.userId, session.id, f.material("additional-passkey"))
    val command = RegisterAdditionalPasskeyCommand(f.principal(), session.id, proof, "Laptop", f.metadata())
    val register = RegisterAdditionalPasskey(f.tx, f.clock, f.random)
    repeat(2) { assertEquals(SecurityChangeResult.Changed, register.execute(command)) }
    assertEquals(3, f.tx.credentials.size)
    assertEquals(3, f.tx.users.single().credentials.size)
    assertEquals("Laptop", f.tx.credentials.single { it.material.id.value == "additional-passkey" }.label)
    assertEquals(f.now, f.tx.restrictedSessions.single().invalidatedAt)
    assertEquals(AuditAction.PASSKEY_REGISTERED, f.tx.auditEvents.single().action)
    assertTrue(f.tx.auditEvents.single().details.fields.isEmpty())
    assertEquals(SecurityChangeResult.IdempotencyConflict, register.execute(command.copy(label = "Changed")))
    val changedProof = VerifiedPasskeyRegistration(f.userId, session.id,
      PasskeyCredentialMaterial(CredentialId("additional-passkey"), byteArrayOf(9), 0, setOf("internal"), true, true))
    assertEquals(SecurityChangeResult.IdempotencyConflict, register.execute(command.copy(registration = changedProof)))
  }

  @Test fun `additional passkey requires live normal recent authentication and a matching scoped ceremony`() {
    for (condition in listOf("weak", "stale", "suspended", "scope", "user", "session", "used", "expired")) {
      val f = SecurityFixture()
      val begin = BeginAdditionalPasskeyRegistration(f.tx, f.clock, f.random)
      val session = assertIs<AdditionalPasskeySessionResult.Ready>(begin.execute(f.principal())).session
      val principal = f.principal(if (condition == "weak") AuthenticationStrength.RECOVERY_PROOFS else AuthenticationStrength.PASSKEY)
      var proof = VerifiedPasskeyRegistration(f.userId, session.id, f.material("additional-passkey"))
      when (condition) {
        "stale" -> f.now = f.start.plusSeconds(301)
        "suspended" -> f.suspend()
        "scope" -> f.tx.execute { it.users.lockByEmail(f.email); it.restrictedSessions.save(session.copy(scope = RestrictedSessionScope.RECOVERY)) }
        "user" -> proof = VerifiedPasskeyRegistration(f.otherId, session.id, proof.credential)
        "session" -> proof = VerifiedPasskeyRegistration(f.userId, RestrictedSessionId(java.util.UUID.randomUUID()), proof.credential)
        "used" -> f.tx.execute { it.users.lockByEmail(f.email); it.restrictedSessions.save(session.copy(invalidatedAt = f.now)) }
        "expired" -> f.tx.execute { it.users.lockByEmail(f.email); it.restrictedSessions.save(session.copy(createdAt = f.start.minusSeconds(600), expiresAt = f.start)) }
      }
      assertEquals(SecurityChangeResult.Forbidden, RegisterAdditionalPasskey(f.tx, f.clock, f.random)
        .execute(RegisterAdditionalPasskeyCommand(principal, session.id, proof, "Laptop", f.metadata())), condition)
      assertEquals(2, f.tx.credentials.size)
      assertTrue(f.tx.auditEvents.isEmpty())
      if (condition in setOf("weak", "stale", "suspended")) assertEquals(AdditionalPasskeySessionResult.Forbidden, begin.execute(principal))
    }
  }

  @Test fun `rename validates labels owns credential and audits only an actual change`() {
    val f = SecurityFixture()
    val rename = RenamePasskey(f.tx, f.clock, f.random)
    val metadata = f.metadata()
    val id = CredentialId("old-passkey-1")
    repeat(2) { assertEquals(SecurityChangeResult.Changed, rename.execute(f.principal(), id, " Personal laptop ", metadata)) }
    assertEquals("Personal laptop", f.tx.credentials.single { it.material.id == id }.label)
    assertEquals(SecurityChangeResult.Unchanged, rename.execute(f.principal(), id, "Personal laptop", f.metadata()))
    assertEquals(SecurityChangeResult.IdempotencyConflict, rename.execute(f.principal(), id, "Other", metadata))
    for (invalid in listOf("", " ", "x".repeat(81), "Laptop\n"))
      assertEquals(SecurityChangeResult.InvalidLabel, rename.execute(f.principal(), id, invalid, f.metadata()))
    assertEquals(SecurityChangeResult.NotFound, rename.execute(f.principal(), CredentialId("missing"), "Laptop", f.metadata()))
    assertEquals(AuditAction.PASSKEY_RENAMED, f.tx.auditEvents.single().action)
    assertTrue(f.tx.auditEvents.single().details.fields.isEmpty())
    assertEquals(2, f.tx.users.single().credentials.size)
    f.now = f.start.plusSeconds(301)
    assertEquals(SecurityChangeResult.Forbidden, rename.execute(f.principal(), id, "Other", f.metadata()))
  }

  @Test fun `additional registration and rename roll back on audit failure`() {
    val f = SecurityFixture()
    val session = assertIs<AdditionalPasskeySessionResult.Ready>(BeginAdditionalPasskeyRegistration(f.tx, f.clock, f.random).execute(f.principal())).session
    f.tx.auditFailure = { error("audit unavailable") }
    assertFailsWith<IllegalStateException> {
      RegisterAdditionalPasskey(f.tx, f.clock, f.random).execute(RegisterAdditionalPasskeyCommand(f.principal(), session.id,
        VerifiedPasskeyRegistration(f.userId, session.id, f.material("additional-passkey")), "Laptop", f.metadata()))
    }
    assertFailsWith<IllegalStateException> { RenamePasskey(f.tx, f.clock, f.random).execute(f.principal(), CredentialId("old-passkey-1"), "Laptop", f.metadata()) }
    assertEquals(2, f.tx.credentials.size)
    assertEquals(2, f.tx.users.single().credentials.size)
    assertTrue(f.tx.credentials.all { it.label == "Passkey" })
    assertNull(f.tx.restrictedSessions.single().invalidatedAt)
    assertTrue(f.tx.completedRequests.isEmpty())
  }

  @Test fun `passkey listing exposes metadata only and removal protects last credential`() {
    val f = SecurityFixture()
    val manage = ManagePasskeys(f.tx, f.clock, f.random)
    val listed = assertIs<PasskeyListResult.Listed>(manage.list(f.principal()))
    assertEquals(setOf("old-passkey-1", "old-passkey-2"), listed.passkeys.map { it.id.value }.toSet())
    assertEquals(SecurityChangeResult.Changed, manage.remove(f.principal(), CredentialId("old-passkey-1"), f.metadata()))
    assertEquals(setOf(CredentialId("old-passkey-2")), f.tx.users.single().credentials)
    assertEquals(1, f.tx.credentials.size)
    assertEquals(SecurityChangeResult.LastCredential, manage.remove(f.principal(), CredentialId("old-passkey-2"), f.metadata()))
    assertEquals(AuditAction.PASSKEY_REMOVED, f.tx.auditEvents.single().action)
  }

  @Test fun `recent passkey boundary allows exactly five minutes and rejects future stale or weak authentication`() {
    for ((age, allowed) in listOf(300L to true, 301L to false, -1L to false)) {
      val f = SecurityFixture()
      f.now = f.start.plusSeconds(age)
      val result = ManagePasskeys(f.tx, f.clock, f.random).remove(f.principal(), CredentialId("old-passkey-1"), f.metadata())
      assertEquals(if (allowed) SecurityChangeResult.Changed else SecurityChangeResult.Forbidden, result)
    }
    for (strength in listOf(AuthenticationStrength.EMAIL_OTP, AuthenticationStrength.RECOVERY_PROOFS)) {
      val f = SecurityFixture()
      assertEquals(SecurityChangeResult.Forbidden, ManagePasskeys(f.tx, f.clock, f.random)
        .remove(f.principal(strength), CredentialId("old-passkey-1"), f.metadata()))
    }
  }

  @Test fun `restricted suspended revoked and expired sessions cannot manage security`() {
    for (condition in listOf("restricted", "suspended", "revoked", "expired")) {
      val f = SecurityFixture()
      when (condition) {
        "suspended" -> f.suspend()
        "revoked" -> f.tx.execute { it.users.lockByEmail(f.email); it.userSessions.revoke(f.currentSessionId, f.now) }
        "expired" -> f.now = f.start.plusSeconds(3600)
      }
      val principal = f.principal(if (condition == "restricted") AuthenticationStrength.RECOVERY_PROOFS else AuthenticationStrength.PASSKEY)
      assertEquals(PasskeyListResult.Forbidden, ManagePasskeys(f.tx, f.clock, f.random).list(principal))
      assertEquals(SessionListResult.Forbidden, ManageSessions(f.tx, f.clock, f.random).list(principal))
      assertEquals(SecurityChangeResult.Forbidden, ManageSessions(f.tx, f.clock, f.random).revoke(principal, f.otherSessionId, f.metadata()))
      assertEquals(RotateRecoveryCodeResult.Forbidden, RotateRecoveryCode(f.tx, f.clock, f.random, f.hashes).execute(principal, f.metadata()))
    }
  }

  @Test fun `sessions identify current one revoke others preserves it and self revoke asks caller to logout`() {
    val f = SecurityFixture()
    val manage = ManageSessions(f.tx, f.clock, f.random)
    val list = assertIs<SessionListResult.Listed>(manage.list(f.principal())).sessions
    assertEquals(f.currentSessionId, list.single { it.current }.id)
    assertEquals(SecurityChangeResult.Changed, manage.revokeOthers(f.principal(), f.metadata()))
    assertNull(f.tx.userSessions.single { it.id == f.currentSessionId }.revokedAt)
    assertEquals(f.now, f.tx.userSessions.single { it.id == f.otherSessionId }.revokedAt)
    f.now = f.start.plusSeconds(301)
    assertEquals(SecurityChangeResult.SignedOut, manage.revoke(f.principal(), f.currentSessionId, f.metadata()))
    assertTrue(f.tx.userSessions.all { it.revokedAt != null })
    assertEquals(listOf(AuditAction.SESSION_REVOKED, AuditAction.SESSION_REVOKED), f.tx.auditEvents.map { it.action })
    assertTrue(f.tx.auditEvents.all { it.targetId == f.userId.value.toString() && it.details.fields.isEmpty() })
  }

  @Test fun `individual other session revocation leaves current authenticated and missing targets have no effect`() {
    val f = SecurityFixture()
    val manage = ManageSessions(f.tx, f.clock, f.random)
    assertEquals(SecurityChangeResult.Changed, manage.revoke(f.principal(), f.otherSessionId, f.metadata()))
    assertNull(f.tx.userSessions.single { it.id == f.currentSessionId }.revokedAt)
    assertEquals(1, assertIs<SessionListResult.Listed>(manage.list(f.principal())).sessions.size)
    assertEquals(SecurityChangeResult.NotFound, manage.revoke(f.principal(), UserSessionId(java.util.UUID.randomUUID()), f.metadata()))
    assertEquals(SecurityChangeResult.NotFound, ManagePasskeys(f.tx, f.clock, f.random).remove(f.principal(), CredentialId("missing"), f.metadata()))
    assertEquals(1, f.tx.auditEvents.size)
  }

  @Test fun `self revocation replay from another live session cannot instruct that caller to sign out`() {
    val f = SecurityFixture()
    val manage = ManageSessions(f.tx, f.clock, f.random)
    val metadata = f.metadata()
    assertEquals(SecurityChangeResult.SignedOut, manage.revoke(f.principal(), f.currentSessionId, metadata))
    val otherPrincipal = f.principal().copy(sessionId = f.otherSessionId)
    assertEquals(SecurityChangeResult.IdempotencyConflict, manage.revoke(otherPrincipal, f.currentSessionId, metadata))
    assertEquals(SecurityChangeResult.Unchanged, manage.revoke(otherPrincipal, f.currentSessionId, f.metadata()))
    assertNull(f.tx.userSessions.single { it.id == f.otherSessionId }.revokedAt)
    assertEquals(f.otherSessionId, assertIs<SessionListResult.Listed>(manage.list(otherPrincipal)).sessions.single().id)
    assertEquals(1, f.tx.auditEvents.size)
  }

  @Test fun `recovery rotation invalidates pending recovery proof sessions and returns plaintext only once`() {
    val f = SecurityFixture()
    val pending = f.beginRecovery()
    val rotate = RotateRecoveryCode(f.tx, f.clock, f.random, f.hashes)
    val metadata = f.metadata()
    val result = assertIs<RotateRecoveryCodeResult.Rotated>(rotate.execute(f.principal(), metadata))
    assertNotEquals(f.oldRecovery.format(), result.recoveryCode.format())
    assertTrue(f.hashes.matches(f.tx.recoveryCodes.single().hash, IdentityHashPurpose.RECOVERY_CODE,
      f.userId.value.toString(), result.recoveryCode.format()))
    assertEquals(RotateRecoveryCodeResult.AlreadyRotated, rotate.execute(f.principal(), metadata))
    assertEquals(CompletePasskeyRecoveryResult.Rejected, f.completeRecovery(pending))
    assertEquals(AuditAction.RECOVERY_CODE_ROTATED, f.tx.auditEvents.single().action)
    assertTrue(f.tx.completedRequests.values.all { it.resourceIds.isEmpty() })
  }

  @Test fun `role commands require current stored ADMIN role and recent passkey`() {
    val f = SecurityFixture()
    val manage = ManageRoles(f.tx, f.clock, f.random)
    assertEquals(SecurityChangeResult.Forbidden, manage.grant(f.principal(), f.otherId, UserRole.ADMIN, f.metadata()))
    f.addAdminAndTarget()
    f.now = f.start.plusSeconds(301)
    assertEquals(SecurityChangeResult.Forbidden, manage.grant(f.principal(), f.otherId, UserRole.ADMIN, f.metadata()))
    f.now = f.start.plusSeconds(300)
    assertEquals(SecurityChangeResult.Changed, manage.grant(f.principal(), f.otherId, UserRole.ADMIN, f.metadata()))
    assertEquals(setOf(UserRole.USER, UserRole.ADMIN), f.tx.users.single { it.id == f.otherId }.roles)
    assertEquals(mapOf("role" to "ADMIN"), f.tx.auditEvents.single().details.fields)
  }

  @Test fun `role commands are idempotent conflict on different semantics and audit only actual changes`() {
    val f = SecurityFixture()
    f.addAdminAndTarget()
    val manage = ManageRoles(f.tx, f.clock, f.random)
    val metadata = f.metadata()
    repeat(2) { assertEquals(SecurityChangeResult.Changed, manage.grant(f.principal(), f.otherId, UserRole.ADMIN, metadata)) }
    assertEquals(SecurityChangeResult.IdempotencyConflict, manage.grant(f.principal(), f.userId, UserRole.ADMIN, metadata))
    assertEquals(SecurityChangeResult.Unchanged, manage.grant(f.principal(), f.otherId, UserRole.ADMIN, f.metadata()))
    assertEquals(SecurityChangeResult.RequiredUserRole, manage.revoke(f.principal(), f.otherId, UserRole.USER, f.metadata()))
    val revokeMetadata = f.metadata()
    repeat(2) { assertEquals(SecurityChangeResult.Changed, manage.revoke(f.principal(), f.otherId, UserRole.ADMIN, revokeMetadata)) }
    assertEquals(SecurityChangeResult.Unchanged, manage.revoke(f.principal(), f.otherId, UserRole.ADMIN, f.metadata()))
    assertEquals(listOf(AuditAction.ROLE_GRANTED, AuditAction.ROLE_REVOKED), f.tx.auditEvents.map { it.action })
    assertEquals(setOf(UserRole.USER), f.tx.users.single { it.id == f.otherId }.roles)
  }

  @Test fun `concurrent role retries apply one audited change`() {
    val f = SecurityFixture()
    f.addAdminAndTarget()
    val metadata = f.metadata()
    val results = concurrent { ManageRoles(f.tx, f.clock, f.random).grant(f.principal(), f.otherId, UserRole.ADMIN, metadata) }
    assertTrue(results.all { it == SecurityChangeResult.Changed })
    assertEquals(1, f.tx.auditEvents.size)
  }

  @Test fun `audit failure rolls back credential session rotation and role mutations with reservations`() {
    val mutations: List<(SecurityFixture) -> Unit> = listOf(
      { f -> ManagePasskeys(f.tx, f.clock, f.random).remove(f.principal(), CredentialId("old-passkey-1"), f.metadata()) },
      { f -> ManageSessions(f.tx, f.clock, f.random).revokeOthers(f.principal(), f.metadata()) },
      { f -> ManageSessions(f.tx, f.clock, f.random).logout(f.principal(), f.metadata()) },
      { f -> RotateRecoveryCode(f.tx, f.clock, f.random, f.hashes).execute(f.principal(), f.metadata()) },
      { f -> ManageRoles(f.tx, f.clock, f.random).grant(f.principal(), f.otherId, UserRole.ADMIN, f.metadata()) },
    )
    for (mutation in mutations) {
      val f = SecurityFixture()
      f.addAdminAndTarget()
      val recovery = f.tx.recoveryCodes.single()
      f.tx.auditFailure = { error("audit unavailable") }
      assertFailsWith<IllegalStateException> { mutation(f) }
      assertEquals(2, f.tx.credentials.size)
      assertTrue(f.tx.userSessions.all { it.revokedAt == null })
      assertSame(recovery, f.tx.recoveryCodes.single())
      assertEquals(setOf(UserRole.USER), f.tx.users.single { it.id == f.otherId }.roles)
      assertTrue(f.tx.completedRequests.isEmpty())
      assertTrue(f.tx.auditEvents.isEmpty())
    }
  }
}
