package dev.moreal.finds_team.security

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.SecurityEventAction
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse
import org.springframework.security.web.webauthn.api.PublicKeyCredential
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey
import org.springframework.web.bind.annotation.*
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import com.fasterxml.jackson.annotation.JsonRawValue
import com.fasterxml.jackson.annotation.JsonValue

@RestController
class WebAuthnController(private val ceremonies: WebAuthnCeremonies, private val securityEvents: HttpSecurityEvents) {
  private val mapper = JsonMapper.builder().addModule(WebauthnJacksonModule()).build()
  private val contexts = HttpSessionSecurityContextRepository()

  @GetMapping("/auth/csrf", produces = ["application/json"])
  fun csrf(token: CsrfToken): ResponseEntity<SecurityJson> = json(mapOf("token" to token.token, "headerName" to token.headerName))

  @PostMapping("/webauthn/authenticate/options", produces = ["application/json"])
  fun authenticationOptions(request: HttpServletRequest): ResponseEntity<SecurityJson> = json(ceremonies.authenticationOptions(request))

  @PostMapping("/login/webauthn", consumes = ["application/json"], produces = ["application/json"])
  fun authenticate(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<SecurityJson> {
    val body = readBody(request)
    val credential = parse { requireNotNull(mapper.readValue(body, object : TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>() {})) }
    val authentication = ceremonies.authenticate(request, credential)
    ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response)
    CsrfAuthenticationStrategy(HttpSessionCsrfTokenRepository()).onAuthentication(authentication, request, response)
    val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
    SecurityContextHolder.setContext(context)
    contexts.saveContext(context, request, response)
    return json(mapOf("authenticated" to true))
  }
  @PostMapping("/webauthn/register/options", produces = ["application/json"])
  fun registrationOptions(request: HttpServletRequest, authentication: Authentication?,
    @RequestBody(required = false) body: RegistrationOptionsRequest?): ResponseEntity<SecurityJson> =
    json(ceremonies.registrationOptions(request, authentication, body?.expectedUserId, body?.beginKey))

  data class RegistrationOptionsRequest(val expectedUserId: String? = null, val beginKey: String? = null)

  @PostMapping("/webauthn/register", consumes = ["application/json"], produces = ["application/json"])
  fun register(request: HttpServletRequest, authentication: Authentication?): ResponseEntity<SecurityJson> {
    val body = readBody(request)
    val key = parse { requireNotNull(mapper.treeToValue(mapper.readTree(body).required("publicKey"), RelyingPartyPublicKey::class.java)) }
    val metadata = parse { CommandMetadata.parse(request.getHeader("X-Request-ID") ?: UUID.randomUUID().toString(),
      request.getHeader("X-Correlation-ID") ?: UUID.randomUUID().toString(), requireNotNull(request.getHeader("Idempotency-Key"))) }
    return json(ceremonies.register(request, authentication, key, metadata))
  }
  @ExceptionHandler(CeremonyRejected::class)
  internal fun rejected(request: HttpServletRequest, error: CeremonyRejected): ResponseEntity<ProblemDetail> {
    securityEvents.denied(request, if (error.replayed) SecurityEventAction.CHALLENGE_REPLAY else SecurityEventAction.WEBAUTHN_FAILED)
    return ResponseEntity.status(401).header("Cache-Control", "no-store")
      .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Passkey ceremony rejected"))
  }
  @ExceptionHandler(MalformedCeremony::class)
  fun malformed(): ResponseEntity<ProblemDetail> = ResponseEntity.badRequest().header("Cache-Control", "no-store")
    .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed Passkey request"))
  @ExceptionHandler(CeremonyConflict::class)
  fun conflict(): ResponseEntity<ProblemDetail> = ResponseEntity.status(409).header("Cache-Control", "no-store")
    .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Idempotency request conflict"))
  @ExceptionHandler(CeremonyTooLarge::class)
  fun tooLarge(): ResponseEntity<ProblemDetail> = ResponseEntity.status(413).header("Cache-Control", "no-store")
    .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "Passkey request too large"))
  private fun readBody(request: HttpServletRequest): String {
    if (request.contentLengthLong > 65536) throw CeremonyTooLarge()
    val bytes = request.inputStream.readNBytes(65537)
    if (bytes.size > 65536) throw CeremonyTooLarge()
    return bytes.toString(Charsets.UTF_8)
  }
  private fun json(value: Any): ResponseEntity<SecurityJson> = ResponseEntity.ok().header("Cache-Control", "no-store").body(SecurityJson(mapper.writeValueAsString(value)))
  private fun <T> parse(block: () -> T): T = try { block() } catch (_: Exception) { throw MalformedCeremony() }
  private class MalformedCeremony : RuntimeException()
  private class CeremonyTooLarge : RuntimeException()
}

/** Spring MVC diagnostics call toString; only the JSON encoder can reveal response secrets. */
class SecurityJson(@get:JsonValue @get:JsonRawValue val value: String) {
  override fun toString() = "SecurityJson(<redacted>)"
}
