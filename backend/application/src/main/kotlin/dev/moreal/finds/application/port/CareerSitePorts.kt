package dev.moreal.finds.application.port

import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SourceProvider

interface CareerSiteRepository {
  fun findById(id: CareerSiteId): CareerSite?

  fun findByHost(host: SiteHost): CareerSite?

  fun insert(site: NewCareerSite): InsertCareerSiteResult

  fun findEnabled(): List<CareerSite>
}

sealed interface InsertCareerSiteResult {
  data class Inserted(val site: CareerSite) : InsertCareerSiteResult

  data class Duplicate(val existing: CareerSite) : InsertCareerSiteResult
}

fun interface SourceDiscoveryPort {
  suspend fun detect(url: SiteUrl): ProviderDiscoveryResult
}

sealed interface ProviderDiscoveryResult {
  data class Detected(val provider: SourceProvider) : ProviderDiscoveryResult

  data object Unsupported : ProviderDiscoveryResult

  data class Ambiguous(val providers: Set<SourceProvider>) : ProviderDiscoveryResult

  data class Failed(val reason: String) : ProviderDiscoveryResult
}
