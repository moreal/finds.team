package dev.moreal.finds.graphql

import dev.moreal.finds.application.audit.*
import dev.moreal.finds.application.model.*
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.usecase.*
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.crawl.CrawlOutcome
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.RuntimeWiring
import org.dataloader.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class CrawlSummaryDto(val outcome: CrawlOutcome?, val finishedAt: String?)
data class CrawlRunDto(val id: String, val careerSiteId: String, val startedAt: String, val finishedAt: String?,
  val outcome: CrawlOutcome?, val counts: CrawlChangeCounts, val error: ApiErrorDto?)
data class AuditDetailsDto(val role: String?, val provider: String?, val enabled: Boolean?)
data class AuditEventDto(val id: String, val occurredAt: String, val actorKind: AuditActorKind, val actorUserId: String?,
  val action: AuditAction, val targetType: String, val targetId: String, val outcome: AuditOutcome, val details: AuditDetailsDto)

internal object AdminGraphqlMapping {
  fun run(r: CrawlRunView) = CrawlRunDto(GlobalIdCodec.encode(NodeType.CrawlRun, r.id.value),
    GlobalIdCodec.encode(NodeType.CareerSite, r.siteId.value), r.startedAt.toString(), r.finishedAt?.toString(), r.outcome, r.counts,
    if (r.outcome == CrawlOutcome.FAILED) ApiErrorDto(ApiErrorCode.CRAWL_FAILED, "Crawl failed") else null)
  fun status(r: CrawlStatus) = CrawlStatusDto(GlobalIdCodec.encode(NodeType.CareerSite, r.careerSiteId.value),
    r.runId?.let { GlobalIdCodec.encode(NodeType.CrawlRun, it.value) }, r.outcome, r.finishedAt?.toString(),
    if (r.outcome == CrawlOutcome.FAILED) ApiErrorDto(ApiErrorCode.CRAWL_FAILED, "Crawl failed") else null)
  fun audit(r: AuditRecord): AuditEventDto {
    // Revalidate the allowlist even if an alternate query adapter provided this record.
    val details = AuditDetails.from(r.action, r.details.fields).fields
    return AuditEventDto(GlobalIdCodec.encode(NodeType.AuditEvent, r.id), r.occurredAt.toString(), r.actorKind,
      r.actorUserId?.let { GlobalIdCodec.encode(NodeType.User, it) }, r.action, r.targetType, r.targetId, r.outcome,
      AuditDetailsDto(details["role"], details["provider"], details["enabled"]?.toBooleanStrict()))
  }
}

internal fun <K : Any, V> requestLoader(load: (List<K>) -> List<V>): DataLoader<K, V> = DataLoaderFactory.newDataLoader(
  BatchLoader<K, V> { keys -> try { CompletableFuture.completedFuture(load(keys)) }
    catch (error: Exception) { CompletableFuture.failedFuture(error) } }, DataLoaderOptions.newOptions().setMaxBatchSize(100).build())

internal fun DataLoaderRegistry.operationsLoaders(facade: FindsGraphqlFacade, principal: SessionPrincipal?): DataLoaderRegistry = apply {
  register(SUMMARIES, requestLoader<CareerSiteId, CrawlSummaryDto?> { ids ->
    requireNotNull(facade.operations).summaries(ids).map { it?.let { r -> CrawlSummaryDto(r.outcome, r.finishedAt?.toString()) } }
  })
  register(HISTORY, requestLoader<CrawlHistoryKey, DiscoveryConnectionDto<CrawlRunDto>> { keys ->
    requireNotNull(facade.operations).history(principal?.actor, keys).map { result -> result.fold(
      { DiscoveryGraphqlMapping.connection(it, AdminGraphqlMapping::run) }, DiscoveryGraphqlMapping::failure) }
  })
  register(STATUSES, requestLoader<ConnectionRequest, DiscoveryConnectionDto<CrawlStatusDto>> { keys ->
    requireNotNull(facade.operations).statuses(principal?.actor, keys).map { result -> result.fold(
      { DiscoveryGraphqlMapping.connection(it, AdminGraphqlMapping::status) }, DiscoveryGraphqlMapping::failure) }
  })
  register(RUNS, requestLoader<CrawlRunId, CrawlRunDto?> { ids ->
    requireNotNull(facade.operations).runs(principal?.actor, ids).map { it?.let(AdminGraphqlMapping::run) }
  })
  register(AUDIT, requestLoader<AuditPageKey, DiscoveryConnectionDto<AuditEventDto>> { keys ->
    requireNotNull(facade.operations).audit(principal?.actor, keys).map { result -> result.fold(
      { DiscoveryGraphqlMapping.connection(it, AdminGraphqlMapping::audit) }, DiscoveryGraphqlMapping::failure) }
  })
  register(EVENTS, requestLoader<UUID, AuditEventDto?> { ids ->
    requireNotNull(facade.operations).events(principal?.actor, ids).map { it?.let(AdminGraphqlMapping::audit) }
  })
}

