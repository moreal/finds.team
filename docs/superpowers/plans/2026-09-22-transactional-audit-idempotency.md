# Transactional Audit and Idempotency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make externally retryable commands idempotent and atomically append an immutable audit event with each committed business change.

**Architecture:** Application defines Actor, command metadata, audit events, and a transaction port. The jOOQ adapter supplies transaction-scoped repositories and persists business state, command results, audit events, and outbox rows on one connection.

**Tech Stack:** Kotlin/JVM, jOOQ, PostgreSQL, Flyway, Testcontainers, JUnit

**Spec:** `docs/superpowers/specs/2026-09-22-transactional-audit-design.md`

## Global Constraints

- Do not introduce full CQRS, event sourcing, or a second database.
- No external I/O may occur inside a database transaction.
- Same key/same request returns the original typed result; same key/different request conflicts.
- Audit events are append-only and contain no authentication or contact secrets.
- Application and domain must not import jOOQ, Spring, JDBC, or servlet types.

## Review Focus

- Concurrent first use of one idempotency key must produce one effect and one audit event.
- Audit insertion failure must roll back the business mutation and idempotency completion.
- Canonical request hashing must be stable across map/property ordering.
- A denied command must create a security event without a misleading successful audit event.
- Runtime database credentials must be unable to update or delete audit rows.

---

### Task 1: Framework-neutral actor, audit, and command metadata

**Files:**
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/security/Actor.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/audit/AuditEvent.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/command/CommandMetadata.kt`
- Create: `backend/application/src/test/kotlin/dev/moreal/finds/application/audit/AuditEventTest.kt`
- Modify: `backend/application/src/test/kotlin/dev/moreal/finds/application/ApplicationArchitectureTest.kt`

**Interfaces:**
- Produces: `Actor`, `UserRole`, `AuthenticationStrength`, `CommandMetadata(requestId, correlationId, idempotencyKey)`, `AuditEvent`, and stable `AuditAction` identifiers.

- [ ] **Step 1: Write failing validation tests**

Test UUID parsing, blank target rejection, stable action wire names, recent-auth calculation, and rejection of secret-shaped detail keys (`otp`, `token`, `cookie`, `credential`, `recoveryCode`).

- [ ] **Step 2: Expand architecture import bans**

Add `com.webauthn4j`, `jakarta.servlet`, and `graphql` to forbidden application imports.

- [ ] **Step 3: Implement minimal values**

Use immutable data and an allowlist of audit detail keys per action rather than accepting arbitrary maps from controllers.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :application:test --tests '*AuditEventTest' --tests '*ApplicationArchitectureTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/application
git commit -m "feat(application): define command audit context" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 2: Transaction and idempotency ports

**Files:**
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/port/TransactionPorts.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/port/AuditPorts.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/port/CommandRequestPorts.kt`
- Create: `backend/application/src/testFixtures/kotlin/dev/moreal/finds/application/testing/FakeTransaction.kt`
- Create: `backend/application/src/test/kotlin/dev/moreal/finds/application/command/IdempotentCommandTest.kt`

**Interfaces:**
- Produces: `TransactionPort.execute`, transaction-scoped stores, `CommandRequestStore.reserve/complete`, and `AuditLog.append`.

- [ ] **Step 1: Write the application-level result matrix**

Use a fake transaction snapshot to prove rollback on exception, replay of completed same-hash requests, conflict on another hash, and no duplicate audit event.

- [ ] **Step 2: Define canonical request hashing**

Introduce `CanonicalCommandEncoder` that sorts field names, emits explicit nulls, and hashes UTF-8 bytes with SHA-256. Tests include commands constructed with different map insertion order.

- [ ] **Step 3: Implement fake transaction semantics**

Clone fake state at transaction entry and publish it only on successful return so application tests exercise rollback behavior.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :application:test --tests '*IdempotentCommandTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/application
git commit -m "feat(application): define transactional command ports" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 3: PostgreSQL command and audit schema

**Files:**
- Create: `backend/adapter-persistence/src/main/resources/db/migration/V3__command_audit.sql`
- Create: `backend/adapter-persistence/src/test/kotlin/dev/moreal/finds/persistence/CommandAuditMigrationTest.kt`
- Create: `deploy/postgres/00-create-runtime-role.sh`
- Modify: `compose.yaml`
- Create: `backend/bootstrap/src/main/kotlin/dev/moreal/finds_team/config/MigrationDataSourceConfiguration.kt`
- Modify: `backend/bootstrap/src/main/resources/application.yml`
- Create: `backend/bootstrap/src/test/kotlin/dev/moreal/finds_team/config/MigrationDataSourceConfigurationTest.kt`

**Interfaces:**
- Produces tables `command_requests`, `audit_events`, and `security_events`.

- [ ] **Step 1: Write failing migration invariants**

Assert uniqueness of `(scope, operation, idempotency_key)`, required request hash, append-only trigger behavior, indexed time/action/actor/target queries, JSON details object shape, and denial of audit update/delete through the runtime database role.

- [ ] **Step 2: Add migration**

Store typed result JSON without secret response fields. Add a trigger function that raises on audit update/delete. Add 24-hour expiry metadata for ordinary command requests and retention class for security-sensitive records. Grant the runtime role `SELECT, INSERT` on `audit_events` and deny `UPDATE, DELETE`.

- [ ] **Step 3: Separate migration and runtime credentials**

The Compose initialization script creates `finds_migrator` as schema owner and `finds_app` as the limited runtime role from environment-provided passwords. Flyway uses `FINDS_MIGRATION_DB_USER/PASSWORD`; the jOOQ runtime datasource uses `FINDS_DB_USER/PASSWORD`. Production startup rejects identical migrator/runtime usernames.

- [ ] **Step 4: Verify migration and credentials**

