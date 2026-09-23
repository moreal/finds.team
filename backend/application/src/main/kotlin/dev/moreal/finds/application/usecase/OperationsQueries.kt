package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.*
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.identity.UserRole
import java.util.UUID

class QueryForbidden : RuntimeException("Access forbidden")

/** Actor is resolved by a trusted boundary once per request, never reconstructed from arguments. */
class OperationsQueries(private val queries: OperationsQueryPort) {
  companion object {
    fun requireAdministrator(actor: Actor?) {
      if (actor !is Actor.User || UserRole.ADMIN !in actor.roles) throw QueryForbidden()
    }
  }
  fun summaries(ids: List<CareerSiteId>) = queries.crawlSummaries(ids)
  fun history(actor: Actor?, keys: List<CrawlHistoryKey>) = authorized(actor) { queries.crawlHistory(keys) }
  fun statuses(actor: Actor?, pages: List<ConnectionRequest>) = authorized(actor) { queries.crawlStatuses(pages) }
  fun runs(actor: Actor?, ids: List<CrawlRunId>) = authorized(actor) { queries.crawlRuns(ids) }
  fun audit(actor: Actor?, keys: List<AuditPageKey>) = authorized(actor) { queries.auditPages(keys) }
  fun events(actor: Actor?, ids: List<UUID>) = authorized(actor) { queries.auditEvents(ids) }
  private inline fun <T> authorized(actor: Actor?, block: () -> T): T { requireAdministrator(actor); return block() }
}
