package dev.moreal.finds.graphql

import dev.moreal.finds.application.model.CrawlStatus
import dev.moreal.finds.application.model.PageRequest
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.*
import java.time.Instant
import dev.moreal.finds.application.usecase.SessionPrincipal
import java.util.UUID
import dev.moreal.finds.application.usecase.CrawlSite
import dev.moreal.finds.application.usecase.CrawlSiteCommand
import dev.moreal.finds.application.usecase.CrawlSiteResult
import dev.moreal.finds.application.usecase.CrawlTrigger
import dev.moreal.finds.application.usecase.GetCrawlStatus
import dev.moreal.finds.application.usecase.RegisterCareerSite
import dev.moreal.finds.application.usecase.RegisterCareerSiteCommand
import dev.moreal.finds.application.usecase.RegisterCareerSiteResult
import dev.moreal.finds.application.usecase.SearchPostings
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.search.Filter

enum class ApiErrorCode {
  INVALID_INPUT, INVALID_URL, UNSUPPORTED_PROVIDER, AMBIGUOUS_PROVIDER,
  DISCOVERY_FAILED, ALREADY_REGISTERED, NOT_FOUND, NOT_DUE, DISABLED, BUSY,
  CRAWL_FAILED, INTERNAL, FORBIDDEN, IDEMPOTENCY_CONFLICT,
  INVALID_FILTER, UNKNOWN_SKILL, INVALID_CURSOR, INVALID_PAGE, LAST_CREDENTIAL,
}

data class ApiErrorDto(
  val code: ApiErrorCode,
  val message: String,
  val providers: List<SourceProvider> = emptyList(),
)

data class CareerSiteDto(
  val id: String,
  val canonicalBaseUrl: String,
  val host: String,
  val provider: SourceProvider,
  val displayName: String,
  val enabled: Boolean,
  val successfulIntervalSeconds: Int,
  val slug: String? = null,
)

data class RegisterCareerSiteInput(val url: String, val displayName: String, val idempotencyKey: String, val clientMutationId: String? = null)
data class RegisterCareerSitePayload(val site: CareerSiteDto?, val error: ApiErrorDto?, val clientMutationId: String? = null)

enum class CrawlTriggerOutcome {
  TRIGGERED, FORBIDDEN, INVALID_INPUT, IDEMPOTENCY_CONFLICT,
  SUCCEEDED, FAILED, NOT_FOUND, NOT_DUE, DISABLED, BUSY, INFRASTRUCTURE_FAILURE,
}

data class TriggerCrawlPayload(
  val outcome: CrawlTriggerOutcome,
  val runId: String? = null,
  val counts: dev.moreal.finds.application.model.CrawlChangeCounts? = null,
  val nextEligibleAt: String? = null,
  val error: ApiErrorDto? = null,
  val clientMutationId: String? = null,
)

data class CrawlStatusDto(
  val careerSiteId: String,
  val runId: String?,
  val outcome: dev.moreal.finds.domain.crawl.CrawlOutcome?,
  val finishedAt: String?,
  val error: ApiErrorDto?,
)

