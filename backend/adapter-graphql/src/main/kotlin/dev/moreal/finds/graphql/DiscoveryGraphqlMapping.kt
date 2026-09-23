package dev.moreal.finds.graphql

import dev.moreal.finds.application.model.*
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.posting.*
import dev.moreal.finds.graphql.PostingGraphqlMapping.toDto

data class DiscoveryEdgeDto<T>(val cursor: String, val node: T)
data class DiscoveryConnectionDto<T>(
  val edges: List<DiscoveryEdgeDto<T>>,
  val pageInfo: PageInfoDto,
  val totalCount: Int,
  val error: ApiErrorDto? = null,
)
data class SkillDto(val id: String, val slug: String, val displayName: String)
data class PostingSkillDto(val skill: SkillDto?, val text: String, val level: SkillRequirementLevel)
data class PostingClassificationDto(
  val taxonomyVersion: Int,
  val skills: List<PostingSkillDto>,
  val role: ClassifiedValue<RoleCategory>,
  val employment: ClassifiedValue<EmploymentType>,
  val remote: ClassifiedValue<RemotePolicy>,
  val location: NormalizedLocation?,
)
data class SkillRequirementCountsDto(val required: Int, val preferred: Int, val mentioned: Int)

object DiscoveryGraphqlMapping {
  fun page(first: Int?, after: String?) = ConnectionRequest(first ?: 20, after?.let(::ApplicationCursor))

  fun site(site: CareerSite) = CareerSiteDto(
    GlobalIdCodec.encode(NodeType.CareerSite, site.id.value), site.canonicalBaseUrl.value.toString(),
    site.canonicalBaseUrl.host.value, site.provider, site.displayName, site.crawlSettings.enabled,
    Math.toIntExact(site.crawlSettings.successfulInterval.seconds), site.slug,
  )
  fun posting(posting: JobPosting) = posting.toDto()
  fun skill(skill: SkillDefinition) = SkillDto(
    GlobalIdCodec.encode(NodeType.Skill, SkillNodeIds.id(skill.slug)), skill.slug, skill.displayName,
  )
  fun classification(value: PostingClassification) = PostingClassificationDto(
    value.taxonomyVersion, value.skills.map { mention -> PostingSkillDto(
      mention.slug?.let { skill(SkillTaxonomy.V1.requireSkill(it)) }, mention.text, mention.level,
    ) }, value.role, value.employment, value.remote, value.location,
  )
  fun counts(value: SkillRequirementCounts) = SkillRequirementCountsDto(
    Math.toIntExact(value.required), Math.toIntExact(value.preferred), Math.toIntExact(value.mentioned),
  )
  fun <T, U> connection(page: ConnectionPage<T>, node: (T) -> U) = DiscoveryConnectionDto(
    page.edges.map { DiscoveryEdgeDto(it.cursor.value, node(it.node)) },
    PageInfoDto(page.pageInfo.hasNextPage, page.pageInfo.endCursor?.value,
      page.pageInfo.hasPreviousPage, page.pageInfo.startCursor?.value), Math.toIntExact(page.totalCount),
  )
  fun <T> failure(error: Throwable): DiscoveryConnectionDto<T> = DiscoveryConnectionDto(
    emptyList(), PageInfoDto(false, null), 0, error(error),
  )
  fun error(error: Throwable): ApiErrorDto = when (error) {
    is InvalidConnectionCursor -> ApiErrorDto(ApiErrorCode.INVALID_CURSOR, "Invalid connection cursor")
    is InvalidConnectionRequest -> ApiErrorDto(ApiErrorCode.INVALID_PAGE, "First must be between 1 and 100")
    is UnknownSkillException -> ApiErrorDto(ApiErrorCode.UNKNOWN_SKILL, "Unknown canonical skill")
    is GraphqlRequestException -> if (error.code in setOf(ApiErrorCode.INVALID_FILTER, ApiErrorCode.UNKNOWN_SKILL))
      ApiErrorDto(error.code, requireNotNull(error.message)) else throw error
    else -> throw error
  }
  fun <T> connectionResult(block: () -> T): Any = try { block()!! }
    catch (error: Exception) { failure<Nothing>(error) }
}

/** Wire identities are append-only. Never renumber/reuse an ID, even if a skill is retired.
 * New catalog entries require a new unused integer here, independent of catalog iteration order.
 */
internal object SkillNodeIds {
  private val slugs = mapOf(
    1L to "kotlin", 2L to "java", 3L to "spring", 4L to "javascript", 5L to "typescript",
    6L to "react", 7L to "vue", 8L to "solidjs", 9L to "nodejs", 10L to "python",
    11L to "django", 12L to "fastapi", 13L to "go", 14L to "rust", 15L to "c",
    16L to "cpp", 17L to "csharp", 18L to "dotnet", 19L to "swift", 20L to "ios",
    21L to "android", 22L to "flutter", 23L to "postgresql", 24L to "mysql", 25L to "redis",
    26L to "mongodb", 27L to "kafka", 28L to "aws", 29L to "gcp", 30L to "azure",
    31L to "docker", 32L to "kubernetes", 33L to "terraform", 34L to "linux", 35L to "git",
    36L to "graphql", 37L to "rest", 38L to "spark", 39L to "airflow", 40L to "pytorch", 41L to "tensorflow",
  )
  private val ids = slugs.entries.associate { it.value to it.key }
  fun id(slug: String) = requireNotNull(ids[slug]) { "Skill lacks a stable Node identity" }
  fun slug(id: Long) = slugs[id]
}
