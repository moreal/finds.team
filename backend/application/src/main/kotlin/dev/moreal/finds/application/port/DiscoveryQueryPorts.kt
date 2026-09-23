package dev.moreal.finds.application.port

import dev.moreal.finds.application.model.*
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.posting.*
import dev.moreal.finds.domain.search.Filter

/** Public discovery reads. IDs and cursor values have no GraphQL dependency. */
interface DiscoveryQueryPort {
  fun findPosting(id: JobPostingId): JobPosting?
  fun findSiteBySlug(slug: String): CareerSite?
  fun postings(filter: Filter, page: ConnectionRequest): ConnectionPage<JobPosting>
  fun careerSites(page: ConnectionRequest): ConnectionPage<CareerSite>
  fun skills(query: String?, page: ConnectionRequest): ConnectionPage<SkillDefinition>
  fun skillCompanies(slug: String, page: ConnectionRequest): ConnectionPage<CareerSite>
  fun relatedSkills(slug: String, page: ConnectionRequest): ConnectionPage<SkillDefinition>
  fun skillRequirementCounts(slug: String): SkillRequirementCounts
}
