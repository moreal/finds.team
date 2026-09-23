package dev.moreal.finds_team.security

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.EmailAddress
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Transport parsing, privacy, and abuse policy only; account decisions remain application results. */
class OtpHttpBoundary(private val rates: AuthRateLimitPort, private val hashes: KeyedIdentityHashPort,
  private val random: SecureRandomPort, private val clock: ClockPort, properties: SecurityProperties) {
  private val mapper = JsonMapper.builder().build()
  private val addresses = TrustedClientAddress(properties.trustedProxyCidrs)

  @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "\${finds.security.rate-cleanup-interval:10s}")
  fun cleanup(): Int = rates.purgeExpired(clock.now(), 1000)

  fun <T> timed(block: () -> T): T {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200L + random.nextInt(21))
    try { return block() } finally {
      // The same response-time floor/jitter applies to both account states and every public outcome.
      // No network delivery occurs on this path. This is a timing class, not a constant-time DB claim.
      val remaining = deadline - System.nanoTime()
      if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
    }
  }
  fun body(request: HttpServletRequest): JsonNode = parse {
    require(request.contentLengthLong <= 4096)
    val bytes = request.inputStream.readNBytes(4097)
    require(bytes.size <= 4096)
    mapper.readTree(bytes).also { require(it.isObject) }
  }
  fun email(body: JsonNode): EmailAddress = parse { EmailAddress(requiredText(body, "email")) }
  fun otp(body: JsonNode): VerificationCode = parse { VerificationCode(requiredText(body, "otp")) }
  fun optionalText(body: JsonNode, name: String): String? = parse {
    body[name]?.takeUnless { it.isNull }?.also { require(it.isTextual) }?.asText()
  }
  fun metadata(request: HttpServletRequest): CommandMetadata = parse {
    CommandMetadata.parse(request.getHeader("X-Request-ID") ?: UUID.randomUUID().toString(),
      request.getHeader("X-Correlation-ID") ?: UUID.randomUUID().toString(), requireNotNull(request.getHeader("Idempotency-Key")))
  }
  fun limit(request: HttpServletRequest, response: HttpServletResponse, email: EmailAddress, verifying: Boolean) {
    val operation = if (verifying) "verify" else "request"
    val device = device(request, response)
    val values = listOf(Triple("email", email.normalized, if (verifying) 10 else 3),
      Triple("ip", addresses.resolve(request), if (verifying) 60 else 30),
      Triple("device", device, if (verifying) 20 else 10))
    val buckets = values.map { (dimension, value, limit) ->
      AuthRateBucket(hashes.hash(IdentityHashPurpose.COMMAND_SCOPE, "http.rate.$operation.$dimension", value), limit)
    }
    when (val decision = rates.consume(buckets, clock.now())) {
      AuthRateDecision.Allowed -> Unit
      is AuthRateDecision.Limited -> throw OtpHttpRejected(HttpStatus.TOO_MANY_REQUESTS, decision.retryAfterSeconds)
    }
  }
  fun bind(request: HttpServletRequest, response: HttpServletResponse, restricted: RestrictedSession) {
    // Replace all prior ceremony/proof/context state. A proof verification never inherits login.
    request.getSession(false)?.invalidate()
    SecurityContextHolder.clearContext()
    HttpSessionSecurityContextRepository().saveContext(SecurityContextHolder.createEmptyContext(), request, response)
    request.session.setAttribute(WebAuthnCeremonies.RESTRICTED_SESSION, restricted.id)
  }
  fun accepted() = json(mapOf("accepted" to true), HttpStatus.ACCEPTED)
  fun verified(scope: String) = json(mapOf("scope" to scope))
  fun json(value: Any, status: HttpStatus = HttpStatus.OK): ResponseEntity<SecurityJson> = ResponseEntity.status(status)
    .header("Cache-Control", "no-store").body(SecurityJson(mapper.writeValueAsString(value)))
  private fun requiredText(body: JsonNode, name: String): String = body.required(name).also { require(it.isTextual) }.asText()
  private fun <T> parse(block: () -> T): T = try { block() } catch (_: Exception) { throw OtpHttpRejected(HttpStatus.BAD_REQUEST) }

  private fun device(request: HttpServletRequest, response: HttpServletResponse): String {
    val cookie = request.cookies?.singleOrNull { it.name == DEVICE_COOKIE }?.value
    val existing = try {
      val parts = cookie?.takeIf { it.length <= 160 }?.split('.')
      if (parts?.size == 4 && parts[0].matches(Regex("[A-Za-z0-9_-]{43}")) &&
        parts[2].toLong() > clock.now().epochSecond && parts[2].toLong() <= clock.now().epochSecond + DEVICE_LIFETIME &&
        hashes.matches(KeyedIdentityHash(parts[1].toInt(), Base64.getUrlDecoder().decode(parts[3])),
          IdentityHashPurpose.COMMAND_SCOPE, "http.device", "${parts[0]}.${parts[2]}")) parts[0] else null
    } catch (_: Exception) { null }
    if (existing != null) return existing
    val opaque = Base64.getUrlEncoder().withoutPadding().encodeToString(random.bytes(32))
    val expiry = clock.now().epochSecond + DEVICE_LIFETIME
    val mac = hashes.hash(IdentityHashPurpose.COMMAND_SCOPE, "http.device", "$opaque.$expiry")
    val token = "$opaque.${mac.pepperVersion}.$expiry.${Base64.getUrlEncoder().withoutPadding().encodeToString(mac.bytes)}"
    response.addHeader("Set-Cookie", ResponseCookie.from(DEVICE_COOKIE, token).secure(true).httpOnly(true)
      .sameSite("Lax").path("/").maxAge(Duration.ofSeconds(DEVICE_LIFETIME)).build().toString())
    return opaque
  }
  companion object {
    const val DEVICE_COOKIE = "__Host-finds-device"
    private const val DEVICE_LIFETIME = 2592000L
  }
}

/** Forwarding is honored only behind an explicitly configured proxy, scanning from the trusted end. */
internal class TrustedClientAddress(cidrs: Set<String>) {
  private val trusted = cidrs.map { IpAddressMatcher(it) }
  fun resolve(request: HttpServletRequest): String {
    val peer = numeric(request.remoteAddr) ?: "unknown"
    if (!trusted.any { it.matches(peer) }) return peer
    val forwarded = request.getHeader("X-Forwarded-For")?.takeIf { it.length <= 2048 } ?: return peer
    var current = peer
    for (entry in forwarded.split(',').asReversed()) {
      if (!trusted.any { it.matches(current) }) break
      current = numeric(entry.trim()) ?: return peer
    }
    return current
  }
  private fun numeric(value: String): String? = try {
    if (value.contains(':') && value.matches(Regex("[0-9a-fA-F:.]+")) || value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))
      InetAddress.getByName(value).hostAddress else null
  } catch (_: Exception) { null }
}

internal class OtpHttpRejected(val status: HttpStatus, val retryAfter: Int? = null) : RuntimeException()

@RestControllerAdvice(assignableTypes = [EnrollmentController::class, RecoveryController::class, AuthSessionController::class])
class OtpHttpErrors {
  @ExceptionHandler(OtpHttpRejected::class)
  internal fun rejected(error: OtpHttpRejected): ResponseEntity<ProblemDetail> {
    val response = ResponseEntity.status(error.status).header("Cache-Control", "no-store")
    error.retryAfter?.let { response.header("Retry-After", it.toString()) }
    return response.body(ProblemDetail.forStatusAndDetail(error.status, "Authentication request rejected"))
  }
}
