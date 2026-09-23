package dev.moreal.finds.domain.identity

/** Shared account roles; the identity aggregate adds policy around this type. */
enum class UserRole {
  USER,
  ADMIN,
}
