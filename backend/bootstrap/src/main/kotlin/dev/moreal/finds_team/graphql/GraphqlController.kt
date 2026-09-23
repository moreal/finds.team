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
import dev.moreal.finds.graphql.GraphqlRuntime
import org.springframework.security.core.Authentication
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.csrf.DeferredCsrfToken
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

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
  fun execute(@RequestBody request: GraphqlRequest, authentication: Authentication?, servletRequest: HttpServletRequest): CompletableFuture<Map<String, Any>> {
    // The security filter leaves public POST queries open. Check the selected operation here, after
    // bounded JSON parsing, using the same deferred session token and XOR handler as Spring Security.
    val operations = try { graphql.parser.Parser().parseDocument(request.query)
      .getDefinitionsOfType(graphql.language.OperationDefinition::class.java) }
      catch (_: graphql.parser.InvalidSyntaxException) { emptyList() }
    val operation = if (request.operationName == null) operations.singleOrNull()
      else operations.singleOrNull { it.name == request.operationName }
    if (operation != null && operation.operation != graphql.language.OperationDefinition.Operation.QUERY) {
      val expected = (servletRequest.getAttribute(DeferredCsrfToken::class.java.name) as? DeferredCsrfToken)?.get()
        ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
      val supplied = XorCsrfTokenRequestAttributeHandler().resolveCsrfTokenValue(servletRequest, expected)
      if (supplied == null || !MessageDigest.isEqual(expected.token.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8)))
        throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }
    val input = ExecutionInput.newExecutionInput()
      .query(request.query)
      .variables(request.variables.orEmpty())
      .graphQLContext { context -> actors?.sessionPrincipal(authentication)?.let { context.put(GraphqlRuntime.SESSION_PRINCIPAL, it) } }
      .also { builder -> request.operationName?.let(builder::operationName) }
      .build()
    return graphQL.executeAsync(input).thenApply(ExecutionResult::toSpecification)
  }
}
