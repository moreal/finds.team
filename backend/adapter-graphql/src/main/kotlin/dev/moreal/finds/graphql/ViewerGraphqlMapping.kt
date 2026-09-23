package dev.moreal.finds.graphql

import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.model.*
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.usecase.*
import dev.moreal.finds.domain.identity.*
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.RuntimeWiring
import org.dataloader.DataLoaderRegistry
import java.util.Base64
import java.util.UUID

data class UserDto(val id: String, val roles: List<UserRole>)
data class ViewerDto(val user: UserDto)
data class PasskeyDto(val id: String, val label: String, val createdAt: String, val lastUsedAt: String?)
data class SessionDto(val id: String, val createdAt: String, val expiresAt: String, val current: Boolean)
data class SecurityChangePayload(val outcome: String, val error: ApiErrorDto? = null, val clientMutationId: String? = null,
  val recoveryCode: String? = null) {
  override fun toString() = "SecurityChangePayload(outcome=$outcome, <redacted>)"
}

internal fun DataFetchingEnvironment.principal(): SessionPrincipal? = graphQlContext.get(GraphqlRuntime.SESSION_PRINCIPAL)
internal fun user(principal: SessionPrincipal) = UserDto(GlobalIdCodec.encode(NodeType.User, principal.actor.userId), principal.actor.roles.sortedBy { it.name })
internal fun DataFetchingEnvironment.ownUser(id: String): UserDto {
  val principal = principal() ?: throw QueryForbidden()
  if (principal.actor.userId.toString() != id) throw QueryForbidden()
  return user(principal)
}

internal fun DataLoaderRegistry.accountLoaders(facade: FindsGraphqlFacade): DataLoaderRegistry = apply {
  register(PASSKEYS, requestLoader<PasskeyPageKey, DiscoveryConnectionDto<PasskeyDto>> { keys ->
    requireNotNull(facade.accounts).queries.passkeys(keys).map { result -> result.fold({ page ->
      DiscoveryGraphqlMapping.connection(page) { PasskeyDto(ManagementIds.encode("Passkey", it.id), it.label, it.createdAt.toString(), it.lastUsedAt?.toString()) }
    }, DiscoveryGraphqlMapping::failure) }
  })
  register(SESSIONS, requestLoader<SessionPageKey, DiscoveryConnectionDto<SessionDto>> { keys ->
    requireNotNull(facade.accounts).queries.sessions(keys).map { result -> result.fold({ page ->
      DiscoveryGraphqlMapping.connection(page) { SessionDto(ManagementIds.encode("Session", it.id.value), it.createdAt.toString(), it.expiresAt.toString(), it.current) }
    }, DiscoveryGraphqlMapping::failure) }
  })
}

