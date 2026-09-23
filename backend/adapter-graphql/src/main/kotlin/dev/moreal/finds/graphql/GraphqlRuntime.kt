package dev.moreal.finds.graphql

import graphql.GraphQL
import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandlerResult
import dev.moreal.finds.application.usecase.SessionPrincipal
import graphql.Scalars
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import java.io.InputStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture

object GraphqlRuntime {
  fun create(facade: FindsGraphqlFacade, scope: CoroutineScope): GraphQL {
    val schema = InputStreamReader(
      requireNotNull(GraphqlRuntime::class.java.getResourceAsStream("/finds.graphqls")),
    ).use { it.readText() }
    val registry = SchemaParser().parse(schema)
    val dateTime = GraphQLScalarType.newScalar()
      .name("DateTime")
      .coercing(Scalars.GraphQLString.coercing)
      .build()
    val wiring = RuntimeWiring.newRuntimeWiring()
      .scalar(dateTime)
      .type("Query") { type ->
        type.dataFetcher("jobPostings") { environment ->
          facade.jobPostings(
            environment.getArgument<Map<String, Any?>>("filter")?.toFilterInput(),
            environment.getArgument("first"),
            environment.getArgument("after"),
          )
        }.dataFetcher("crawlStatuses") { facade.crawlStatuses() }
      }
      .type("Mutation") { type ->
        type.dataFetcher("registerCareerSite") { environment ->
          val input = requireNotNull(environment.getArgument<Map<String, Any>>("input"))
          scope.future {
            facade.registerCareerSite(
              RegisterCareerSiteInput(
                input.getValue("url") as String,
                input.getValue("displayName") as String,
                input.getValue("idempotencyKey") as String,
              ),
              environment.graphQlContext.get<SessionPrincipal>(SESSION_PRINCIPAL),
            )
          }
        }.dataFetcher("triggerCrawl") {
          // Open each mutation only when its audited application command is available.
          throw ClosedMutation()
        }
      }
      .build()
    return GraphQL.newGraphQL(SchemaGenerator().makeExecutableSchema(registry, wiring))
      .defaultDataFetcherExceptionHandler { parameters ->
        val forbidden = parameters.exception is ClosedMutation
        val error = GraphqlErrorBuilder.newError(parameters.dataFetchingEnvironment)
          .message(if (forbidden) "Mutation forbidden" else "Request failed")
          .extensions(mapOf("code" to if (forbidden) "FORBIDDEN" else "INTERNAL"))
          .build()
        CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
      }.build()
  }

  const val SESSION_PRINCIPAL = "sessionPrincipal"
  private class ClosedMutation : RuntimeException()

  @Suppress("UNCHECKED_CAST")
  private fun Map<String, Any?>.toFilterInput(): PostingFilterInput = PostingFilterInput(
    atSite = this["atSite"] as String?,
    textContains = this["textContains"] as String?,
    hasStatus = (this["hasStatus"] as String?)?.let(dev.moreal.finds.domain.posting.PostingStatus::valueOf),
    updatedAfter = this["updatedAfter"] as String?,
    not = (this["not"] as Map<String, Any?>?)?.toFilterInput(),
    all = (this["all"] as List<Map<String, Any?>>?)?.map { it.toFilterInput() },
    any = (this["any"] as List<Map<String, Any?>>?)?.map { it.toFilterInput() },
  )
}
