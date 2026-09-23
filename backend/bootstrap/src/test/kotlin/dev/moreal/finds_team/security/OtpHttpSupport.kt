package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds_team.Application
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.*
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class OtpHttpSupport {
  protected lateinit var context: ConfigurableApplicationContext
  protected lateinit var tx: TransactionPort
  protected lateinit var mvc: MockMvc
  private lateinit var db: PostgreSQLContainer
  protected val json = JsonMapper.builder().build()
  protected val hashes get() = context.getBean(KeyedIdentityHashPort::class.java)
  protected var now: Instant
    get() = TestTime.now
    set(value) { TestTime.now = value }
  @BeforeAll fun startOtpServer() {
    db = PostgreSQLContainer("postgres:17-alpine").apply { start() }
    context = SpringApplicationBuilder(Application::class.java, TestTime::class.java).run(
      "--spring.profiles.active=test", "--server.port=0", "--finds.mail.recording=true",
      "--spring.datasource.url=${db.jdbcUrl}", "--spring.datasource.username=${db.username}",
      "--spring.datasource.password=${db.password}", "--spring.flyway.user=${db.username}",
      "--spring.flyway.password=${db.password}", "--finds.crawl.scan-interval=1h", "--finds.mail.scan-interval=1h",
      "--finds.security.cleanup-interval=1h",
    )
    tx = context.getBean(TransactionPort::class.java)
    mvc = MockMvcBuilders.webAppContextSetup(context as WebApplicationContext)
      .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
  }
  @AfterAll fun stopOtpServer() { context.close(); db.close() }
  @BeforeEach fun nextWindow() { now = now.plusSeconds(3600) }
  protected fun email() = EmailAddress("${UUID.randomUUID()}@example.test")
  protected fun postJson(path: String, body: String, session: MockHttpSession = MockHttpSession(), key: UUID = UUID.randomUUID(),
    ip: String = "127.0.0.1", correlation: UUID = UUID.randomUUID(), requestId: UUID = UUID.randomUUID()) = mvc.perform(post(path).secure(true).session(session).with(csrf())
    .with { it.remoteAddr = ip; it }.header("Idempotency-Key", key).header("X-Correlation-ID", correlation).header("X-Request-ID", requestId)
    .contentType("application/json").content(body))
  protected fun request(purpose: String, email: EmailAddress, session: MockHttpSession = MockHttpSession(),
    key: UUID = UUID.randomUUID(), correlation: UUID = UUID.randomUUID()) =
    postJson("/auth/$purpose/otp/request", json.writeValueAsString(mapOf("email" to email.value)), session, key, correlation = correlation)
  protected fun seed(active: Boolean): User = tx.execute {
    val user = User(UserId(UUID.randomUUID()), email())
    it.users.lockByEmail(user.email); it.users.save(user)
    if (!active) user else {
      val id = CredentialId(UUID.randomUUID().toString())
      it.credentials.insert(PasskeyCredential(user.id, PasskeyCredentialMaterial(id, byteArrayOf(1), 0, emptySet(), false, false), now))
      (user.registerCredential(id) as UserChange.Updated).user.also(it.users::save)
    }
  }
  protected fun challenge(email: EmailAddress, purpose: VerificationPurpose, code: String = "12345678") {
    tx.execute {
      it.users.lockByEmail(email)
      val old = it.otpChallenges.find(email, purpose) ?: OtpAccountState(email, purpose)
      val hashPurpose = if (purpose == VerificationPurpose.ENROLLMENT) IdentityHashPurpose.ENROLLMENT_OTP else IdentityHashPurpose.RECOVERY_OTP
      it.otpChallenges.save(old.copy(challenge = OtpChallenge(DeliveryRequestId(UUID.randomUUID()),
        hashes.hash(hashPurpose, email.normalized, code), now.plusSeconds(600)), lastIssuedAt = now))
    }
  }
  protected fun verify(purpose: String, email: EmailAddress, otp: String = "12345678", recovery: String? = null,
    session: MockHttpSession = MockHttpSession()) = postJson("/auth/$purpose/otp/verify",
    json.writeValueAsString(mapOf("email" to email.value, "otp" to otp, "recoveryCode" to recovery)), session)
  protected fun assertRestricted(session: MockHttpSession, scope: RestrictedSessionScope) {
    val id = session.getAttribute(WebAuthnCeremonies.RESTRICTED_SESSION) as RestrictedSessionId
    kotlin.test.assertEquals(scope, tx.execute { it.restrictedSessions.findById(id)!!.scope })
    mvc.perform(get("/auth/session").secure(true).session(session)).andExpect(status().isUnauthorized)
    mvc.perform(post("/graphql").secure(true).session(session).contentType("application/json").content("""{"query":"{ jobPostings { totalCount } }"}"""))
      .andExpect(status().isForbidden)
    mvc.perform(post("/admin/roles").secure(true).session(session).with(csrf())).andExpect(status().is4xxClientError)
    mvc.perform(post("/webauthn/register/options").secure(true).session(session)).andExpect(status().isForbidden)
    mvc.perform(post("/webauthn/register/options").secure(true).session(session).with(csrf())).andExpect(status().isOk)
  }
  @TestConfiguration(proxyBeanMethods = false)
  class TestTime {
    @Bean @Primary fun otpTestClock() = ClockPort { now }
    companion object { var now: Instant = Instant.parse("2026-09-23T00:00:00Z") }
  }
}
