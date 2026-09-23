package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.audit.AuditAction
import dev.moreal.finds.application.audit.AuditEvent
import dev.moreal.finds.application.audit.AuditOutcome
import dev.moreal.finds.application.command.CanonicalCommandEncoder
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.domain.identity.*
import java.util.Base64

data class CompletePasskeyEnrollmentCommand(
  val sessionId: RestrictedSessionId,
  val registration: VerifiedPasskeyRegistration,
  val metadata: CommandMetadata,
) {
  init { require(metadata.idempotencyKey != null) { "Enrollment completion requires an idempotency key" } }
}

sealed interface CompletePasskeyEnrollmentResult {
  data class Completed(val userId: UserId, val recoveryCode: RecoveryCode) : CompletePasskeyEnrollmentResult
  data class AlreadyCompleted(val userId: UserId) : CompletePasskeyEnrollmentResult
  data object Rejected : CompletePasskeyEnrollmentResult
  data object IdempotencyConflict : CompletePasskeyEnrollmentResult
}

class CompletePasskeyEnrollment(
  private val transactions: TransactionPort,
  private val clock: ClockPort,
  private val random: SecureRandomPort,
  private val hashes: KeyedIdentityHashPort,
  private val initialRoles: InitialRolePolicyPort,
) {
  fun execute(command: CompletePasskeyEnrollmentCommand): CompletePasskeyEnrollmentResult {
    val proof = command.registration
    if (proof.sessionId != command.sessionId) return CompletePasskeyEnrollmentResult.Rejected
    return transactions.execute { tx ->
      val initialSession = tx.restrictedSessions.findById(command.sessionId)
        ?: return@execute CompletePasskeyEnrollmentResult.Rejected
      if (initialSession.userId != proof.userId) return@execute CompletePasskeyEnrollmentResult.Rejected
      val email = tx.users.findById(initialSession.userId)?.email
        ?: return@execute CompletePasskeyEnrollmentResult.Rejected
      val user = tx.users.lockByEmail(email) ?: return@execute CompletePasskeyEnrollmentResult.Rejected
      val session = tx.restrictedSessions.findById(command.sessionId)
        ?: return@execute CompletePasskeyEnrollmentResult.Rejected
      if (session.userId != user.id || session.scope != RestrictedSessionScope.ENROLLMENT)
        return@execute CompletePasskeyEnrollmentResult.Rejected
      val now = clock.now()
      val key = CommandRequestKey(user.id.value.toString(), "enrollment.complete", checkNotNull(command.metadata.idempotencyKey))
      // Fingerprint every persisted input; only the digest is stored. Ceremony proofs, session IDs,
      // generated timestamps and recovery plaintext are not semantic credential inputs.
      val credential = proof.credential
      val requestHash = CanonicalCommandEncoder.hash(mapOf(
        "user" to user.id.value.toString(),
        "credential" to mapOf(
          "id" to credential.id.value,
          "publicKeyCose" to Base64.getEncoder().encodeToString(credential.publicKeyCose),
          "signatureCount" to credential.signatureCount,
          "transports" to credential.transports.sorted(),
          "backupEligible" to credential.backupEligible,
          "backedUp" to credential.backedUp,
        ),
      ))
      when (val reservation = tx.commandRequests.reserve(CommandRequest(key, requestHash, now, CommandRetention.AUDIT))) {
        CommandReservation.Conflict -> return@execute CompletePasskeyEnrollmentResult.IdempotencyConflict
        is CommandReservation.Replay -> {
          reservation.result.requireSupported("enrollment.complete", 1)
          return@execute when (reservation.result.outcome) {
            "COMPLETED" -> {
              check(reservation.result.resourceIds == mapOf("user" to CommandResourceId.Uuid(user.id.value))) {
                "Invalid enrollment completion result"
              }
              CompletePasskeyEnrollmentResult.AlreadyCompleted(user.id)
            }
            "REJECTED" -> {
              check(reservation.result.resourceIds.isEmpty()) { "Invalid enrollment rejection result" }
              CompletePasskeyEnrollmentResult.Rejected
            }
            else -> throw UnsupportedCommandResultException()
          }
        }
        CommandReservation.Reserved -> Unit
      }

      fun reject(): CompletePasskeyEnrollmentResult {
        tx.commandRequests.complete(key, StoredCommandResult(1, "enrollment.complete", "REJECTED"))
        return CompletePasskeyEnrollmentResult.Rejected
      }
      if (!session.isUsable(now) || user.status != UserStatus.PENDING_PASSKEY) return@execute reject()
      val initialized = User(user.id, user.email, roles = initialRoles.rolesForVerifiedEmail(user.email))
      val activated = when (val change = initialized.registerCredential(proof.credential.id)) {
        is UserChange.Updated -> change.user
        else -> return@execute reject()
      }
      if (!tx.credentials.insert(PasskeyCredential(user.id, proof.credential, now))) return@execute reject()
      tx.users.save(activated)
      tx.restrictedSessions.invalidateForUser(user.id, RestrictedSessionScope.ENROLLMENT, now)
      val entropy = random.bytes(16)
      val recoveryCode = try { RecoveryCode.fromBytes(entropy) } finally { entropy.fill(0) }
      tx.recoveryCodes.save(RecoveryCodeHash(user.id,
        hashes.hash(IdentityHashPurpose.RECOVERY_CODE, user.id.value.toString(), recoveryCode.format()), now))
      tx.auditLog.append(AuditEvent(random.uuid(), 1, now,
        Actor.User(user.id.value, activated.roles, now, AuthenticationStrength.PASSKEY),
        AuditAction.PASSKEY_REGISTERED, "user", user.id.value.toString(),
        command.metadata.requestId, command.metadata.correlationId, AuditOutcome.SUCCEEDED))
      tx.commandRequests.complete(key, StoredCommandResult(1, "enrollment.complete", "COMPLETED",
        mapOf("user" to CommandResourceId.Uuid(user.id.value))))
      CompletePasskeyEnrollmentResult.Completed(user.id, recoveryCode)
    }
  }
}
