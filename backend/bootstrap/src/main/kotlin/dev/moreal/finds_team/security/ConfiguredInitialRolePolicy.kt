package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.InitialRolePolicyPort
import dev.moreal.finds.domain.identity.EmailAddress
import dev.moreal.finds.domain.identity.UserRole

class ConfiguredInitialRolePolicy(emails: Set<String>) : InitialRolePolicyPort {
  private val allowed = emails.map {
    require('*' !in it) { "Administrator allowlist requires exact addresses" }
    EmailAddress(it).normalized
  }.toSet()
  override fun rolesForVerifiedEmail(email: EmailAddress): Set<UserRole> =
    if (email.normalized in allowed) setOf(UserRole.USER, UserRole.ADMIN) else setOf(UserRole.USER)
}
