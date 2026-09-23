package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.domain.identity.UserRole

sealed interface SearchAuditEventsResult {
  data object Forbidden : SearchAuditEventsResult
  data class Found(val page: AuditPage) : SearchAuditEventsResult
}

class SearchAuditEvents(private val queries: AuditQueryPort) {
  fun execute(actor: Actor?, query: AuditSearch): SearchAuditEventsResult {
    if (actor !is Actor.User || UserRole.ADMIN !in actor.roles) return SearchAuditEventsResult.Forbidden
    return SearchAuditEventsResult.Found(queries.search(query))
  }
}
