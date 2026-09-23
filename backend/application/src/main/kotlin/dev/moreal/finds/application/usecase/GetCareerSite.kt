package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.ConnectionRequest
import dev.moreal.finds.application.port.DiscoveryQueryPort
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.search.Filter

class GetCareerSite(private val queries: DiscoveryQueryPort) {
  fun execute(slug: String) = queries.findSiteBySlug(slug)
  fun list(page: ConnectionRequest = ConnectionRequest()) = queries.careerSites(page)
  fun openPostings(id: CareerSiteId, page: ConnectionRequest = ConnectionRequest()) =
    queries.postings(Filter.And(listOf(Filter.AtSite(id), Filter.HasStatus(PostingStatus.OPEN))), page)
}
