package dev.moreal.finds_team.graphql

import dev.moreal.finds.application.usecase.CrawlSite
import dev.moreal.finds.application.usecase.GetCrawlStatus
import dev.moreal.finds.application.usecase.RegisterCareerSite
import dev.moreal.finds.application.usecase.SearchPostings
import dev.moreal.finds.graphql.FindsGraphqlFacade
import dev.moreal.finds.graphql.GraphqlRuntime
import dev.moreal.finds_team.runtime.ManagedCoroutineScope
import graphql.GraphQL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class GraphqlConfiguration {
  @Bean(destroyMethod = "close")
  fun applicationCoroutineScope(): ManagedCoroutineScope = ManagedCoroutineScope()

  @Bean
  fun findsGraphqlFacade(
    search: SearchPostings,
    register: RegisterCareerSite,
    crawl: CrawlSite,
    statuses: GetCrawlStatus,
  ): FindsGraphqlFacade = FindsGraphqlFacade(search, register, crawl, statuses)

  @Bean
  fun graphQL(facade: FindsGraphqlFacade, scope: ManagedCoroutineScope): GraphQL =
    GraphqlRuntime.create(facade, scope)
}
