package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.PageRequest
import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.port.PostingRepository
import dev.moreal.finds.domain.search.Filter
import dev.moreal.finds.domain.search.normalize

class SearchPostings(
  private val postings: PostingRepository,
) {
  fun execute(
    filter: Filter,
    page: PageRequest = PageRequest(),
  ): SearchPage = postings.search(filter.normalize(), page)
}
