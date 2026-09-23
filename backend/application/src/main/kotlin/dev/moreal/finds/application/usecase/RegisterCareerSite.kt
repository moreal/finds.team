package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.audit.*
import dev.moreal.finds.application.command.CanonicalCommandEncoder
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CrawlSettings
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.identity.UserId
import dev.moreal.finds.domain.identity.UserRole
import java.util.UUID

data class RegisterCareerSiteCommand(
  val url: String,
  val displayName: String,
  val actor: Actor,
  val metadata: CommandMetadata,
  val sessionId: UserSessionId? = null,
)

sealed interface RegisterCareerSiteResult {
  data object Forbidden : RegisterCareerSiteResult
  data object IdempotencyConflict : RegisterCareerSiteResult
  data object InvalidIdempotencyKey : RegisterCareerSiteResult
  data class Registered(val site: CareerSite) : RegisterCareerSiteResult

  data class InvalidUrl(val reason: String) : RegisterCareerSiteResult

  data object UnsupportedProvider : RegisterCareerSiteResult

  data class AmbiguousProvider(val providers: Set<SourceProvider>) : RegisterCareerSiteResult

  data class DiscoveryFailed(val reason: String) : RegisterCareerSiteResult

  data class AlreadyRegistered(val site: CareerSite) : RegisterCareerSiteResult

  data class InvalidDisplayName(val reason: String) : RegisterCareerSiteResult
}

