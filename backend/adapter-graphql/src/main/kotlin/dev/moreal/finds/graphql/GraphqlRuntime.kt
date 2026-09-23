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
            else -> null
          }
          name?.let(environment.schema::getObjectType)
        }
      }
      .type("Query") { type ->
        type.dataFetcher("node") { environment ->
          val id = GlobalIdCodec.decode(requireNotNull(environment.getArgument<String>("id")))
          unavailableNode(id)
        }.dataFetcher("jobPosting") { environment ->
          val id = GlobalIdCodec.decode(NodeType.JobPosting, requireNotNull(environment.getArgument<String>("id")))
          unavailableNode(GlobalId(NodeType.JobPosting, id))
        }.dataFetcher("jobPostings") { environment ->
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
      .defaultDataFetcherExceptionHandler { parameters ->
        val exception = parameters.exception
        val expected = exception as? GraphqlRequestException
        val error = GraphqlErrorBuilder.newError(parameters.dataFetchingEnvironment)
          .message(expected?.message ?: "Request failed")
          .extensions(mapOf("code" to (expected?.code?.name ?: "INTERNAL")))
          .build()
        CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
      }.build()
  }

  const val SESSION_PRINCIPAL = "sessionPrincipal"

  // Tasks 5–6 replace each branch with an authorized, bounded application lookup.
  // A known identity must never silently resolve as another node type.
  private fun unavailableNode(id: GlobalId): Nothing = when (id.type) {
    NodeType.JobPosting, NodeType.CareerSite, NodeType.User,
    NodeType.Skill, NodeType.CrawlRun, NodeType.AuditEvent ->
      throw GraphqlRequestException(ApiErrorCode.NOT_FOUND, "Node is not available")
  }

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
