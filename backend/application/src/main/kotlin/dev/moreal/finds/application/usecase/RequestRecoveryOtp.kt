package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.command.CanonicalCommandEncoder
import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.EmailAddress
import dev.moreal.finds.domain.identity.UserStatus

data class RequestRecoveryOtpCommand(val email: EmailAddress, val metadata: CommandMetadata) {
  init { require(metadata.idempotencyKey != null) { "Recovery request requires an idempotency key" } }
}
sealed interface RequestRecoveryOtpResult { data object Accepted : RequestRecoveryOtpResult }

class RequestRecoveryOtp(private val transactions: TransactionPort, private val clock: ClockPort,
  private val random: SecureRandomPort, private val hashes: KeyedIdentityHashPort, private val notifier: VerificationCodeNotifier) {
  fun execute(command: RequestRecoveryOtpCommand): RequestRecoveryOtpResult {
    val digest = hashes.hash(IdentityHashPurpose.COMMAND_SCOPE, command.email.normalized, "")
    val scope = "RECOVERY:v${digest.pepperVersion}:" + digest.bytes.joinToString("") { "%02x".format(it) }
    val operation = "recovery.otp.request"
    val key = CommandRequestKey(scope, operation, checkNotNull(command.metadata.idempotencyKey))
    return transactions.execute { tx ->
      val user = tx.users.lockByEmail(command.email)
      val now = clock.now()
      when (val reserved = tx.commandRequests.reserve(CommandRequest(key,
        CanonicalCommandEncoder.hash(mapOf("scope" to scope, "purpose" to "RECOVERY")), now))) {
        CommandReservation.Conflict -> return@execute RequestRecoveryOtpResult.Accepted
        is CommandReservation.Replay -> {
          reserved.result.requireSupported(operation, 1)
          check(reserved.result.outcome == "ACCEPTED" && reserved.result.resourceIds.isEmpty()) { "Invalid recovery request result" }
          return@execute RequestRecoveryOtpResult.Accepted
        }
        CommandReservation.Reserved -> Unit
      }
      if (user?.status == UserStatus.ACTIVE && tx.recoveryCodes.findByUserId(user.id) != null) {
        val state = tx.otpChallenges.find(command.email, VerificationPurpose.RECOVERY)
          ?: OtpAccountState(command.email, VerificationPurpose.RECOVERY)
        if (state.lockedUntil?.let { now < it } != true && state.lastIssuedAt?.let { now < it.plusSeconds(60) } != true) {
          val code = VerificationCode(random.nextInt(100_000_000).toString().padStart(8, '0'))
          val challenge = OtpChallenge(DeliveryRequestId(random.uuid()),
            hashes.hash(IdentityHashPurpose.RECOVERY_OTP, command.email.normalized, code.value), now.plusSeconds(600))
          tx.otpChallenges.save(state.copy(challenge = challenge, lastIssuedAt = now))
          notifier.deliver(tx, command.email, VerificationPurpose.RECOVERY, code, challenge.expiresAt,
            challenge.deliveryId, command.metadata.correlationId)
        }
      }
      tx.commandRequests.complete(key, StoredCommandResult(1, operation, "ACCEPTED"))
      RequestRecoveryOtpResult.Accepted
    }
  }
}
