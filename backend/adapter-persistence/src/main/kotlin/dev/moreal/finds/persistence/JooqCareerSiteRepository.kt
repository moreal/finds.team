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
import org.jooq.exception.DataAccessException
import org.postgresql.util.PSQLException

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

  override fun insert(site: NewCareerSite): InsertCareerSiteResult = try {
    val record = requireNotNull(
      context.insertInto(CAREER_SITES)
        .set(CAREER_SITES.CANONICAL_BASE_URL, site.canonicalBaseUrl.value.toString())
        .set(CAREER_SITES.HOST, site.canonicalBaseUrl.host.value)
        .set(CAREER_SITES.PROVIDER, site.provider.name)
        .set(CAREER_SITES.DISPLAY_NAME, site.displayName)
        .set(CAREER_SITES.ENABLED, site.crawlSettings.enabled)
        .set(
          CAREER_SITES.SUCCESSFUL_INTERVAL_SECONDS,
          site.crawlSettings.successfulInterval.seconds,
        )
        .returning()
        .fetchOne(),
    ) { "Career-site insert returned no row" }
    InsertCareerSiteResult.Inserted(record.toDomain())
  } catch (error: DataAccessException) {
    if (error.constraintName() != HOST_UNIQUE_CONSTRAINT) throw error
    val existing = requireNotNull(findByHost(site.canonicalBaseUrl.host)) {
      "Host uniqueness failed but existing career site was not found"
    }
    InsertCareerSiteResult.Duplicate(existing)
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
    crawlSettings = CrawlSettings(
      successfulInterval = Duration.ofSeconds(requireNotNull(successfulIntervalSeconds)),
      enabled = requireNotNull(enabled),
    ),
  )

  private fun DataAccessException.constraintName(): String? =
    generateSequence<Throwable>(this) { it.cause }
      .filterIsInstance<PSQLException>()
      .firstOrNull()
      ?.serverErrorMessage
      ?.constraint

  private companion object {
    const val HOST_UNIQUE_CONSTRAINT = "uq_career_sites_host"
  }
}
