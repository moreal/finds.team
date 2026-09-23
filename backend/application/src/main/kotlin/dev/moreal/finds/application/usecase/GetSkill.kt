package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.ConnectionRequest
import dev.moreal.finds.application.port.DiscoveryQueryPort
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.posting.SkillTaxonomy
import dev.moreal.finds.domain.search.Filter

class GetSkill(private val queries: DiscoveryQueryPort) {
  fun execute(slug: String) = SkillTaxonomy.V1.requireSkill(slug)
  fun list(query: String? = null, page: ConnectionRequest = ConnectionRequest()) = queries.skills(query, page)
  fun postings(slug: String, page: ConnectionRequest = ConnectionRequest()) =
    queries.postings(Filter.And(listOf(Filter.HasSkill(slug), Filter.HasStatus(PostingStatus.OPEN))), page)
  fun companies(slug: String, page: ConnectionRequest = ConnectionRequest()) = queries.skillCompanies(slug, page)
  fun related(slug: String, page: ConnectionRequest = ConnectionRequest()) = queries.relatedSkills(slug, page)
  fun requirementCounts(slug: String) = queries.skillRequirementCounts(slug)
}
