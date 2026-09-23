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
            is UserDto -> "User"
            is CrawlRunDto -> "CrawlRun"
            is AuditEventDto -> "AuditEvent"
            else -> null
          }
          name?.let(environment.schema::getObjectType)
        }
      }
      .discovery(facade)
      .viewer(facade)
      .operations()
      .type("Mutation") { type ->
        type.dataFetcher("registerCareerSite") { environment ->
          val input = requireNotNull(environment.getArgument<Map<String, Any>>("input"))
          scope.future {
            facade.registerCareerSite(
              RegisterCareerSiteInput(
                input.getValue("url") as String,
                input.getValue("displayName") as String,
                input.getValue("idempotencyKey") as String,
                input["clientMutationId"] as String?,
              ),
              environment.graphQlContext.get<SessionPrincipal>(SESSION_PRINCIPAL),
            ).copy(clientMutationId = input["clientMutationId"] as String?)
          }
        }.dataFetcher("triggerCrawl") { environment ->
          val input = requireNotNull(environment.getArgument<Map<String, Any?>>("input"))
          scope.future {
            facade.triggerCrawl(input.getValue("careerSiteId") as String, input.getValue("idempotencyKey") as String,
              environment.graphQlContext.get<SessionPrincipal>(SESSION_PRINCIPAL)).copy(clientMutationId = input["clientMutationId"] as String?)
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
        val forbidden = exception is dev.moreal.finds.application.usecase.QueryForbidden
        val extensions = mutableMapOf<String, Any>("code" to (if (forbidden) "FORBIDDEN" else expected?.code?.name ?: "INTERNAL"))
        if (!forbidden && expected == null) extensions["correlationId"] = java.util.UUID.randomUUID().toString()
        val error = GraphqlErrorBuilder.newError(parameters.dataFetchingEnvironment)
          .message(if (forbidden) "Access forbidden" else expected?.message ?: "Request failed")
          .extensions(extensions)
          .build()
        CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
      }.build()
  }

  const val SESSION_PRINCIPAL = "sessionPrincipal"

}
