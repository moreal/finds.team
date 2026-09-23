package dev.moreal.finds_team.security

import dev.moreal.finds.graphql.GlobalIdCodec
import dev.moreal.finds.graphql.NodeType
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
class AuthSessionController(private val actors: ActorResolver, private val boundary: OtpHttpBoundary) {
  @GetMapping("/auth/session", produces = ["application/json"])
  fun session(authentication: Authentication?) = actors.resolve(authentication)?.let { actor ->
    boundary.json(mapOf("authenticated" to true, "userId" to actor.userId.toString(),
      "userGlobalId" to GlobalIdCodec.encode(NodeType.User, actor.userId),
      "roles" to actor.roles.map { it.name }.sorted(), "authenticatedAt" to actor.authenticatedAt.toString()))
  } ?: throw OtpHttpRejected(HttpStatus.UNAUTHORIZED)
}
