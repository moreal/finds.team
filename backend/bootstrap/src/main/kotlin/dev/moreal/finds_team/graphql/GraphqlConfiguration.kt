package dev.moreal.finds_team.graphql

import dev.moreal.finds.application.usecase.CrawlSite
import dev.moreal.finds.application.usecase.GetCrawlStatus
import dev.moreal.finds.application.usecase.RegisterCareerSite
import dev.moreal.finds.application.usecase.SearchPostings
import dev.moreal.finds.application.port.SecurityEventPort
import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.graphql.FindsGraphqlFacade
import dev.moreal.finds.graphql.GraphqlRuntime
import dev.moreal.finds_team.runtime.ManagedCoroutineScope
import graphql.GraphQL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import dev.moreal.finds.persistence.JooqDiscoveryQuery
import org.jooq.DSLContext

@Configuration(proxyBeanMethods = false)
class GraphqlConfiguration {
  @Bean fun discoveryQueries(context: DSLContext) = JooqDiscoveryQuery(context)
  @Bean(destroyMethod = "close")
  fun applicationCoroutineScope(): ManagedCoroutineScope = ManagedCoroutineScope()

  @Bean
  fun findsGraphqlFacade(
    search: SearchPostings,
    register: RegisterCareerSite,
    crawl: CrawlSite,
    statuses: GetCrawlStatus,
    securityEvents: SecurityEventPort,
    clock: ClockPort,
    discovery: JooqDiscoveryQuery,
  ): FindsGraphqlFacade = FindsGraphqlFacade(search, register, crawl, statuses, securityEvents, clock, discovery, discovery)

  @Bean
  fun graphQL(facade: FindsGraphqlFacade, scope: ManagedCoroutineScope): GraphQL =
    GraphqlRuntime.create(facade, scope)
}
