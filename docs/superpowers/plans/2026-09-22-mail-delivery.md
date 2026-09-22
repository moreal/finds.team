# Mail Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build provider-neutral Kotlin mail modules, SMTP and SES transports, resilient priority fallback, and an encrypted transactional outbox for verification-code delivery.

**Architecture:** `mail/*` modules contain reusable contracts and transport decorators with no finds.team dependency. `adapter-notification` maps authentication semantics into messages, while `adapter-persistence` stores encrypted outbox payloads atomically with OTP challenges.

**Tech Stack:** Kotlin/JVM 25, kotlinx.coroutines, Ktor client, Jakarta Mail/Angus, AWS SDK SES v2, jOOQ, PostgreSQL, Testcontainers, OpenTelemetry/Micrometer adapters

**Spec:** `docs/superpowers/specs/2026-09-22-mail-transport-design.md`

## Global Constraints

- `mail/*` must not depend on backend domain/application modules.
- `MailMessageId` stays stable across retries and providers.
- Permanent rejection never falls back; indeterminate delivery never blindly falls back.
- OTP plaintext is encrypted in the outbox and redacted after acceptance or expiry.
- Mail bodies, recipient local parts, OTPs, and provider secrets never enter logs.
- SMTP and Amazon SES are both implemented before this plan is complete.

## Review Focus

- A timeout after possible provider acceptance must produce `Indeterminate`, not retryable rejection.
- Same message id must reach every retry/provider attempt unchanged.
- Dispatcher crash after lease acquisition must make the row eligible again after lease expiry.
- Expired OTP outbox rows must be redacted and never delivered.
- A database-only attacker must not recover OTP plaintext from the outbox payload.

---

### Task 1: Mail module structure and core value types

**Files:**
- Modify: `backend/settings.gradle.kts`
- Create: `backend/mail-core/build.gradle.kts`
- Create: `backend/mail-core/src/main/kotlin/dev/moreal/mail/MailTypes.kt`
- Create: `backend/mail-core/src/main/kotlin/dev/moreal/mail/MailTransport.kt`
- Create: `backend/mail-core/src/test/kotlin/dev/moreal/mail/MailTypesTest.kt`

**Interfaces:**
- Produces: `MailMessageId`, `Mailbox`, `Recipients`, `MailContent`, `MailMessage`, `MailProvider`, `MailFailure`, `MailDeliveryResult`, and `MailTransport.send`.

- [ ] **Step 1: Write failing value tests**

Test rejection of blank/CRLF-injected subjects, invalid mailbox addresses, empty recipients, duplicate recipients, and blank message content. Test `MailMessageId` UUID parsing.

- [ ] **Step 2: Run the missing-module test**

Run: `cd backend && ./gradlew :mail-core:test`

Expected: FAIL because `mail-core` is not included.

- [ ] **Step 3: Add the module and minimal immutable types**

Use sealed results rather than transport-thrown control-flow exceptions:

```kotlin
fun interface MailTransport {
  suspend fun send(message: MailMessage): MailDeliveryResult
}

sealed interface MailDeliveryResult {
  data class Accepted(val provider: MailProvider, val providerMessageId: String) : MailDeliveryResult
  data class Rejected(val provider: MailProvider, val failure: MailFailure, val retryable: Boolean) : MailDeliveryResult
  data class Indeterminate(val provider: MailProvider, val failure: MailFailure) : MailDeliveryResult
}
```

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :mail-core:test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/settings.gradle.kts backend/mail-core
git commit -m "feat(mail): define provider-neutral transport contract" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 2: Recording and scripted test transports

**Files:**
- Create: `backend/mail-transport-testing/build.gradle.kts`
- Create: `backend/mail-transport-testing/src/main/kotlin/dev/moreal/mail/testing/RecordingMailTransport.kt`
- Create: `backend/mail-transport-testing/src/main/kotlin/dev/moreal/mail/testing/ScriptedMailTransport.kt`
- Create: `backend/mail-transport-testing/src/test/kotlin/dev/moreal/mail/testing/RecordingMailTransportTest.kt`
- Modify: `backend/settings.gradle.kts`

**Interfaces:**
- Consumes: `MailTransport`.
- Produces: thread-safe `messages(): List<MailMessage>` and scripted result queue.

- [ ] **Step 1: Write concurrency and immutability tests**

Send 100 messages concurrently, assert every id appears once, and assert the returned snapshot cannot mutate internal state.

- [ ] **Step 2: Implement minimal test transports**

Use `Mutex` around internal collections and make `ScriptedMailTransport` fail clearly when its result queue is exhausted.

- [ ] **Step 3: Verify**

