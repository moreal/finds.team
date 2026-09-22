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

data class GraphqlRequest(
  val query: String,
  val operationName: String? = null,
  val variables: Map<String, Any?>? = null,
)

@RestController
@RequestMapping("/graphql")
class GraphqlController(
  private val graphQL: GraphQL,
) {
  @PostMapping(
    consumes = [MediaType.APPLICATION_JSON_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE],
  )
  fun execute(@RequestBody request: GraphqlRequest): CompletableFuture<Map<String, Any>> {
    val input = ExecutionInput.newExecutionInput()
      .query(request.query)
      .variables(request.variables.orEmpty())
      .also { builder -> request.operationName?.let(builder::operationName) }
      .build()
    return graphQL.executeAsync(input).thenApply(ExecutionResult::toSpecification)
  }
}