Run: `cd backend && ./gradlew :adapter-persistence:test --tests '*CommandAuditMigrationTest' :bootstrap:test --tests '*MigrationDataSourceConfigurationTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/adapter-persistence backend/bootstrap deploy/postgres compose.yaml
git commit -m "feat(persistence): add immutable audit ledger" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 4: jOOQ transaction adapter

**Files:**
- Create: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqTransactionAdapter.kt`
- Create: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqCommandRequestStore.kt`
- Create: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqAuditLog.kt`
- Create: `backend/adapter-persistence/src/test/kotlin/dev/moreal/finds/persistence/JooqTransactionAdapterTest.kt`
- Modify: `backend/bootstrap/src/main/kotlin/dev/moreal/finds_team/config/RuntimeConfiguration.kt`

**Interfaces:**
- Implements: `TransactionPort`, `CommandRequestStore`, and `AuditLog` over one transaction-scoped `DSLContext`.

- [ ] **Step 1: Write atomicity and concurrency tests**

Use two coroutines/contexts to race the same command key. Force audit insertion failure with an invalid allowed-detail check and assert business/idempotency rows roll back.

- [ ] **Step 2: Implement transaction-scoped context**

Inside `transactionResult`, create repository/store instances from `DSL.using(configuration)`. Do not reuse root-context repositories inside the block.

- [ ] **Step 3: Implement deterministic replay**

Serialize typed results with a versioned result-kind field; reject an unknown stored version as infrastructure failure rather than repeating the command.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :adapter-persistence:test --tests '*JooqTransactionAdapterTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/adapter-persistence backend/bootstrap
git commit -m "feat(persistence): execute commands atomically" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 5: Idempotent and audited career-site registration

**Files:**
- Modify: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/RegisterCareerSite.kt`
- Modify: `backend/application/src/test/kotlin/dev/moreal/finds/application/usecase/RegisterCareerSiteTest.kt`
- Modify: `backend/adapter-graphql/src/main/kotlin/dev/moreal/finds/graphql/FindsGraphqlFacade.kt`
- Modify: `schema/finds.graphqls`
- Modify: `backend/adapter-graphql/src/test/kotlin/dev/moreal/finds/graphql/FindsGraphqlFacadeTest.kt`

**Interfaces:**
- Changes: `RegisterCareerSiteCommand` adds `actor: Actor` and `metadata: CommandMetadata`; GraphQL input adds `idempotencyKey: ID!`.

- [ ] **Step 1: Write failing application tests**

Prove provider discovery occurs before transaction entry, successful insertion and audit are atomic, same request replays, changed request conflicts, non-admin returns forbidden before discovery, and duplicate host returns its typed existing result.

- [ ] **Step 2: Refactor the write phase**

Keep URL parsing and provider discovery outside the transaction. Recheck host uniqueness inside the transaction, reserve idempotency, insert, append `CAREER_SITE_REGISTERED`, and complete the stored result.

- [ ] **Step 3: Map the GraphQL input**

Require valid UUID syntax and pass the authenticated Actor from the adapter boundary.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :application:test --tests '*RegisterCareerSiteTest' :adapter-graphql:test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/application backend/adapter-graphql schema/finds.graphqls
git commit -m "feat(application): audit site registration" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 6: Idempotent manual crawl and security events

**Files:**
- Modify: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/CrawlSite.kt`
- Modify: `backend/application/src/test/kotlin/dev/moreal/finds/application/usecase/CrawlSiteTest.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/port/SecurityEventPort.kt`
- Create: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqSecurityEventLog.kt`
- Modify: `schema/finds.graphqls`
- Modify: `backend/adapter-graphql/src/main/kotlin/dev/moreal/finds/graphql/GraphqlRuntime.kt`

**Interfaces:**
- Manual `CrawlSiteCommand` consumes Actor/metadata and returns the original run on replay; scheduled trigger uses a stable system scope.

- [ ] **Step 1: Write failing command tests**

Prove a retried manual trigger returns one run id, authorization denial records a security event but no audit event, and source fetching never occurs inside the audit transaction.

- [ ] **Step 2: Separate trigger reservation from long crawl I/O**

Atomically reserve/create the crawl run and audit the trigger, commit, then fetch/reconcile through the existing bounded crawl path. Replays resolve to the reserved run.

- [ ] **Step 3: Verify**

Run: `cd backend && ./gradlew :application:test --tests '*CrawlSiteTest' :adapter-persistence:test :adapter-graphql:test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend schema/finds.graphqls
git commit -m "feat(crawl): make manual triggers idempotent" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 7: Audit query port and retention maintenance

**Files:**
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/SearchAuditEvents.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/port/AuditQueryPort.kt`
- Create: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqAuditQuery.kt`
- Create: `backend/application/src/test/kotlin/dev/moreal/finds/application/usecase/SearchAuditEventsTest.kt`
- Create: `backend/adapter-persistence/src/test/kotlin/dev/moreal/finds/persistence/JooqAuditQueryTest.kt`

**Interfaces:**
- Produces cursor search by actor/action/target/time and `PurgeExpiredCommandRequests` for ordinary 24-hour records only.

- [ ] **Step 1: Write authorization and stable-cursor tests**

Only admins may query audit events. Equal timestamps use event id tie-breakers. Cleanup preserves security-sensitive command rows.

- [ ] **Step 2: Implement query and cleanup adapters**

Keep audit reads separate from mutation stores. Cleanup runs as a bounded scheduled maintenance command.

- [ ] **Step 3: Verify the entire subproject**

Run: `cd backend && ./gradlew :application:test :adapter-persistence:test :adapter-graphql:test :bootstrap:test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend
git commit -m "feat(audit): expose immutable audit history" -m "Assisted-by: Codex:gpt-5.6-sol"
```
