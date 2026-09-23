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
      .type("Node") { type ->
        type.typeResolver { environment ->
          val name = when (environment.getObject<Any>()) {
            is JobPostingDto -> "JobPosting"
            is CareerSiteDto -> "CareerSite"
            is SkillDto -> "Skill"
            else -> null
          }
          name?.let(environment.schema::getObjectType)
        }
      }
      .discovery(facade)
      .type("Query") { type ->
        type.dataFetcher("crawlStatuses") { facade.crawlStatuses() }
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
        }.dataFetcher("triggerCrawl") { environment ->
          scope.future {
            facade.triggerCrawl(requireNotNull(environment.getArgument("careerSiteId")),
              requireNotNull(environment.getArgument("idempotencyKey")),
              environment.graphQlContext.get<SessionPrincipal>(SESSION_PRINCIPAL))
          }
        }
      }
      .build()
    return GraphQL.newGraphQL(SchemaGenerator().makeExecutableSchema(registry, wiring))
      .instrumentation(DiscoveryLoaderInstrumentation(facade))
      .defaultDataFetcherExceptionHandler { parameters ->
        var exception = parameters.exception
        while (exception is java.util.concurrent.CompletionException && exception.cause != null) exception = exception.cause!!
        val expected = exception as? GraphqlRequestException
        val error = GraphqlErrorBuilder.newError(parameters.dataFetchingEnvironment)
          .message(expected?.message ?: "Request failed")
          .extensions(mapOf("code" to (expected?.code?.name ?: "INTERNAL")))
          .build()
        CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
      }.build()
  }

  const val SESSION_PRINCIPAL = "sessionPrincipal"

}