internal fun RuntimeWiring.Builder.operations(): RuntimeWiring.Builder = type("Query") { type ->
  type.dataFetcher("crawlStatuses") { env ->
    env.administrator()
    DiscoveryGraphqlMapping.connectionResult {
      env.getDataLoader<ConnectionRequest, DiscoveryConnectionDto<CrawlStatusDto>>(STATUSES)!!.load(env.operationPage())
    }
  }.dataFetcher("auditEvents") { env ->
    env.administrator()
    DiscoveryGraphqlMapping.connectionResult {
      env.getDataLoader<AuditPageKey, DiscoveryConnectionDto<AuditEventDto>>(AUDIT)!!.load(AuditPageKey(env.auditFilter(), env.operationPage()))
    }
  }
}.type("CareerSite") { type ->
  type.dataFetcher("crawlSummary") { env -> env.getDataLoader<CareerSiteId, CrawlSummaryDto?>(SUMMARIES)!!.load(env.siteId()) }
    .dataFetcher("crawlHistory") { env ->
      env.administrator()
      DiscoveryGraphqlMapping.connectionResult {
        env.getDataLoader<CrawlHistoryKey, DiscoveryConnectionDto<CrawlRunDto>>(HISTORY)!!.load(CrawlHistoryKey(env.siteId(), env.operationPage()))
      }
    }
}
internal fun DataFetchingEnvironment.crawlRun(id: Long): CompletableFuture<CrawlRunDto> {
  administrator()
  return getDataLoader<CrawlRunId, CrawlRunDto?>(RUNS)!!.load(CrawlRunId(id)).thenApply { it ?: missingOperationsNode() }
}
internal fun DataFetchingEnvironment.auditEvent(id: UUID): CompletableFuture<AuditEventDto> {
  administrator()
  return getDataLoader<UUID, AuditEventDto?>(EVENTS)!!.load(id).thenApply { it ?: missingOperationsNode() }
}
private fun DataFetchingEnvironment.administrator() = OperationsQueries.requireAdministrator(principal()?.actor)
private fun DataFetchingEnvironment.siteId() = CareerSiteId(GlobalIdCodec.decode(NodeType.CareerSite, getSource<CareerSiteDto>()!!.id).toLong())
private fun DataFetchingEnvironment.operationPage() = DiscoveryGraphqlMapping.page(getArgument("first"), getArgument("after"))
private fun missingOperationsNode(): Nothing = throw GraphqlRequestException(ApiErrorCode.NOT_FOUND, "Node is not available")
private fun DataFetchingEnvironment.auditFilter(): AuditSearch {
  val f = getArgument<Map<String, String?>>("filter").orEmpty()
  try {
    fun timestamp(name: String) = f[name]?.let { Instant.parse(it).also { value -> require(DiscoveryTimestamp.supports(value)) } }
    return AuditSearch(actorUserId = f["actorUserId"]?.let { UUID.fromString(GlobalIdCodec.decode(NodeType.User, it)) },
      actorKind = f["actorKind"]?.let(AuditActorKind::valueOf), action = f["action"]?.let(AuditAction::valueOf),
      targetType = f["targetType"], targetId = f["targetId"], from = timestamp("from"), until = timestamp("until"))
  } catch (_: IllegalArgumentException) { throw GraphqlRequestException(ApiErrorCode.INVALID_FILTER, "Invalid audit filter") }
    catch (_: java.time.DateTimeException) { throw GraphqlRequestException(ApiErrorCode.INVALID_FILTER, "Invalid audit filter") }
}
private const val SUMMARIES = "operations.summaries"
private const val HISTORY = "operations.history"
private const val STATUSES = "operations.statuses"
private const val RUNS = "operations.runs"
private const val AUDIT = "operations.audit"
private const val EVENTS = "operations.events"
