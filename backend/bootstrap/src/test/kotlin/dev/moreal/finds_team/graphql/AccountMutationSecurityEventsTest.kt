package dev.moreal.finds_team.graphql

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds.graphql.GlobalIdCodec
import dev.moreal.finds.graphql.NodeType
import dev.moreal.finds_team.security.*
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@Suppress("DEPRECATION")
@ExtendWith(OutputCaptureExtension::class)
class AccountMutationSecurityEventsTest : OtpHttpSupport() {
  private val sql get() = context.getBean(DSLContext::class.java)

  @ParameterizedTest
  @ValueSource(strings = ["renamePasskey", "removePasskey", "rotateRecoveryCode", "revokeSession", "revokeOtherSessions"])
  fun `commands bound to another account deny before reserving or changing state`(operation: String) {
    for (retry in listOf(false, true)) {
      val original = account()
      val current = account()
      val query = original.command(operation)
      if (retry) response(query, original.session)
      val before = listOf(businessState(original.user), businessState(current.user))
      val existing = eventIds()
      val result = response(query, current.session)
      assertNull(result["errors"], result.toString())
      assertEquals("ACCOUNT_MISMATCH", result["data"][operation]["error"]["code"].asText())
      assertEquals(before, listOf(businessState(original.user), businessState(current.user)))
      assertEquals(1, (eventIds() - existing).size)
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["renamePasskey", "removePasskey", "rotateRecoveryCode", "revokeSession", "revokeOtherSessions"])
  fun `anonymous account commands with valid CSRF append one categorical denial`(operation: String) {
    assertDenied(operation, anonymous = true)
  }

  @ParameterizedTest
  @ValueSource(strings = ["renamePasskey", "removePasskey", "rotateRecoveryCode", "revokeSession", "revokeOtherSessions"])
  fun `stale Passkey account commands append one denial after the business transaction exits`(operation: String) {
    assertDenied(operation, anonymous = false)
  }

  private fun assertDenied(operation: String, anonymous: Boolean) {
    val fixture = account()
    val before = businessState(fixture.user)
    val existing = eventIds()
    if (!anonymous) now = now.plusSeconds(301)
    val result = response(fixture.command(operation), if (anonymous) null else fixture.session)
    assertNull(result["errors"], result.toString())
    val payload = result["data"][operation]
    assertEquals("REJECTED", payload["outcome"].asText())
    assertEquals("FORBIDDEN", payload["error"]["code"].asText())
    assertEquals("denial-client", payload["clientMutationId"].asText())
    assertEquals(before, businessState(fixture.user), "Denials must not mutate accounts, audit success or reserve commands")
    val events = sql.fetch("select event_id, action, actor_user_id, target_type, target_id, details::text from security_events")
      .filter { it.get("event_id", UUID::class.java) !in existing }
    assertEquals(1, events.size, "Every denied account command must append exactly one security event")
    val event = events.single()
    assertEquals("identity.authorization_denied", event.get("action"))
    assertEquals("{\"reason\": \"FORBIDDEN\"}", event.get("details"))
    assertNull(event.get("actor_user_id"))
    assertNull(event.get("target_type"))
    assertNull(event.get("target_id"))
    val serialized = event.toString()
    for (privateValue in listOf(fixture.user.email.value, fixture.user.credentials.single().value, fixture.passkeyId, fixture.otherSessionId, PRIVATE_LABEL))
      assertFalse(serialized.contains(privateValue), "Caller-controlled account data must not enter a security event")
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `denial event write failures cannot grant account access or expose storage details`(anonymous: Boolean, output: CapturedOutput) {
    val fixture = account()
    val before = businessState(fixture.user)
    val existing = eventIds()
    if (!anonymous) now = now.plusSeconds(301)
    val attempts = AtomicInteger()
    sql.configuration().set(DefaultExecuteListenerProvider(object : ExecuteListener {
      override fun executeStart(ctx: ExecuteContext) {
        val statement = ctx.sql().orEmpty()
        if (statement.startsWith("insert", true) && statement.contains("\"security_events\"")) attempts.incrementAndGet()
      }
    }))
    sql.execute("alter table security_events add constraint task6_denial_write_failure check (false) not valid")
    try {
      val result = response(fixture.command("renamePasskey"), if (anonymous) null else fixture.session)
      assertTrue(result["data"].isNull)
      val errors = result["errors"].toList()
      assertEquals(1, errors.size)
      assertEquals("INTERNAL", errors.single()["extensions"]["code"].asText())
      assertEquals("Request failed", errors.single()["message"].asText())
      assertNotNull(UUID.fromString(errors.single()["extensions"]["correlationId"].asText()))
      assertEquals(1, attempts.get(), "A failed append is attempted once, outside the rejected business transaction")
      assertEquals(existing, eventIds())
      assertEquals(before, businessState(fixture.user))
      for (privateValue in listOf("task6_denial_write_failure", fixture.user.email.value, fixture.user.credentials.single().value,
        fixture.passkeyId, fixture.otherSessionId, PRIVATE_LABEL)) {
        assertFalse(result.toString().contains(privateValue))
        assertFalse(output.all.contains(privateValue))
      }
    } finally { sql.execute("alter table security_events drop constraint task6_denial_write_failure") }
  }

  @Test fun `successful account mutation and replay append no denial events`() {
    val fixture = account()
    val existing = eventIds()
    val key = UUID.randomUUID()
    repeat(2) {
      val result = response(fixture.command("renamePasskey", key), fixture.session)
      assertNull(result["errors"])
      assertEquals("CHANGED", result["data"]["renamePasskey"]["outcome"].asText())
    }
    assertEquals(existing, eventIds())
    assertEquals(PRIVATE_LABEL, tx.execute { it.credentials.findByUserId(fixture.user.id).single().label })
  }

  private data class Fixture(val user: User, val session: MockHttpSession, val passkeyId: String, val otherSessionId: String) {
    fun command(operation: String, key: UUID = UUID.randomUUID(), expectedUserId: String = GlobalIdCodec.encode(NodeType.User, user.id.value)): String {
      val argument = when (operation) {
        "renamePasskey" -> "passkeyId: \"$passkeyId\", label: \"$PRIVATE_LABEL\", "
        "removePasskey" -> "passkeyId: \"$passkeyId\", "
        "revokeSession" -> "sessionId: \"$otherSessionId\", "
        else -> ""
      }
      return """mutation { $operation(input: {expectedUserId: "$expectedUserId", ${argument}idempotencyKey: "$key", clientMutationId: "denial-client"}) { outcome clientMutationId error { code } } }"""
    }
  }
  private fun account(): Fixture {
    val user = seed(true)
    val current = UserSessionId(UUID.randomUUID())
    tx.execute { t ->
      t.users.lockByEmail(user.email)
      for (id in listOf(current, UserSessionId(UUID.randomUUID()))) t.userSessions.save(UserSession(id, user.id, now, now.plusSeconds(3600), now))
    }
    val session = MockHttpSession().apply { setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
      SecurityContextImpl(PasskeyAuthentication(PasskeyPrincipal(user.id, current)))) }
    val viewer = response("{ viewer { passkeys { edges { node { id } } } sessions { edges { node { id current } } } } }", session)["data"]["viewer"]
    return Fixture(user, session, viewer["passkeys"]["edges"][0]["node"]["id"].asText(),
      viewer["sessions"]["edges"].toList().single { !it["node"]["current"].asBoolean() }["node"]["id"].asText())
  }
  private fun eventIds() = sql.fetch("select event_id from security_events").map { it.get(0, UUID::class.java) }.toSet()
  private fun businessState(user: User): List<Any?> = listOf(
    sql.fetchValue("select count(*)::int from audit_events"), sql.fetchValue("select count(*)::int from command_requests"),
    tx.execute { it.credentials.findByUserId(user.id).map { credential -> credential.label }.sorted() },
    tx.execute { it.recoveryCodes.findByUserId(user.id)?.createdAt },
    tx.execute { it.userSessions.findByUserId(user.id).sortedBy { session -> session.id.value }.map { session -> session.revokedAt } },
  )
  private fun response(query: String, session: MockHttpSession? = null): tools.jackson.databind.JsonNode {
    val builder = post("/graphql").secure(true).with(csrf()).contentType("application/json").content(json.writeValueAsString(mapOf("query" to query)))
    session?.let(builder::session)
    val pending = mvc.perform(builder).andExpect(request().asyncStarted()).andReturn()
    return json.readTree(mvc.perform(asyncDispatch(pending)).andExpect(status().isOk).andReturn().response.contentAsString)
  }
  private companion object { const val PRIVATE_LABEL = "private-account-label@example.test" }
}
