package dev.moreal.finds_team.security

import dev.moreal.finds.application.usecase.*
import dev.moreal.finds.application.port.VerificationCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
class RecoveryController(private val requestOtp: RequestRecoveryOtp, private val verifyProofs: VerifyRecoveryProofs,
  private val boundary: OtpHttpBoundary) {
  @PostMapping("/auth/recovery/otp/request", consumes = ["application/json"], produces = ["application/json"])
  fun request(request: HttpServletRequest, response: HttpServletResponse) = boundary.timed {
    val email = boundary.email(boundary.body(request)); val metadata = boundary.metadata(request)
    boundary.limit(request, response, email, verifying = false)
    when (requestOtp.execute(RequestRecoveryOtpCommand(email, metadata))) {
      RequestRecoveryOtpResult.Accepted -> boundary.accepted()
    }
  }
  @PostMapping("/auth/recovery/otp/verify", consumes = ["application/json"], produces = ["application/json"])
  fun verify(request: HttpServletRequest, response: HttpServletResponse) = boundary.timed {
    val body = boundary.body(request); val email = boundary.email(body)
    val otp = boundary.optionalText(body, "otp")?.let { runCatching { VerificationCode(it) }.getOrNull() }
    val recovery = boundary.optionalText(body, "recoveryCode")
    boundary.limit(request, response, email, verifying = true)
    when (val result = verifyProofs.execute(VerifyRecoveryProofsCommand(email, otp, recovery))) {
      is VerifyRecoveryProofsResult.Verified -> {
        boundary.bind(request, response, result.session); boundary.verified("RECOVERY")
      }
      VerifyRecoveryProofsResult.Rejected -> throw OtpHttpRejected(HttpStatus.UNAUTHORIZED)
    }
  }
}
