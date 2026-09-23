package dev.moreal.finds.application.port

import dev.moreal.finds.application.audit.AuditEvent

/** Append-only, transaction-scoped ledger. An append failure must abort the business transaction. */
fun interface AuditLog {
  fun append(event: AuditEvent)
}
