package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.UserId
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.scheduling.annotation.Scheduled
import java.util.Base64

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties::class)
class SecurityConfiguration {
  // Suppress Boot's generated password/user fallback; normal authentication is ceremony-only.
  @Bean fun authenticationManager(): org.springframework.security.authentication.AuthenticationManager =
    org.springframework.security.authentication.AuthenticationManager { throw org.springframework.security.authentication.BadCredentialsException("Passkey required") }
  @Bean fun relyingPartySettings(properties: SecurityProperties, environment: Environment) = properties.validated(environment.activeProfiles.toSet())
  @Bean fun secureIdentityRandom(): SecureRandomPort = JvmSecureRandom()
  @Bean fun identityHashes(properties: SecurityProperties, settings: RelyingPartySettings, random: SecureRandomPort): KeyedIdentityHashPort {
    // settings has already rejected missing production keys. Local keys intentionally die on restart.
    val keys = properties.hashKeys.mapValues { Base64.getDecoder().decode(it.value) }
      .ifEmpty { mapOf(properties.activeHashVersion to random.bytes(32)) }
    return try { VersionedIdentityHashes(properties.activeHashVersion, keys, properties.commandScopeHashVersion) } finally { keys.values.forEach { it.fill(0) } }
  }
  @Bean fun initialRolePolicy(properties: SecurityProperties): InitialRolePolicyPort = ConfiguredInitialRolePolicy(properties.adminEmails)
  @Bean fun actorResolver(transactions: TransactionPort, clock: ClockPort) = ActorResolver(transactions, clock)
  @Bean fun webAuthnCeremonies(transactions: TransactionPort, clock: ClockPort, random: SecureRandomPort,
    hashes: KeyedIdentityHashPort, settings: RelyingPartySettings, roles: InitialRolePolicyPort, actors: ActorResolver) =
    WebAuthnCeremonies(transactions, clock, random, hashes, settings, roles, actors)
  @Bean fun restrictedSessionCleanup(transactions: TransactionPort, clock: ClockPort) = RestrictedSessionCleanup(transactions, clock)

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  fun securityFilterChain(http: HttpSecurity, actors: ActorResolver, transactions: TransactionPort, clock: ClockPort): SecurityFilterChain {
    http.formLogin { it.disable() }.httpBasic { it.disable() }.requestCache { it.disable() }
      .securityContext { it.securityContextRepository(HttpSessionSecurityContextRepository()) }
      // GraphQL is query-only until audited command adapters land; its controller rejects every mutation.
      .csrf { it.csrfTokenRepository(HttpSessionCsrfTokenRepository()).ignoringRequestMatchers("/graphql") }
      .authorizeHttpRequests { rules ->
        rules.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
          .requestMatchers(HttpMethod.GET, "/auth/csrf", "/auth/session", "/actuator/health", "/actuator/health/**").permitAll()
          .requestMatchers(HttpMethod.POST, "/graphql").access { _, context ->
            AuthorizationDecision(context.request.getSession(false)?.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION) == null)
          }
          .requestMatchers(HttpMethod.POST, "/webauthn/authenticate/options", "/login/webauthn",
            "/webauthn/register/options", "/webauthn/register", "/auth/enrollment/otp/request", "/auth/enrollment/otp/verify",
            "/auth/recovery/otp/request", "/auth/recovery/otp/verify").permitAll()
          .anyRequest().denyAll()
      }
      .exceptionHandling { errors ->
        errors.authenticationEntryPoint { request, response, _ -> securityProblem(response,
          if (request.requestURI == request.contextPath + "/graphql" && request.getSession(false)?.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION) != null) 403 else 401) }
        errors.accessDeniedHandler { _, response, _ -> securityProblem(response, 403) }
      }
      .logout { logout ->
        logout.logoutUrl("/auth/logout").addLogoutHandler { _, _, authentication ->
          val principal = actors.sessionPrincipal(authentication)
          if (principal != null) transactions.execute { tx ->
            val user = tx.users.findById(UserId(principal.actor.userId)) ?: return@execute
            tx.users.lockByEmail(user.email)
            tx.userSessions.revoke(principal.sessionId, clock.now())
          }
        }.deleteCookies("JSESSIONID").logoutSuccessHandler { _, response, _ -> response.status = 204 }
      }
    return http.build()
  }
}

class RestrictedSessionCleanup(private val transactions: TransactionPort, private val clock: ClockPort) {
  @Scheduled(fixedDelayString = "\${finds.security.cleanup-interval:1m}")
  fun purge(): Int = transactions.execute {
    val now = clock.now()
    val restricted = it.restrictedSessions.purgeExpired(now, 100)
    it.webauthnChallenges.purgeExpired(now, 100)
    restricted
  }
}

internal fun securityProblem(response: HttpServletResponse, status: Int) {
  response.status = status
  response.contentType = "application/problem+json"
  response.setHeader("Cache-Control", "no-store")
  response.writer.write("""{"type":"about:blank","title":"Request rejected","status":$status}""")
}