Run: `cd backend && ./gradlew :mail-transport-testing:test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/settings.gradle.kts backend/mail-transport-testing
git commit -m "test(mail): add deterministic transports" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 3: Retry and priority pool decorators

**Files:**
- Create: `backend/mail-transport-retry/build.gradle.kts`
- Create: `backend/mail-transport-retry/src/main/kotlin/dev/moreal/mail/retry/RetryMailTransport.kt`
- Create: `backend/mail-transport-retry/src/test/kotlin/dev/moreal/mail/retry/RetryMailTransportTest.kt`
- Create: `backend/mail-transport-pool/build.gradle.kts`
- Create: `backend/mail-transport-pool/src/main/kotlin/dev/moreal/mail/pool/PriorityMailTransport.kt`
- Create: `backend/mail-transport-pool/src/test/kotlin/dev/moreal/mail/pool/PriorityMailTransportTest.kt`
- Create: `backend/mail-observability/build.gradle.kts`
- Create: `backend/mail-observability/src/main/kotlin/dev/moreal/mail/observability/ObservedMailTransport.kt`
- Create: `backend/mail-observability/src/test/kotlin/dev/moreal/mail/observability/ObservedMailTransportTest.kt`
- Modify: `backend/settings.gradle.kts`

**Interfaces:**
- Produces: `RetryPolicy(maxAttempts, initialDelay, maximumDelay, jitterRatio)` and `PriorityMailTransport(entries)`.
- Consumes: child `MailTransport`s and a suspend delay/random port for deterministic tests.

- [ ] **Step 1: Write the complete result matrix tests**

Cover accepted-first-attempt, retryable-then-accepted, permanent rejection, indeterminate, exhausted retry, priority fallback after definite retryable rejection, and unchanged `MailMessageId` across all attempts.

- [ ] **Step 2: Implement retry without exception ambiguity**

Provider adapters must catch transport exceptions and classify them before decorators see results. Decorators never infer that a timeout is safe to retry.

- [ ] **Step 3: Implement priority selection**

Try entries in descending priority. Move to the next provider only after a definite retryable rejection has exhausted its provider-local retry policy.

- [ ] **Step 4: Implement the observability decorator**

Record provider/result-class/latency metrics and trace message id, attempt, purpose, and correlation id. Tests capture logs/spans and assert recipient, subject, body, and OTP content are absent.

- [ ] **Step 5: Verify**

Run: `cd backend && ./gradlew :mail-transport-retry:test :mail-transport-pool:test :mail-observability:test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/settings.gradle.kts backend/mail-transport-retry backend/mail-transport-pool backend/mail-observability
git commit -m "feat(mail): add retry and priority fallback" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 4: SMTP transport

**Files:**
- Modify: `backend/gradle/libs.versions.toml`
- Create: `backend/mail-transport-smtp/build.gradle.kts`
- Create: `backend/mail-transport-smtp/src/main/kotlin/dev/moreal/mail/smtp/SmtpMailTransport.kt`
- Create: `backend/mail-transport-smtp/src/test/kotlin/dev/moreal/mail/smtp/SmtpMailTransportTest.kt`
- Modify: `backend/settings.gradle.kts`

**Interfaces:**
- Produces: `SmtpSettings(host, port, tlsMode, username, password, connectTimeout, readTimeout)`.

- [ ] **Step 1: Write integration tests against a local SMTP server**

Assert envelope recipients, From/Reply-To, UTF-8 Korean subject, text/HTML alternatives, stable `Message-ID` derived from `MailMessageId`, authentication failure classification, and timeout classification as indeterminate after DATA begins.

- [ ] **Step 2: Implement SMTP MIME rendering**

Use Angus Mail/Jakarta Mail inside this adapter only. Sanitize header values and never log the session properties or message body.

- [ ] **Step 3: Verify**

Run: `cd backend && ./gradlew :mail-transport-smtp:test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/settings.gradle.kts backend/gradle/libs.versions.toml backend/mail-transport-smtp
git commit -m "feat(mail): add SMTP transport" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 5: Amazon SES transport

**Files:**
- Modify: `backend/gradle/libs.versions.toml`
- Create: `backend/mail-transport-ses/build.gradle.kts`
- Create: `backend/mail-transport-ses/src/main/kotlin/dev/moreal/mail/ses/SesMailTransport.kt`
- Create: `backend/mail-transport-ses/src/test/kotlin/dev/moreal/mail/ses/SesMailTransportTest.kt`
- Modify: `backend/settings.gradle.kts`

**Interfaces:**
- Produces: `SesSettings(region, configurationSet)` and an adapter accepting an injected SES client interface.

- [ ] **Step 1: Write request mapping and failure tests**

Assert `MailMessageId` is sent as a provider tag, Korean content maps correctly, throttling/service-unavailable are retryable rejection only when the SDK confirms no acceptance, message rejection is permanent, and client timeout is indeterminate.

- [ ] **Step 2: Implement the SES adapter**

Keep AWS SDK types private to the module. Return the SES message id on acceptance.

- [ ] **Step 3: Verify**

Run: `cd backend && ./gradlew :mail-transport-ses:test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/settings.gradle.kts backend/gradle/libs.versions.toml backend/mail-transport-ses
git commit -m "feat(mail): add SES transport" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 6: Encrypted mail outbox persistence

