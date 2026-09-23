package dev.moreal.finds.application.audit

import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.domain.career.SourceProvider
import dev.moreal.finds.domain.identity.UserRole
import java.time.Instant
import java.util.Collections
import java.util.UUID

/** Persist [wireName], never the Kotlin enum name. */
enum class AuditAction(val wireName: String, internal val allowedDetailKeys: Set<String> = emptySet()) {
  ROLE_GRANTED("role.granted", setOf("role")),
  ROLE_REVOKED("role.revoked", setOf("role")),
  CAREER_SITE_REGISTERED("career_site.registered", setOf("provider")),
  CAREER_SITE_SETTINGS_CHANGED("career_site.settings_changed", setOf("enabled")),
  MANUAL_CRAWL_TRIGGERED("crawl.manually_triggered"),
  PASSKEY_REGISTERED("passkey.registered"),
  PASSKEY_REMOVED("passkey.removed"),
  RECOVERY_CODE_ROTATED("recovery_code.rotated"),
  ACCOUNT_RECOVERY_COMPLETED("recovery.completed"),
  SESSION_REVOKED("session.revoked"),
  ADMIN_CONFIGURATION_CHANGED("admin_configuration.changed");

  companion object {
    private val byWireName = entries.associateBy(AuditAction::wireName)

    fun fromWireName(wireName: String): AuditAction =
      requireNotNull(byWireName[wireName]) { "Unknown audit action" }
  }
}

enum class AuditOutcome(val wireName: String) {
  SUCCEEDED("succeeded"),
}

/** Only action-specific, categorical fields may enter the ledger. */
class AuditDetails private constructor(
  val action: AuditAction,
  val fields: Map<String, String>,
) {
  companion object {
    fun empty(action: AuditAction): AuditDetails = from(action, emptyMap())

    fun from(action: AuditAction, fields: Map<String, String>): AuditDetails {
      require(fields.keys.all { it in action.allowedDetailKeys }) {
        "Unsupported audit detail key"
      }
      fields.forEach { (key, value) ->
        val valid = when (key) {
          "role" -> UserRole.entries.any { it.name == value }
          "provider" -> SourceProvider.entries.any { it.name == value }
          "enabled" -> value == "true" || value == "false"
          else -> false
        }
        require(valid) { "Unsupported audit detail value" }
      }
      return AuditDetails(action, Collections.unmodifiableMap(LinkedHashMap(fields)))
    }
  }
}

data class AuditEvent(
  val id: UUID,
  val schemaVersion: Int,
  val occurredAt: Instant,
  val actor: Actor,
  val action: AuditAction,
  val targetType: String,
  val targetId: String,
  val requestId: UUID,
  val correlationId: UUID,
  val outcome: AuditOutcome,
  val details: AuditDetails = AuditDetails.empty(action),
) {
  init {
    require(schemaVersion > 0) { "Audit schema version must be positive" }
    require(TARGET_TYPE_PATTERN.matches(targetType)) { "Audit target type must be a stable name" }
    require(TARGET_ID_PATTERN.matches(targetId)) { "Audit target ID must be a stable opaque ID" }
    require(details.action == action) { "Audit details must match the action" }
  }

  override fun toString(): String =
    "AuditEvent(id=$id, action=${action.wireName}, outcome=${outcome.wireName})"

  companion object {
    private val TARGET_TYPE_PATTERN = Regex("[a-z][a-z0-9_]*")
    private val TARGET_ID_PATTERN = Regex("[A-Za-z0-9:_-]{1,128}")
  }
}