class FindsGraphqlFacade(
  private val searchHandler: (Filter, PageRequest) -> SearchPage,
  private val registerHandler: suspend (RegisterCareerSiteCommand) -> RegisterCareerSiteResult,
  private val crawlHandler: suspend (CrawlSiteCommand) -> CrawlSiteResult,
  private val statusHandler: () -> List<CrawlStatus>,
  private val securityEvents: SecurityEventPort,
  private val clock: ClockPort = ClockPort(Instant::now),
  val discovery: DiscoveryQueryPort? = null,
  val discoveryBatches: DiscoveryBatchQueryPort? = null,
  val accounts: dev.moreal.finds.application.usecase.AccountManagement? = null,
  val operations: dev.moreal.finds.application.usecase.OperationsQueries? = null,
) {
  constructor(
    search: SearchPostings,
    register: RegisterCareerSite,
    crawl: CrawlSite,
    statuses: GetCrawlStatus,
    securityEvents: SecurityEventPort,
    clock: ClockPort = ClockPort(Instant::now),
    discovery: DiscoveryQueryPort? = null,
    discoveryBatches: DiscoveryBatchQueryPort? = null,
    accounts: dev.moreal.finds.application.usecase.AccountManagement? = null,
    operations: dev.moreal.finds.application.usecase.OperationsQueries? = null,
  ) : this(search::execute, register::execute, crawl::execute, statuses::execute, securityEvents, clock, discovery, discoveryBatches, accounts, operations)

  fun now(): Instant = clock.now()

  fun jobPostings(filter: PostingFilterInput?, first: Int?, after: String?): JobPostingConnectionDto =
    PostingGraphqlMapping.connection(
      searchHandler(PostingGraphqlMapping.filter(filter), PostingGraphqlMapping.page(first, after)),
    )

  suspend fun registerCareerSite(input: RegisterCareerSiteInput, principal: SessionPrincipal? = null): RegisterCareerSitePayload {
    if (principal == null) {
      securityEvents.denied(SecurityEventAction.REGISTRATION_DENIED, clock.now(), anonymousMetadata())
      return errorPayload(ApiErrorCode.FORBIDDEN, "Registration forbidden")
    }
    val metadata = try {
      CommandMetadata.parse(UUID.randomUUID().toString(), UUID.randomUUID().toString(), input.idempotencyKey)
    } catch (_: IllegalArgumentException) {
      return errorPayload(ApiErrorCode.INVALID_INPUT, "Idempotency key must be a UUID")
    }
    return when (val result = registerHandler(RegisterCareerSiteCommand(input.url, input.displayName,
      principal.actor, metadata, principal.sessionId))) {
      RegisterCareerSiteResult.Forbidden -> errorPayload(ApiErrorCode.FORBIDDEN, "Registration forbidden")
      RegisterCareerSiteResult.IdempotencyConflict -> errorPayload(ApiErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency key was used for another request")
      RegisterCareerSiteResult.InvalidIdempotencyKey -> errorPayload(ApiErrorCode.INVALID_INPUT, "Idempotency key must be a UUID")
      is RegisterCareerSiteResult.Registered -> RegisterCareerSitePayload(result.site.toDto(), null)
      is RegisterCareerSiteResult.AlreadyRegistered -> RegisterCareerSitePayload(
        result.site.toDto(), ApiErrorDto(ApiErrorCode.ALREADY_REGISTERED, "Career site already registered"),
      )
      is RegisterCareerSiteResult.InvalidUrl -> errorPayload(ApiErrorCode.INVALID_URL, "Invalid career-site URL")
      is RegisterCareerSiteResult.InvalidDisplayName -> errorPayload(ApiErrorCode.INVALID_INPUT, "Invalid display name")
      RegisterCareerSiteResult.UnsupportedProvider -> errorPayload(
        ApiErrorCode.UNSUPPORTED_PROVIDER, "Unsupported career-site provider",
      )
      is RegisterCareerSiteResult.AmbiguousProvider -> RegisterCareerSitePayload(
        null,
        ApiErrorDto(ApiErrorCode.AMBIGUOUS_PROVIDER, "Multiple providers matched", result.providers.sortedBy { it.name }),
      )
      is RegisterCareerSiteResult.DiscoveryFailed -> errorPayload(ApiErrorCode.DISCOVERY_FAILED, "Career-site discovery failed")
    }
  }

  suspend fun triggerCrawl(careerSiteId: String, idempotencyKey: String, principal: SessionPrincipal? = null): TriggerCrawlPayload {
    if (principal == null) {
      securityEvents.denied(SecurityEventAction.CRAWL_DENIED, clock.now(), anonymousMetadata())
      return simpleCrawlError(CrawlTriggerOutcome.FORBIDDEN, ApiErrorCode.FORBIDDEN)
    }
    val metadata = try {
      CommandMetadata.parse(UUID.randomUUID().toString(), UUID.randomUUID().toString(), idempotencyKey)
    } catch (_: IllegalArgumentException) {
      return simpleCrawlError(CrawlTriggerOutcome.INVALID_INPUT, ApiErrorCode.INVALID_INPUT)
    }
    val id = try {
      GlobalIdCodec.decode(NodeType.CareerSite, careerSiteId).toLong()
    } catch (_: GlobalIdException) {
      return TriggerCrawlPayload(
        CrawlTriggerOutcome.INVALID_INPUT,
        error = ApiErrorDto(ApiErrorCode.INVALID_INPUT, "Invalid global ID"),
      )
    }
    return when (val result = crawlHandler(CrawlSiteCommand(CareerSiteId(id), CrawlTrigger.MANUAL,
      principal.actor, metadata, principal.sessionId))) {
      is CrawlSiteResult.Triggered -> TriggerCrawlPayload(CrawlTriggerOutcome.TRIGGERED, GlobalIdCodec.encode(NodeType.CrawlRun, result.runId.value))
      CrawlSiteResult.Forbidden -> simpleCrawlError(CrawlTriggerOutcome.FORBIDDEN, ApiErrorCode.FORBIDDEN)
      CrawlSiteResult.InvalidIdempotencyKey -> simpleCrawlError(CrawlTriggerOutcome.INVALID_INPUT, ApiErrorCode.INVALID_INPUT)
      CrawlSiteResult.IdempotencyConflict -> simpleCrawlError(CrawlTriggerOutcome.IDEMPOTENCY_CONFLICT, ApiErrorCode.IDEMPOTENCY_CONFLICT)
      is CrawlSiteResult.Succeeded -> TriggerCrawlPayload(
        CrawlTriggerOutcome.SUCCEEDED, GlobalIdCodec.encode(NodeType.CrawlRun, result.runId.value), result.counts,
      )
      is CrawlSiteResult.Failed -> TriggerCrawlPayload(
        CrawlTriggerOutcome.FAILED, GlobalIdCodec.encode(NodeType.CrawlRun, result.runId.value),
        error = ApiErrorDto(ApiErrorCode.CRAWL_FAILED, "Crawl failed"),
      )
      CrawlSiteResult.NotFound -> simpleCrawlError(CrawlTriggerOutcome.NOT_FOUND, ApiErrorCode.NOT_FOUND)
      is CrawlSiteResult.NotDue -> TriggerCrawlPayload(
        CrawlTriggerOutcome.NOT_DUE, nextEligibleAt = result.nextEligibleAt.toString(),
        error = ApiErrorDto(ApiErrorCode.NOT_DUE, "Career site is not due"),
      )
      CrawlSiteResult.Disabled -> simpleCrawlError(CrawlTriggerOutcome.DISABLED, ApiErrorCode.DISABLED)
      CrawlSiteResult.Busy -> simpleCrawlError(CrawlTriggerOutcome.BUSY, ApiErrorCode.BUSY)
      is CrawlSiteResult.InfrastructureFailure -> TriggerCrawlPayload(
        CrawlTriggerOutcome.INFRASTRUCTURE_FAILURE,
        error = ApiErrorDto(ApiErrorCode.INTERNAL, "Crawl request failed"),
      )
    }
  }

  fun crawlStatuses(): List<CrawlStatusDto> = statusHandler().map { status ->
    CrawlStatusDto(
      GlobalIdCodec.encode(NodeType.CareerSite, status.careerSiteId.value),
      status.runId?.let { GlobalIdCodec.encode(NodeType.CrawlRun, it.value) }, status.outcome,
      status.finishedAt?.toString(), status.failure?.let {
        ApiErrorDto(ApiErrorCode.CRAWL_FAILED, "Crawl failed")
      },
    )
  }

  private fun anonymousMetadata() = CommandMetadata(UUID.randomUUID(), UUID.randomUUID())

  private fun CareerSite.toDto() = CareerSiteDto(
    GlobalIdCodec.encode(NodeType.CareerSite, id.value), canonicalBaseUrl.value.toString(), canonicalBaseUrl.host.value,
    provider, displayName, crawlSettings.enabled,
    Math.toIntExact(crawlSettings.successfulInterval.seconds), slug,
  )

  private fun errorPayload(code: ApiErrorCode, message: String) =
    RegisterCareerSitePayload(null, ApiErrorDto(code, message))

  private fun simpleCrawlError(outcome: CrawlTriggerOutcome, code: ApiErrorCode) =
    TriggerCrawlPayload(outcome, error = ApiErrorDto(code, code.name.lowercase().replace('_', ' ')))
}
