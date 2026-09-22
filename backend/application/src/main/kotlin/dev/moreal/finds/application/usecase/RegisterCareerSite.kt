package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.port.CareerSiteRepository
import dev.moreal.finds.application.port.InsertCareerSiteResult
import dev.moreal.finds.application.port.ProviderDiscoveryResult
import dev.moreal.finds.application.port.SourceDiscoveryPort
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider

data class RegisterCareerSiteCommand(
  val url: String,
  val displayName: String,
)

sealed interface RegisterCareerSiteResult {
  data class Registered(val site: CareerSite) : RegisterCareerSiteResult

  data class InvalidUrl(val reason: String) : RegisterCareerSiteResult

  data object UnsupportedProvider : RegisterCareerSiteResult

  data class AmbiguousProvider(val providers: Set<SourceProvider>) : RegisterCareerSiteResult

  data class DiscoveryFailed(val reason: String) : RegisterCareerSiteResult

  data class AlreadyRegistered(val site: CareerSite) : RegisterCareerSiteResult

  data class InvalidDisplayName(val reason: String) : RegisterCareerSiteResult
}

class RegisterCareerSite(
  private val sites: CareerSiteRepository,
  private val discovery: SourceDiscoveryPort,
) {
  fun execute(command: RegisterCareerSiteCommand): RegisterCareerSiteResult {
    val siteUrl = when (val parsed = SiteUrl.parse(command.url)) {
      is SiteUrlResult.Invalid -> return RegisterCareerSiteResult.InvalidUrl(parsed.reason)
      is SiteUrlResult.Valid -> parsed.url
    }
    if (command.displayName.isBlank()) {
      return RegisterCareerSiteResult.InvalidDisplayName("Display name must not be blank")
    }
    if (command.displayName != command.displayName.trim()) {
      return RegisterCareerSiteResult.InvalidDisplayName("Display name must be trimmed")
    }

    sites.findByHost(siteUrl.host)?.let { existing ->
      return RegisterCareerSiteResult.AlreadyRegistered(existing)
    }

    val provider = when (val detected = discovery.detect(siteUrl)) {
      is ProviderDiscoveryResult.Detected -> detected.provider
      ProviderDiscoveryResult.Unsupported -> {
        return RegisterCareerSiteResult.UnsupportedProvider
      }
      is ProviderDiscoveryResult.Ambiguous -> {
        return RegisterCareerSiteResult.AmbiguousProvider(detected.providers.toSet())
      }
      is ProviderDiscoveryResult.Failed -> {
        return RegisterCareerSiteResult.DiscoveryFailed(detected.reason)
      }
    }
    val newSite = NewCareerSite(
      canonicalBaseUrl = siteUrl,
      provider = provider,
      displayName = command.displayName,
    )
    return when (val inserted = sites.insert(newSite)) {
      is InsertCareerSiteResult.Inserted -> RegisterCareerSiteResult.Registered(inserted.site)
      is InsertCareerSiteResult.Duplicate -> {
        RegisterCareerSiteResult.AlreadyRegistered(inserted.existing)
      }
    }
  }
}
