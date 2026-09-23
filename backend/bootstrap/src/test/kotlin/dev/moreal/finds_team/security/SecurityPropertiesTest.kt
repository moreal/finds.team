package dev.moreal.finds_team.security

import dev.moreal.finds.domain.identity.EmailAddress
import dev.moreal.finds.domain.identity.UserRole
import kotlin.test.*
import org.junit.jupiter.api.Test

class SecurityPropertiesTest {
  @Test fun `production requires explicit HTTPS origin and valid matching RP`() {
    assertFailsWith<IllegalArgumentException> { SecurityProperties().validated(emptySet()) }
    listOf("http://finds.team", "https://evil.example", "https://finds.team/path", "https://user@finds.team",
      "https://finds.team?x", "https://finds.team#x", "https://finds.team:0").forEach { origin ->
      assertFailsWith<IllegalArgumentException>(origin) { configured("finds.team", setOf(origin)).validated(emptySet()) }
    }
    listOf("https://finds.team", "finds.team:443", "TEAM", "team", "localhost", "127.0.0.1", "finds.team.").forEach { rp ->
      assertFailsWith<IllegalArgumentException>(rp) { configured(rp, setOf("https://finds.team")).validated(emptySet()) }
    }
    configured("finds.team", setOf("https://finds.team", "https://app.finds.team")).validated(emptySet())
    assertFailsWith<IllegalArgumentException> { configured("finds.team", emptySet()).validated(emptySet()) }
  }
  @Test fun `local setup still requires HTTPS and never weakens production with mixed profiles`() {
    assertEquals("localhost", SecurityProperties().validated(setOf("test")).rpId)
    assertEquals(setOf("https://localhost:8443"), SecurityProperties().validated(setOf("dev")).allowedOrigins)
    assertFailsWith<IllegalArgumentException> { SecurityProperties().validated(setOf("dev", "prod")) }
    assertFailsWith<IllegalArgumentException> { configured("localhost", setOf("http://localhost:8080")).validated(setOf("dev")) }
  }
  @Test fun `initial administrator policy uses exact normalized email equality`() {
    val policy = ConfiguredInitialRolePolicy(setOf("Admin@Example.test"))
    assertEquals(setOf(UserRole.USER, UserRole.ADMIN), policy.rolesForVerifiedEmail(EmailAddress("admin@EXAMPLE.test")))
    listOf("other@example.test", "admin+tag@example.test", "admin@sub.example.test", "ad.min@example.test").forEach {
      assertEquals(setOf(UserRole.USER), policy.rolesForVerifiedEmail(EmailAddress(it)))
    }
    assertFailsWith<IllegalArgumentException> { ConfiguredInitialRolePolicy(setOf("*@example.test")) }
  }
  private fun configured(rp: String, origins: Set<String>) = SecurityProperties(rpId = rp, allowedOrigins = origins,
    hashKeys = mapOf(1 to "dGVzdC1rZXktMzItYnl0ZXMtZXhhY3RseS0xMjM0NTY="))
}
