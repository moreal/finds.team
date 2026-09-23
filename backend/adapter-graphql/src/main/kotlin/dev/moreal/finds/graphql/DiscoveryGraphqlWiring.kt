package dev.moreal.finds.graphql

import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.posting.*
import dev.moreal.finds.domain.search.Filter
import dev.moreal.finds.domain.search.normalize
import dev.moreal.finds.graphql.DiscoveryGraphqlMapping.connection
import dev.moreal.finds.graphql.DiscoveryGraphqlMapping.connectionResult
import graphql.ExecutionInput
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.RuntimeWiring
import org.dataloader.BatchLoader
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderOptions
import org.dataloader.DataLoaderRegistry
import java.util.concurrent.CompletableFuture

/** Created for each execution, including direct engine calls and HTTP/SSR requests. No shared cache. */
internal class DiscoveryLoaderInstrumentation(private val facade: FindsGraphqlFacade) : SimplePerformantInstrumentation() {
  override fun instrumentExecutionInput(input: ExecutionInput, parameters: InstrumentationExecutionParameters,
    state: InstrumentationState?): ExecutionInput = input.transform { it.dataLoaderRegistry(loaders()) }

  private fun loaders(): DataLoaderRegistry {
    val queries = facade.discoveryBatches
    return DataLoaderRegistry().apply {
      register(POSTING, loader<JobPostingId, JobPostingDto?> { ids ->
        queries?.findPostings(ids)?.map { it?.let(DiscoveryGraphqlMapping::posting) } ?: ids.map { null }
      })
      register(SITE, loader<CareerSiteId, CareerSiteDto?> { ids ->
        queries?.findSites(ids)?.map { it?.let(DiscoveryGraphqlMapping::site) } ?: ids.map { null }
      })
      register(POSTINGS, loader<PostingPageKey, DiscoveryConnectionDto<JobPostingDto>> { keys ->
        requireNotNull(queries).postingPages(keys).map { result -> result.fold(
          { connection(it, DiscoveryGraphqlMapping::posting) }, { DiscoveryGraphqlMapping.failure(it) },
        ) }
      })
      register(COMPANIES, loader<SkillPageKey, DiscoveryConnectionDto<CareerSiteDto>> { keys ->
        requireNotNull(queries).companyPages(keys).map { result -> result.fold(
          { connection(it, DiscoveryGraphqlMapping::site) }, { DiscoveryGraphqlMapping.failure(it) },
        ) }
      })
      register(RELATED, loader<SkillPageKey, DiscoveryConnectionDto<SkillDto>> { keys ->
        requireNotNull(queries).relatedSkillPages(keys).map { result -> result.fold(
          { connection(it, DiscoveryGraphqlMapping::skill) }, { DiscoveryGraphqlMapping.failure(it) },
        ) }
      })
      register(COUNTS, loader<String, SkillRequirementCountsDto> { slugs ->
        requireNotNull(queries).requirementCounts(slugs).map(DiscoveryGraphqlMapping::counts)
      })
    }
  }

  private fun <K : Any, V> loader(load: (List<K>) -> List<V>): DataLoader<K, V> = DataLoaderFactory.newDataLoader(
    BatchLoader<K, V> { keys ->
      try { CompletableFuture.completedFuture(load(keys)) }
      catch (error: Exception) { CompletableFuture.failedFuture(error) }
    }, DataLoaderOptions.newOptions().setMaxBatchSize(100).build(),
  )
}