internal fun RuntimeWiring.Builder.viewer(facade: FindsGraphqlFacade): RuntimeWiring.Builder =
  type("Query") { type -> type.dataFetcher("viewer") { env -> env.principal()?.let { ViewerDto(user(it)) } } }
    .type("Viewer") { type ->
      type.dataFetcher("passkeys") { env -> DiscoveryGraphqlMapping.connectionResult {
        val principal = env.principal() ?: throw QueryForbidden()
        env.getDataLoader<PasskeyPageKey, DiscoveryConnectionDto<PasskeyDto>>(PASSKEYS)!!.load(
          PasskeyPageKey(UserId(principal.actor.userId), env.accountPage()))
      } }.dataFetcher("sessions") { env -> DiscoveryGraphqlMapping.connectionResult {
        val principal = env.principal() ?: throw QueryForbidden()
        env.getDataLoader<SessionPageKey, DiscoveryConnectionDto<SessionDto>>(SESSIONS)!!.load(
          SessionPageKey(UserId(principal.actor.userId), principal.sessionId, requireNotNull(env.graphQlContext.get<java.time.Instant>("requestTime")), env.accountPage()))
      } }
    }.type("Mutation") { type ->
      listOf("renamePasskey", "removePasskey", "rotateRecoveryCode", "revokeSession", "revokeOtherSessions").fold(type) { wiring, operation ->
        wiring.dataFetcher(operation) { env ->
          val input = requireNotNull(env.getArgument<Map<String, Any?>>("input"))
          val client = input["clientMutationId"] as String?
          val payload = try {
            val principal = env.principal() ?: throw QueryForbidden()
            val metadata = commandMetadata(input.getValue("idempotencyKey") as String)
            val accounts = requireNotNull(facade.accounts)
            if (operation == "rotateRecoveryCode") {
              when (val result = accounts.rotate(principal, metadata)) {
                is RotateRecoveryCodeResult.Rotated -> SecurityChangePayload("ROTATED", recoveryCode = result.recoveryCode.format())
                RotateRecoveryCodeResult.AlreadyRotated -> SecurityChangePayload("ALREADY_ROTATED")
                RotateRecoveryCodeResult.Forbidden -> rejected(ApiErrorCode.FORBIDDEN)
                RotateRecoveryCodeResult.IdempotencyConflict -> rejected(ApiErrorCode.IDEMPOTENCY_CONFLICT)
              }
            } else securityResult(when (operation) {
              "renamePasskey" -> accounts.rename(principal, ManagementIds.decode("Passkey", input.getValue("passkeyId") as String), input.getValue("label") as String, metadata)
              "removePasskey" -> accounts.remove(principal, ManagementIds.decode("Passkey", input.getValue("passkeyId") as String), metadata)
              "revokeSession" -> accounts.revoke(principal, UserSessionId(ManagementIds.decode("Session", input.getValue("sessionId") as String)), metadata)
              else -> accounts.revokeOthers(principal, metadata)
            })
          } catch (_: QueryForbidden) { rejected(ApiErrorCode.FORBIDDEN) }
            catch (error: GraphqlRequestException) { rejected(error.code) }
          payload.copy(clientMutationId = client)
        }
      }
    }

internal fun commandMetadata(key: String): CommandMetadata = try {
  CommandMetadata.parse(UUID.randomUUID().toString(), UUID.randomUUID().toString(), key)
} catch (_: IllegalArgumentException) { throw GraphqlRequestException(ApiErrorCode.INVALID_INPUT, "Idempotency key must be a UUID") }

private fun securityResult(result: SecurityChangeResult): SecurityChangePayload = when (result) {
  SecurityChangeResult.Changed -> SecurityChangePayload("CHANGED")
  SecurityChangeResult.Unchanged -> SecurityChangePayload("UNCHANGED")
  SecurityChangeResult.SignedOut -> SecurityChangePayload("SIGNED_OUT")
  SecurityChangeResult.Forbidden -> rejected(ApiErrorCode.FORBIDDEN)
  SecurityChangeResult.NotFound -> rejected(ApiErrorCode.NOT_FOUND)
  SecurityChangeResult.LastCredential -> rejected(ApiErrorCode.LAST_CREDENTIAL)
  SecurityChangeResult.IdempotencyConflict -> rejected(ApiErrorCode.IDEMPOTENCY_CONFLICT)
  SecurityChangeResult.InvalidLabel, SecurityChangeResult.RequiredUserRole, SecurityChangeResult.CredentialAlreadyExists -> rejected(ApiErrorCode.INVALID_INPUT)
}
private fun rejected(code: ApiErrorCode) = SecurityChangePayload("REJECTED", ApiErrorDto(code, code.name.lowercase().replace('_', ' ')))
private fun DataFetchingEnvironment.accountPage() = DiscoveryGraphqlMapping.page(getArgument("first"), getArgument("after"))

/** Management references are typed and opaque but are not bearer credentials or Node identities. */
internal object ManagementIds {
  fun encode(type: String, id: UUID) = Base64.getUrlEncoder().withoutPadding().encodeToString("v1:$type:$id".toByteArray(Charsets.UTF_8))
  fun decode(type: String, id: String): UUID = try {
    require(id.length in 1..128 && Regex("[A-Za-z0-9_-]+").matches(id))
    val parts = String(Base64.getUrlDecoder().decode(id), Charsets.UTF_8).split(':')
    require(parts.size == 3 && parts[0] == "v1" && parts[1] == type)
    UUID.fromString(parts[2]).also { require(encode(type, it) == id) }
  } catch (_: IllegalArgumentException) { throw GlobalIdException() }
}
private const val PASSKEYS = "account.passkeys"
private const val SESSIONS = "account.sessions"