**Files:**
- Create: `backend/adapter-persistence/src/main/resources/db/migration/V2__mail_outbox.sql`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/port/MailOutboxPorts.kt`
- Create: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqMailOutbox.kt`
- Create: `backend/adapter-persistence/src/test/kotlin/dev/moreal/finds/persistence/JooqMailOutboxTest.kt`
- Create: `backend/application/src/testFixtures/kotlin/dev/moreal/finds/application/testing/MailOutboxFake.kt`

**Interfaces:**
- Produces: `enqueue`, `leaseBatch`, `recordAttempt`, `complete`, `reschedule`, and `redactExpired` operations keyed by `MailMessageId`.

- [ ] **Step 1: Write failing migration and leasing tests**

Test unique message id, `SKIP LOCKED` leasing across two workers, expired lease recovery, accepted completion, encrypted payload redaction, and no plaintext OTP match in any text/bytea column.

- [ ] **Step 2: Add schema**

Create `mail_outbox` and `mail_delivery_attempts`. Store `payload_ciphertext`, `payload_nonce`, `key_version`, purpose, expiry, state, lease owner/expiry, next attempt time, and provider receipt metadata. Encrypt with AES-256-GCM, a unique random 96-bit nonce, and authenticated additional data containing message id, purpose, and expiry.

- [ ] **Step 3: Implement jOOQ adapter**

The encryption service is injected; jOOQ never receives plaintext beyond the bound ciphertext. `redactExpired` nulls ciphertext/nonce and records `EXPIRED`.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :adapter-persistence:test --tests '*MailOutbox*'`

Expected: PASS against PostgreSQL.

- [ ] **Step 5: Commit**

```bash
git add backend/application backend/adapter-persistence
git commit -m "feat(persistence): add encrypted mail outbox" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 7: Notification adapter and dispatcher

**Files:**
- Create: `backend/adapter-notification/build.gradle.kts`
- Create: `backend/adapter-notification/src/main/kotlin/dev/moreal/finds/notification/MailVerificationCodeNotifier.kt`
- Create: `backend/adapter-notification/src/main/kotlin/dev/moreal/finds/notification/MailOutboxDispatcher.kt`
- Create: `backend/adapter-notification/src/test/kotlin/dev/moreal/finds/notification/MailVerificationCodeNotifierTest.kt`
- Create: `backend/adapter-notification/src/test/kotlin/dev/moreal/finds/notification/MailOutboxDispatcherTest.kt`
- Modify: `backend/settings.gradle.kts`
- Modify: `backend/bootstrap/build.gradle.kts`
- Modify: `backend/bootstrap/src/main/kotlin/dev/moreal/finds_team/config/RuntimeConfiguration.kt`
- Modify: `backend/bootstrap/src/main/resources/application.yml`

**Interfaces:**
- Implements: application `VerificationCodeNotifier`.
- Consumes: outbox port, payload crypto, priority mail transport, clock, and configured sender.

- [ ] **Step 1: Write failing template and delivery tests**

Assert purpose-specific Korean subject/body, exact expiry copy, no account-existence disclosure, correct outbox message id, accepted completion, retry schedule, permanent failure, and indeterminate hold.

- [ ] **Step 2: Implement adapter and worker**

Compose `Retry(SMTP)` and `Retry(SES)` under `PriorityMailTransport`. The dispatcher handles a bounded batch and records metrics without logging recipient/OTP.

- [ ] **Step 3: Wire configuration**

Add validated properties for sender, enabled providers, priority, timeouts, leases, retry bounds, and payload-encryption key version. Production startup fails when no production transport is enabled; test/dev profiles may use recording transport.

- [ ] **Step 4: Verify all mail modules**

Run: `cd backend && ./gradlew :mail-core:test :mail-transport-testing:test :mail-transport-retry:test :mail-transport-pool:test :mail-observability:test :mail-transport-smtp:test :mail-transport-ses:test :adapter-notification:test :adapter-persistence:test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(notification): deliver verification mail resiliently" -m "Assisted-by: Codex:gpt-5.6-sol"
```