internal fun RuntimeWiring.Builder.discovery(facade: FindsGraphqlFacade): RuntimeWiring.Builder =
  type("Query") { type ->
    type.dataFetcher("node") { env ->
      val id = GlobalIdCodec.decode(requireNotNull(env.getArgument("id")))
      when (id.type) {
        NodeType.JobPosting -> env.posting(id.value.toLong())
        NodeType.CareerSite -> env.site(id.value.toLong())
        NodeType.Skill -> SkillNodeIds.slug(id.value.toLong())?.let(::skillBySlug) ?: missingNode()
        NodeType.User, NodeType.CrawlRun, NodeType.AuditEvent -> missingNode()
      }
    }.dataFetcher("jobPosting") { env ->
      env.posting(GlobalIdCodec.decode(NodeType.JobPosting, requireNotNull(env.getArgument("id"))).toLong())
    }.dataFetcher("jobPostings") { env -> connectionResult {
      val input = env.getArgument<Map<String, Any?>>("filter")?.toPostingFilterInput()
      if (facade.discoveryBatches == null) {
        // Existing embedders may still supply the legacy application search handler.
        DiscoveryGraphqlMapping.page(env.getArgument("first"), env.getArgument("after"))
        facade.jobPostings(input, env.getArgument("first"), env.getArgument("after"))
      } else env.postings(PostingGraphqlMapping.filter(input))
    } }.dataFetcher("careerSite") { env ->
      requireNotNull(facade.discovery).findSiteBySlug(requireNotNull(env.getArgument("slug")))?.let(DiscoveryGraphqlMapping::site)
    }.dataFetcher("careerSites") { env -> connectionResult {
      connection(requireNotNull(facade.discovery).careerSites(env.page()), DiscoveryGraphqlMapping::site)
    } }.dataFetcher("skill") { env -> skillBySlug(requireNotNull(env.getArgument("slug"))) }
      .dataFetcher("skills") { env -> connectionResult {
        connection(requireNotNull(facade.discovery).skills(env.getArgument("query"), env.page()), DiscoveryGraphqlMapping::skill)
      } }
  }.type("JobPosting") { type ->
    type.dataFetcher("careerSite") { env ->
      env.site(GlobalIdCodec.decode(NodeType.CareerSite, requireNotNull(env.getSource<JobPostingDto>()).careerSiteId).toLong())
    }
  }.type("CareerSite") { type ->
    type.dataFetcher("openPostings") { env -> connectionResult {
      val id = GlobalIdCodec.decode(NodeType.CareerSite, requireNotNull(env.getSource<CareerSiteDto>()).id).toLong()
      env.postings(open(Filter.AtSite(CareerSiteId(id))))
    } }
  }.type("Skill") { type ->
    type.dataFetcher("openPostings") { env -> connectionResult {
      env.postings(open(Filter.HasSkill(env.skillSlug())))
    } }.dataFetcher("companies") { env -> connectionResult {
      env.getDataLoader<SkillPageKey, DiscoveryConnectionDto<CareerSiteDto>>(COMPANIES)!!.load(SkillPageKey(env.skillSlug(), env.page()))
    } }.dataFetcher("relatedSkills") { env -> connectionResult {
      env.getDataLoader<SkillPageKey, DiscoveryConnectionDto<SkillDto>>(RELATED)!!.load(SkillPageKey(env.skillSlug(), env.page()))
    } }.dataFetcher("requirementCounts") { env ->
      env.getDataLoader<String, SkillRequirementCountsDto>(COUNTS)!!.load(env.skillSlug())
    }
  }

private fun DataFetchingEnvironment.posting(id: Long) =
  getDataLoader<JobPostingId, JobPostingDto?>(POSTING)!!.load(JobPostingId(id)).thenApply { it ?: missingNode() }
private fun DataFetchingEnvironment.site(id: Long) =
  getDataLoader<CareerSiteId, CareerSiteDto?>(SITE)!!.load(CareerSiteId(id)).thenApply { it ?: missingNode() }
private fun DataFetchingEnvironment.postings(filter: Filter) =
  getDataLoader<PostingPageKey, DiscoveryConnectionDto<JobPostingDto>>(POSTINGS)!!.load(PostingPageKey(filter.normalize(), page()))
private fun DataFetchingEnvironment.page() = DiscoveryGraphqlMapping.page(getArgument("first"), getArgument("after"))
private fun DataFetchingEnvironment.skillSlug() = requireNotNull(getSource<SkillDto>()).slug
private fun open(filter: Filter) = Filter.And(listOf(filter, Filter.HasStatus(PostingStatus.OPEN)))
private fun missingNode(): Nothing = throw GraphqlRequestException(ApiErrorCode.NOT_FOUND, "Node is not available")
private fun skillBySlug(slug: String): SkillDto = try {
  DiscoveryGraphqlMapping.skill(SkillTaxonomy.V1.requireSkill(slug))
} catch (_: UnknownSkillException) { throw GraphqlRequestException(ApiErrorCode.UNKNOWN_SKILL, "Unknown canonical skill") }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.toPostingFilterInput(): PostingFilterInput = PostingFilterInput(
  atSite = this["atSite"] as String?, textContains = this["textContains"] as String?,
  hasStatus = (this["hasStatus"] as String?)?.let(PostingStatus::valueOf), updatedAfter = this["updatedAfter"] as String?,
  not = (this["not"] as Map<String, Any?>?)?.toPostingFilterInput(),
  all = (this["all"] as List<Map<String, Any?>>?)?.map { it.toPostingFilterInput() },
  any = (this["any"] as List<Map<String, Any?>>?)?.map { it.toPostingFilterInput() },
  hasSkill = (this["hasSkill"] as Map<String, Any?>?)?.let {
    SkillFilterInput(it["slug"] as String, (it["level"] as String?)?.let(SkillRequirementLevel::valueOf))
  },
  hasRole = (this["hasRole"] as String?)?.let(RoleCategory::valueOf),
  hasEmployment = (this["hasEmployment"] as String?)?.let(EmploymentType::valueOf),
  hasRemotePolicy = (this["hasRemotePolicy"] as String?)?.let(RemotePolicy::valueOf),
  atLocation = this["atLocation"] as String?,
)

private const val POSTING = "discovery.posting"
private const val SITE = "discovery.site"
private const val POSTINGS = "discovery.postings"
private const val COMPANIES = "discovery.companies"
private const val RELATED = "discovery.related"
private const val COUNTS = "discovery.counts"
