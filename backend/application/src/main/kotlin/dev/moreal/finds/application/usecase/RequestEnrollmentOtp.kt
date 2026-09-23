package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.command.CanonicalCommandEncoder
import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.EmailAddress
import dev.moreal.finds.domain.identity.UserStatus

data class RequestEnrollmentOtpCommand(val email: EmailAddress, val metadata: CommandMetadata) {
  init { require(metadata.idempotencyKey != null) { "Enrollment request requires an idempotency key" } }
}

sealed interface RequestEnrollmentOtpResult {
  data object Accepted : RequestEnrollmentOtpResult
}

class RequestEnrollmentOtp(
  private val transactions: TransactionPort,
  private val clock: ClockPort,
  private val random: SecureRandomPort,
  private val hashes: KeyedIdentityHashPort,
  private val notifier: VerificationCodeNotifier,
) {
  fun execute(command: RequestEnrollmentOtpCommand): RequestEnrollmentOtpResult {
    val scopeHash = hashes.hash(IdentityHashPurpose.COMMAND_SCOPE, command.email.normalized, "")
    val scope = "ENROLLMENT:v${scopeHash.pepperVersion}:" + scopeHash.bytes.joinToString("") { "%02x".format(it) }
    val key = CommandRequestKey(scope, "enrollment.otp.request", checkNotNull(command.metadata.idempotencyKey))
    val requestHash = CanonicalCommandEncoder.hash(mapOf("scope" to scope, "purpose" to "ENROLLMENT"))
    return transactions.execute { tx ->
      val user = tx.users.lockByEmail(command.email)
      val now = clock.now()
      when (val reservation = tx.commandRequests.reserve(CommandRequest(key, requestHash, now))) {
        CommandReservation.Conflict -> return@execute RequestEnrollmentOtpResult.Accepted
        is CommandReservation.Replay -> {
          reservation.result.requireSupported("enrollment.otp.request", 1)
          check(reservation.result.outcome == "ACCEPTED" && reservation.result.resourceIds.isEmpty()) {
            "Invalid enrollment request result"
          }
          return@execute RequestEnrollmentOtpResult.Accepted
        }
        CommandReservation.Reserved -> Unit
      }

      if (user == null || user.status == UserStatus.PENDING_PASSKEY) {
        val state = tx.otpChallenges.find(command.email, VerificationPurpose.ENROLLMENT)
          ?: OtpAccountState(command.email, VerificationPurpose.ENROLLMENT)
        if (state.lockedUntil?.let { now < it } != true) {
          val code = VerificationCode(random.nextInt(100_000_000).toString().padStart(8, '0'))
          val challenge = OtpChallenge(DeliveryRequestId(random.uuid()),
            hashes.hash(IdentityHashPurpose.ENROLLMENT_OTP, command.email.normalized, code.value), now.plusSeconds(600))
          tx.otpChallenges.save(state.copy(challenge = challenge, lastIssuedAt = now))
          notifier.deliver(tx, command.email, VerificationPurpose.ENROLLMENT, code, challenge.expiresAt,
            challenge.deliveryId, command.metadata.correlationId)
        }
      }
      tx.commandRequests.complete(key, StoredCommandResult(1, "enrollment.otp.request", "ACCEPTED"))
      RequestEnrollmentOtpResult.Accepted
    }
  }
}
