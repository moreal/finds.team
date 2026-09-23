package dev.moreal.finds_team.graphql

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture
import dev.moreal.finds_team.security.ActorResolver
import org.springframework.security.core.Authentication

data class GraphqlRequest(
  val query: String,
  val operationName: String? = null,
  val variables: Map<String, Any?>? = null,
)

@RestController
@RequestMapping("/graphql")
class GraphqlController(
  private val graphQL: GraphQL,
  private val actors: ActorResolver? = null,
) {
  @PostMapping(
    consumes = [MediaType.APPLICATION_JSON_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE],
  )
  fun execute(@RequestBody request: GraphqlRequest, authentication: Authentication?): CompletableFuture<Map<String, Any>> {
    // Reopen mutations only when the audited, Actor-authorized application adapters are wired.
    val operations = try { graphql.parser.Parser().parseDocument(request.query)
      .getDefinitionsOfType(graphql.language.OperationDefinition::class.java) } catch (_: graphql.parser.InvalidSyntaxException) { emptyList() }
    if (operations.any { it.operation != graphql.language.OperationDefinition.Operation.QUERY })
      throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)
    val input = ExecutionInput.newExecutionInput()
      .query(request.query)
      .variables(request.variables.orEmpty())
      .graphQLContext { context -> actors?.resolve(authentication)?.let { context.put("actor", it) } }
      .also { builder -> request.operationName?.let(builder::operationName) }
      .build()
    return graphQL.executeAsync(input).thenApply(ExecutionResult::toSpecification)
  }
}
