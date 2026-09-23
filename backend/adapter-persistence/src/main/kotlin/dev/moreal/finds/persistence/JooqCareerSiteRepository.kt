package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.port.CareerSiteRepository
import dev.moreal.finds.application.port.InsertCareerSiteResult
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.CrawlSettings
import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.persistence.jooq.generated.tables.records.CareerSitesRecord
import dev.moreal.finds.persistence.jooq.generated.tables.references.CAREER_SITES
import java.time.Duration
import org.jooq.DSLContext

class JooqCareerSiteRepository(
  private val context: DSLContext,
) : CareerSiteRepository {
  override fun findById(id: CareerSiteId): CareerSite? =
    context.selectFrom(CAREER_SITES)
      .where(CAREER_SITES.ID.eq(id.value))
      .fetchOne()
      ?.toDomain()

  override fun findByHost(host: SiteHost): CareerSite? =
    context.selectFrom(CAREER_SITES)
      .where(CAREER_SITES.HOST.eq(host.value))
      .fetchOne()
      ?.toDomain()

  override fun insert(site: NewCareerSite): InsertCareerSiteResult {
    val record = context.insertInto(CAREER_SITES)
      .set(CAREER_SITES.CANONICAL_BASE_URL, site.canonicalBaseUrl.value.toString())
      .set(CAREER_SITES.HOST, site.canonicalBaseUrl.host.value)
      .set(CAREER_SITES.PROVIDER, site.provider.name)
      .set(CAREER_SITES.DISPLAY_NAME, site.displayName)
      .set(CAREER_SITES.ENABLED, site.crawlSettings.enabled)
      .set(
        CAREER_SITES.SUCCESSFUL_INTERVAL_SECONDS,
        site.crawlSettings.successfulInterval.seconds,
      )
      .onConflict(CAREER_SITES.HOST)
      .doNothing()
      .returning()
      .fetchOne()
    if (record != null) return InsertCareerSiteResult.Inserted(record.toDomain())
    val existing = requireNotNull(findByHost(site.canonicalBaseUrl.host)) {
      "Host uniqueness failed but existing career site was not found"
    }
    return InsertCareerSiteResult.Duplicate(existing)
  }

  override fun findEnabled(): List<CareerSite> =
    context.selectFrom(CAREER_SITES)
      .where(CAREER_SITES.ENABLED.isTrue)
      .orderBy(CAREER_SITES.ID.asc())
      .fetch()
      .map { it.toDomain() }

  private fun CareerSitesRecord.toDomain(): CareerSite = CareerSite(
    id = CareerSiteId(requireNotNull(id)),
    canonicalBaseUrl = when (val parsed = SiteUrl.parse(requireNotNull(canonicalBaseUrl))) {
      is SiteUrlResult.Valid -> parsed.url
      is SiteUrlResult.Invalid -> error("Stored career-site URL is invalid: ${parsed.reason}")
    },
    provider = SourceProvider.valueOf(requireNotNull(provider)),
    displayName = requireNotNull(displayName),
    slug = requireNotNull(slug),
    crawlSettings = CrawlSettings(
      successfulInterval = Duration.ofSeconds(requireNotNull(successfulIntervalSeconds)),
      enabled = requireNotNull(enabled),
    ),
  )
}
