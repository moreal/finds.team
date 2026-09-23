package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.*
import dev.moreal.finds.domain.identity.UserRole
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class SearchAuditEventsTest {
  private val now = Instant.parse("2026-09-23T00:00:00Z")
  @Test fun `anonymous system and ordinary users cannot read any audit history`() {
    val useCase = SearchAuditEvents { error("unauthorized audit query") }
    for (actor in listOf(null, Actor.System, user(setOf(UserRole.USER))))
      assertEquals(SearchAuditEventsResult.Forbidden, useCase.execute(actor, AuditSearch()))
  }
  @Test fun `administrator can read historical rows without a mutation step up`() {
    val query = AuditSearch(actorUserId = UUID.randomUUID(), limit = 2)
    val page = AuditPage(emptyList(), null)
    val useCase = SearchAuditEvents { actual -> assertEquals(query, actual); page }
    assertEquals(SearchAuditEventsResult.Found(page), useCase.execute(user(setOf(UserRole.ADMIN)), query))
  }
  @Test fun `search bounds reject unbounded and ambiguous requests`() {
    for (size in listOf(0, -1, 101)) assertFailsWith<IllegalArgumentException> { AuditSearch(limit = size) }
    assertFailsWith<IllegalArgumentException> { AuditSearch(from = now, until = now) }
    assertFailsWith<IllegalArgumentException> { AuditSearch(targetId = "42") }
  }
  @Test fun `maintenance bounds batches before accessing storage`() {
    var calls = 0
    val useCase = PurgeExpiredCommandRequests({ time, limit ->
      assertEquals(now, time); assertEquals(25, limit); calls++; 12
    }, ClockPort { now })
    for (limit in listOf(0, -1, 1001)) assertFailsWith<IllegalArgumentException> { useCase.execute(limit) }
    assertEquals(0, calls)
    assertEquals(12, useCase.execute(25))
    assertEquals(1, calls)
  }
  private fun user(roles: Set<UserRole>) = Actor.User(UUID.randomUUID(), roles, now.minusSeconds(3600), AuthenticationStrength.PASSKEY)
}
