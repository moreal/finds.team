package dev.moreal.finds.domain.identity

/** USER is required on every account; ADMIN adds authorization without changing authentication. */
enum class UserRole {
  USER,
  ADMIN,
}
