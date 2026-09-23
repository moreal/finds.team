package dev.moreal.finds.application.port

import dev.moreal.finds.application.model.*
import dev.moreal.finds.domain.career.*
import dev.moreal.finds.domain.posting.*
import dev.moreal.finds.domain.search.Filter

data class PostingPageKey(val filter: Filter, val page: ConnectionRequest)
data class SkillPageKey(val slug: String, val page: ConnectionRequest)

/** Ordered results, one bounded statement per batch; validation failures stay local to each key. */
interface DiscoveryBatchQueryPort {
  fun findPostings(ids: List<JobPostingId>): List<JobPosting?>
  fun findSites(ids: List<CareerSiteId>): List<CareerSite?>
  fun postingPages(keys: List<PostingPageKey>): List<Result<ConnectionPage<JobPosting>>>
  fun companyPages(keys: List<SkillPageKey>): List<Result<ConnectionPage<CareerSite>>>
  fun relatedSkillPages(keys: List<SkillPageKey>): List<Result<ConnectionPage<SkillDefinition>>>
  fun requirementCounts(slugs: List<String>): List<SkillRequirementCounts>
}
