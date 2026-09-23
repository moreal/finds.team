package dev.moreal.finds_team.security

import dev.moreal.finds.application.usecase.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
class EnrollmentController(private val requestOtp: RequestEnrollmentOtp, private val verifyOtp: VerifyEnrollmentOtp,
  private val boundary: OtpHttpBoundary) {
  @PostMapping("/auth/enrollment/otp/request", consumes = ["application/json"], produces = ["application/json"])
  fun request(request: HttpServletRequest, response: HttpServletResponse) = boundary.timed {
    val email = boundary.email(boundary.body(request)); val metadata = boundary.metadata(request)
    boundary.limit(request, response, email, verifying = false)
    when (requestOtp.execute(RequestEnrollmentOtpCommand(email, metadata))) {
      RequestEnrollmentOtpResult.Accepted -> boundary.accepted()
    }
  }
  @PostMapping("/auth/enrollment/otp/verify", consumes = ["application/json"], produces = ["application/json"])
  fun verify(request: HttpServletRequest, response: HttpServletResponse) = boundary.timed {
    val body = boundary.body(request); val email = boundary.email(body); val otp = boundary.otp(body)
    boundary.limit(request, response, email, verifying = true)
    when (val result = verifyOtp.execute(VerifyEnrollmentOtpCommand(email, otp))) {
      is VerifyEnrollmentOtpResult.Verified -> {
        boundary.bind(request, response, result.session); boundary.verified("ENROLLMENT")
      }
      VerifyEnrollmentOtpResult.Rejected -> throw OtpHttpRejected(HttpStatus.UNAUTHORIZED)
    }
  }
}
