# Transactional Audit and Idempotency Design

## Goal

Make every security-sensitive or administrator state change idempotent and atomically auditable without adopting full CQRS or event sourcing.

## Command/query boundary

Queries remain side-effect-free application services backed by repositories. Commands receive an `Actor`, correlation metadata, and an explicit idempotency key where external retry is possible. PostgreSQL remains the write and read source of truth; no separate projection database is introduced.

## Transaction port

Application defines a framework-neutral unit-of-work boundary:

```kotlin
interface TransactionPort {
  fun <T> execute(block: (TransactionContext) -> T): T
}

interface TransactionContext {
  val users: UserRepository
  val careerSites: CareerSiteRepository
  val credentials: PasskeyCredentialRepository
  val commandRequests: CommandRequestStore
  val auditLog: AuditLog
  val outbox: Outbox
}
```

The jOOQ adapter creates transaction-scoped repository implementations over the configuration supplied by `DSLContext.transactionResult`. Application code never imports jOOQ or Spring transaction annotations. Network calls, WebAuthn verification, DNS/provider discovery, and mail delivery happen before or after the short database transaction, never inside it.

## Idempotent command algorithm

Every supported command uses the following algorithm inside one transaction:

1. Canonically serialize the validated semantic command and hash it.
2. Look up `(scope, operation, idempotency_key)`.
3. If no row exists, reserve the key.
4. If a row exists with another request hash, return `IdempotencyConflict`.
5. If a completed row exists with the same hash, return the stored semantic result.
6. Apply the business change.
7. Append the audit event and required outbox events.
8. Store the command result and mark the request complete.
9. Commit all rows together.

The unique database constraint on `(scope, operation, idempotency_key)` resolves concurrent first attempts. The losing transaction reloads and follows steps 4 or 5. Stored results contain stable identifiers and typed outcome fields, not secrets or entire HTTP responses.

Scopes are the actor user id for authenticated commands, `SYSTEM` plus job identity for scheduled commands, and a keyed normalized-email hash for anonymous enrollment commands. Ordinary request records expire after 24 hours. Role, credential, recovery, session-revocation, and administrator configuration command records are retained with their audit events for the audit retention period.

## Audit event model

`audit_events` is an append-only ledger with:

- event id and schema version
- occurrence time
- actor kind (`USER` or `SYSTEM`) and optional actor user id
- stable action identifier
- target type and stable target id
- request and correlation ids
- outcome
- minimal redacted JSON details

Audit actions include role changes, career-site registration/settings changes, manual crawl triggers, Passkey registration/removal, recovery-code rotation, account recovery completion, session revocation, and administrator configuration changes.

OTP values or hashes, recovery codes or hashes, session identifiers, CSRF tokens, WebAuthn challenge or credential material, provider secrets, raw email addresses, and full request bodies are forbidden in audit details.

## Audit, security events, and logs

- `audit_events` records successful committed state changes.
- `security_events` records denied authorization, OTP failures, challenge replay, suspicious throttling, and authentication failures.
- structured application logs support diagnosis and are not the audit source of truth.
- an audit-export outbox can later replicate ledger entries to immutable external storage.

Business rows and their audit event are written in the same transaction. An audit insert failure rolls back the business change. A denied command has no business transaction to pair and records a separate security event.

## Append-only enforcement

The runtime database role receives `INSERT` and `SELECT`, not `UPDATE` or `DELETE`, on `audit_events`. A database trigger rejects mutation or deletion as defense in depth. Retention or legal deletion uses a separate operational role and documented procedure. Audit pages never offer edit or delete controls.

## Existing code impact

The current `JooqSuccessfulCrawlAdapter` already owns an atomic crawl-completion transaction and becomes the pattern for transaction-scoped commands. `RegisterCareerSite` currently calls a single repository insert; its write phase moves behind the transaction port so site insertion, idempotency result, and audit append are inseparable. Provider discovery remains before the transaction.

## Verification

- Audit insert failure leaves no business change.
- Business constraint failure leaves no audit event or completed idempotency record.
- A successful command produces exactly one business effect and one audit event.
- Same key and same request returns the original result under sequential and concurrent retries.
- Same key and a different request returns a conflict without a business effect.
- Crashes before commit leave no partial rows; crashes after commit are safely replayable.
- Architecture tests keep transaction implementation types outside application and domain.
- Schema/serialization tests reject forbidden audit detail fields.
