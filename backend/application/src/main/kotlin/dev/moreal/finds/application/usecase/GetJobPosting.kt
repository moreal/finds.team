package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.*
import dev.moreal.finds.application.port.DiscoveryQueryPort
import dev.moreal.finds.domain.posting.JobPostingId
import dev.moreal.finds.domain.search.Filter
import dev.moreal.finds.domain.search.normalize

class GetJobPosting(private val queries: DiscoveryQueryPort) {
  fun execute(id: JobPostingId) = queries.findPosting(id)
  fun list(filter: Filter = Filter.And(emptyList()), page: ConnectionRequest = ConnectionRequest()) =
    queries.postings(filter.normalize(), page)
}
