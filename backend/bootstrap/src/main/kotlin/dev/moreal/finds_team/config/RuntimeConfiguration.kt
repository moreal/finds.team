package dev.moreal.finds_team.config

import dev.moreal.finds.application.port.CareerSiteRepository
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.CrawlLeasePort
import dev.moreal.finds.application.port.CrawlRunRepository
import dev.moreal.finds.application.port.PostingRepository
import dev.moreal.finds.application.port.SourceDiscoveryPort
import dev.moreal.finds.application.port.SourceFetchPort
import dev.moreal.finds.application.port.SuccessfulCrawlPort
import dev.moreal.finds.application.usecase.CrawlAllDue
import dev.moreal.finds.application.usecase.CrawlSite
import dev.moreal.finds.application.usecase.GetCrawlStatus
import dev.moreal.finds.application.usecase.RegisterCareerSite
import dev.moreal.finds.application.usecase.SearchPostings
import dev.moreal.finds.domain.career.CrawlSettings
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.crawl.ClosePolicy
import dev.moreal.finds.domain.crawl.RetryPolicy
import dev.moreal.finds.persistence.JooqCareerSiteRepository
import dev.moreal.finds.persistence.JooqCrawlLeasePort
import dev.moreal.finds.persistence.JooqCrawlRunRepository
import dev.moreal.finds.persistence.JooqPostingRepository
import dev.moreal.finds.persistence.JooqSuccessfulCrawlAdapter
import dev.moreal.finds.source.SourceGateway
import dev.moreal.finds.source.protocol.DestinationPolicy
import dev.moreal.finds.source.protocol.JvmHostResolver
import dev.moreal.finds.source.protocol.KtorSingleRequestTransport
import dev.moreal.finds.source.protocol.SafeKtorWebClient
import dev.moreal.finds.source.protocol.SourceIdentity
import dev.moreal.finds.source.protocol.SourceProtocolSettings
import dev.moreal.finds.source.protocol.WebClient
import dev.moreal.finds.source.provider.FlexSourceAdapter
import dev.moreal.finds.source.provider.GreetingSourceAdapter
import dev.moreal.finds.source.provider.NinehireSourceAdapter
import dev.moreal.finds.source.provider.ProviderDetector
import dev.moreal.finds.source.provider.SourceAdapter
import dev.moreal.finds.source.robots.RobotsClient
import dev.moreal.finds.source.sitemap.SitemapCrawler
import dev.moreal.finds_team.crawl.ScheduledCrawlDispatcher
import dev.moreal.finds_team.runtime.ManagedCoroutineScope
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import java.time.Instant
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class RuntimeConfiguration {
  @Bean
  @DependsOn("flyway")
  fun dslContext(dataSource: DataSource): DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

  @Bean
  fun careerSites(context: DSLContext): CareerSiteRepository = JooqCareerSiteRepository(context)

  @Bean
  fun postings(context: DSLContext): PostingRepository = JooqPostingRepository(context)

  @Bean
  fun crawlRuns(context: DSLContext): CrawlRunRepository = JooqCrawlRunRepository(context)

  @Bean
  fun crawlLeases(context: DSLContext): CrawlLeasePort = JooqCrawlLeasePort(context)

  @Bean
  fun successfulCrawls(context: DSLContext): SuccessfulCrawlPort =
    JooqSuccessfulCrawlAdapter(context)

  @Bean
  fun clock(): ClockPort = ClockPort(Instant::now)

  @Bean
  fun sourceProtocolSettings(properties: FindsProperties): SourceProtocolSettings {
    val source = properties.source
    return SourceProtocolSettings(
      connectTimeout = source.connectTimeout,
      requestTimeout = source.requestTimeout,
      siteTimeout = source.siteTimeout,
      maxResponseBytes = source.maximumResponseBytes.toIntExact("maximumResponseBytes"),
      maxRedirects = source.maximumRedirects,
      robotsSuccessTtl = source.robotsSuccessTtl,
      robotsUnavailableTtl = source.robotsFailureTtl,
      maxSitemapDepth = source.sitemapMaximumDepth,
      maxSitemapDocuments = source.sitemapMaximumDocuments,
      maxSitemapUrls = source.sitemapMaximumUrls,
      maxSitemapTotalBytes = source.sitemapMaximumTotalBytes,
      minimumHostSpacing = source.minimumHostSpacing,
    )
  }

  @Bean(destroyMethod = "close")
  fun sourceTransport(
    settings: SourceProtocolSettings,
    properties: FindsProperties,
  ): KtorSingleRequestTransport = KtorSingleRequestTransport.create(
    settings,
    SourceIdentity(
      properties.source.userAgentProduct,
      requireUrl(properties.source.contactUrl),
    ),
  )

  @Bean
  fun webClient(
    transport: KtorSingleRequestTransport,
    settings: SourceProtocolSettings,
  ): WebClient = SafeKtorWebClient(
    transport,
    DestinationPolicy(JvmHostResolver()),
    settings,
  )

  @Bean
  fun robotsClient(
    web: WebClient,
    settings: SourceProtocolSettings,
    properties: FindsProperties,
  ): RobotsClient = RobotsClient(web, settings, properties.source.robotsProductToken)

  @Bean
  fun sitemapCrawler(
    web: WebClient,
    robots: RobotsClient,
    settings: SourceProtocolSettings,
  ): SitemapCrawler = SitemapCrawler(web, robots, settings)

  @Bean
  fun flexSource(web: WebClient, robots: RobotsClient, clock: ClockPort): SourceAdapter =
    FlexSourceAdapter(web, robots, clock)

  @Bean
  fun greetingSource(
    web: WebClient,
    robots: RobotsClient,
    sitemaps: SitemapCrawler,
    clock: ClockPort,
  ): SourceAdapter = GreetingSourceAdapter(web, robots, sitemaps, clock)

  @Bean
  fun ninehireSource(
    web: WebClient,
    robots: RobotsClient,
    sitemaps: SitemapCrawler,
    clock: ClockPort,
  ): SourceAdapter = NinehireSourceAdapter(web, robots, sitemaps, clock)

  @Bean
  fun sourceGateway(
    adapters: List<SourceAdapter>,
    settings: SourceProtocolSettings,
  ): SourceFetchPort = SourceGateway(adapters, settings)

  @Bean
  fun sourceDiscovery(web: WebClient, robots: RobotsClient): SourceDiscoveryPort =
    ProviderDetector(web, robots)

  @Bean
  fun retryPolicy(properties: FindsProperties): RetryPolicy =
    RetryPolicy(properties.crawl.retryDelays)

  @Bean
  fun closePolicy(properties: FindsProperties): ClosePolicy =
    ClosePolicy(properties.crawl.closeAfterMisses)

  @Bean
  fun registerCareerSite(
    sites: CareerSiteRepository,
    discovery: SourceDiscoveryPort,
    properties: FindsProperties,
  ): RegisterCareerSite = RegisterCareerSite(
    sites,
    discovery,
    CrawlSettings(successfulInterval = properties.crawl.successInterval),
  )

  @Bean
  fun searchPostings(postings: PostingRepository): SearchPostings = SearchPostings(postings)

  @Bean
  fun crawlSite(
    sites: CareerSiteRepository,
    postings: PostingRepository,
    runs: CrawlRunRepository,
    source: SourceFetchPort,
    leases: CrawlLeasePort,
    completion: SuccessfulCrawlPort,
    clock: ClockPort,
    retryPolicy: RetryPolicy,
    closePolicy: ClosePolicy,
    properties: FindsProperties,
  ): CrawlSite = CrawlSite(
    sites,
    postings,
    runs,
    source,
    leases,
    completion,
    clock,
    retryPolicy,
    closePolicy,
    properties.crawl.leaseOwner,
    properties.crawl.leaseDuration,
  )

  @Bean
  fun crawlAllDue(
    sites: CareerSiteRepository,
    runs: CrawlRunRepository,
    clock: ClockPort,
    retryPolicy: RetryPolicy,
  ): CrawlAllDue = CrawlAllDue(sites, runs, clock, retryPolicy)

  @Bean
  fun getCrawlStatus(runs: CrawlRunRepository): GetCrawlStatus = GetCrawlStatus(runs)

  @Bean
  fun scheduledCrawlDispatcher(
    due: CrawlAllDue,
    crawl: CrawlSite,
    clock: ClockPort,
    scope: ManagedCoroutineScope,
    properties: FindsProperties,
    registry: MeterRegistry,
  ): ScheduledCrawlDispatcher = ScheduledCrawlDispatcher(
    due,
    crawl,
    clock,
    scope,
    properties,
    registry,
  )

  private fun requireUrl(value: String): SiteUrl = when (val parsed = SiteUrl.parse(value)) {
    is SiteUrlResult.Valid -> parsed.url
    is SiteUrlResult.Invalid -> error("Configured contact URL is invalid: ${parsed.reason}")
  }

  private fun Long.toIntExact(name: String): Int {
    require(this <= Int.MAX_VALUE) { "$name exceeds the supported integer range" }
    return toInt()
  }
}