class RegisterCareerSite(
  private val transactions: TransactionPort,
  private val discovery: SourceDiscoveryPort,
  private val clock: ClockPort,
  private val defaultCrawlSettings: CrawlSettings = CrawlSettings(),
  private val securityEvents: SecurityEventPort,
) {
  suspend fun execute(command: RegisterCareerSiteCommand): RegisterCareerSiteResult {
    val result = executeCommand(command)
    if (result == RegisterCareerSiteResult.Forbidden) securityEvents.denied(SecurityEventAction.REGISTRATION_DENIED,
      clock.now(), command.metadata, (command.actor as? Actor.User)?.userId)
    return result
  }

  private suspend fun executeCommand(command: RegisterCareerSiteCommand): RegisterCareerSiteResult {
    val actor = command.actor as? Actor.User ?: return RegisterCareerSiteResult.Forbidden
    if (UserRole.ADMIN !in actor.roles || !actor.hasRecentPasskeyAuthentication(clock.now()))
      return RegisterCareerSiteResult.Forbidden
    val principal = SessionPrincipal(actor, command.sessionId ?: return RegisterCareerSiteResult.Forbidden)
    val key = CommandRequestKey(actor.userId.toString(), OPERATION,
      command.metadata.idempotencyKey ?: return RegisterCareerSiteResult.InvalidIdempotencyKey)
    val siteUrl = when (val parsed = SiteUrl.parse(command.url)) {
      is SiteUrlResult.Invalid -> return RegisterCareerSiteResult.InvalidUrl(parsed.reason)
      is SiteUrlResult.Valid -> parsed.url
    }
    if (command.displayName.isBlank()) {
      return RegisterCareerSiteResult.InvalidDisplayName("Display name must not be blank")
    }
    if (command.displayName != command.displayName.trim()) {
      return RegisterCareerSiteResult.InvalidDisplayName("Display name must be trimmed")
    }

    val hash = try {
      CanonicalCommandEncoder.hash(mapOf("url" to siteUrl.value.toString(), "displayName" to command.displayName))
    } catch (_: IllegalArgumentException) {
      return RegisterCareerSiteResult.InvalidDisplayName("Display name must contain valid Unicode")
    }
    val preflight = transactions.execute { tx ->
      if (!tx.authorized(principal)) return@execute Preflight(false, null)
      Preflight(true, tx.careerSites.findByHost(siteUrl.host))
    }
    if (!preflight.authorized) return RegisterCareerSiteResult.Forbidden
    // Existing hosts (including successful retries) need no network work. Recheck after discovery
    // because another command may register this host while the network request is in flight.
    val detected = if (preflight.existing == null) discovery.detect(siteUrl) else null
    return try {
      transactions.execute { tx ->
        if (!tx.authorized(principal)) return@execute RegisterCareerSiteResult.Forbidden
        val now = clock.now()
        when (val reservation = tx.commandRequests.reserve(CommandRequest(key, hash, now, CommandRetention.AUDIT))) {
          CommandReservation.Conflict -> return@execute RegisterCareerSiteResult.IdempotencyConflict
          is CommandReservation.Replay -> return@execute tx.replay(reservation.result)
          CommandReservation.Reserved -> Unit
        }
        val existing = tx.careerSites.findByHost(siteUrl.host)
        val result = if (existing != null) RegisterCareerSiteResult.AlreadyRegistered(existing) else {
          val provider = when (detected) {
            is ProviderDiscoveryResult.Detected -> detected.provider
            ProviderDiscoveryResult.Unsupported -> throw DiscoveryRejected(RegisterCareerSiteResult.UnsupportedProvider)
            is ProviderDiscoveryResult.Ambiguous -> throw DiscoveryRejected(RegisterCareerSiteResult.AmbiguousProvider(detected.providers.toSet()))
            is ProviderDiscoveryResult.Failed -> throw DiscoveryRejected(RegisterCareerSiteResult.DiscoveryFailed(detected.reason))
            null -> error("Career site disappeared during registration")
          }
          when (val inserted = tx.careerSites.insert(NewCareerSite(siteUrl, provider, command.displayName, defaultCrawlSettings))) {
            is InsertCareerSiteResult.Duplicate -> RegisterCareerSiteResult.AlreadyRegistered(inserted.existing)
            is InsertCareerSiteResult.Inserted -> {
              tx.auditLog.append(AuditEvent(UUID.randomUUID(), 1, now, actor, AuditAction.CAREER_SITE_REGISTERED,
                "career_site", inserted.site.id.value.toString(), command.metadata.requestId, command.metadata.correlationId,
                AuditOutcome.SUCCEEDED, AuditDetails.from(AuditAction.CAREER_SITE_REGISTERED, mapOf("provider" to provider.name))))
              RegisterCareerSiteResult.Registered(inserted.site)
            }
          }
        }
        val (outcome, site) = when (result) {
          is RegisterCareerSiteResult.Registered -> "CREATED" to result.site
          is RegisterCareerSiteResult.AlreadyRegistered -> "ALREADY_REGISTERED" to result.site
          else -> error("Unexpected registration result")
        }
        tx.commandRequests.complete(key, StoredCommandResult(1, OPERATION, outcome,
          mapOf("career_site" to CommandResourceId.Number(site.id.value))))
        result
      }
    } catch (rejected: DiscoveryRejected) {
      // No business effect: roll back the pending reservation so discovery can be retried.
      // Checking reserve first preserves replay/conflict even if discovery is now unavailable.
      rejected.result
    }
  }

  private fun TransactionContext.authorized(principal: SessionPrincipal): Boolean {
    val user = lockUsers(setOf(UserId(principal.actor.userId)))[UserId(principal.actor.userId)]
    return authorize(principal, user, clock.now(), recent = true) && UserRole.ADMIN in checkNotNull(user).roles
  }

  private fun TransactionContext.replay(stored: StoredCommandResult): RegisterCareerSiteResult {
    stored.requireSupported(OPERATION, 1)
    if (stored.resourceIds.keys != setOf("career_site")) throw UnsupportedCommandResultException()
    val id = stored.resourceIds["career_site"] as? CommandResourceId.Number ?: throw UnsupportedCommandResultException()
    val site = careerSites.findById(CareerSiteId(id.value)) ?: throw UnsupportedCommandResultException()
    return when (stored.outcome) {
      "CREATED" -> RegisterCareerSiteResult.Registered(site)
      "ALREADY_REGISTERED" -> RegisterCareerSiteResult.AlreadyRegistered(site)
      else -> throw UnsupportedCommandResultException()
    }
  }

  private data class Preflight(val authorized: Boolean, val existing: CareerSite?)
  private class DiscoveryRejected(val result: RegisterCareerSiteResult) : RuntimeException(null, null, false, false)
  private companion object { const val OPERATION = "career_site.register" }
}
