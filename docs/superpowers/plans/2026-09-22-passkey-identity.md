# Passkey Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add public user accounts with email-OTP enrollment, Passkey-only login, two-proof recovery, role authorization, and server sessions while preserving framework-agnostic domain/application layers.

**Architecture:** Pure identity rules live in domain, use cases and ports in application, jOOQ stores identity state, and Spring Security WebAuthn handles only HTTP ceremonies, cryptographic verification, CSRF, and session integration at the outer adapter/bootstrap boundary.

**Tech Stack:** Kotlin/JVM, Spring Security WebAuthn, WebAuthn4J transitively, jOOQ/PostgreSQL, encrypted mail outbox, MockMvc, Playwright virtual authenticator

**Spec:** `docs/superpowers/specs/2026-09-22-identity-authentication-design.md`

## Global Constraints

- No passwords.
- Normal login accepts Passkey only; email OTP is enrollment/recovery only.
- Recovery requires email OTP plus the saved 128-bit recovery code.
- Recovery start never changes or locks an active user's global status.
- Completing recovery revokes every pre-recovery Passkey and session.
- Domain/application cannot import Spring, servlet, WebAuthn4J, GraphQL, jOOQ, or JDBC types.

## Review Focus

- Enumeration-safe OTP requests must be indistinguishable for registered and unregistered email addresses.
- Issuing a new OTP must not reset the account-level failed-attempt counter.
- WebAuthn challenge replay, RP mismatch, origin mismatch, and missing user verification must fail closed.
- Recovery session must be unable to call ordinary GraphQL or administrator operations.
- Concurrent recovery completion must leave exactly one new credential/recovery code and revoke every old session.

---

### Task 1: Identity domain model and policies

**Files:**
- Create: `backend/domain/src/main/kotlin/dev/moreal/finds/domain/identity/User.kt`
- Create: `backend/domain/src/main/kotlin/dev/moreal/finds/domain/identity/EmailAddress.kt`
- Create: `backend/domain/src/main/kotlin/dev/moreal/finds/domain/identity/CredentialPolicy.kt`
- Create: `backend/domain/src/main/kotlin/dev/moreal/finds/domain/identity/RecoveryCode.kt`
- Create: `backend/domain/src/test/kotlin/dev/moreal/finds/domain/identity/UserTest.kt`
- Create: `backend/domain/src/test/kotlin/dev/moreal/finds/domain/identity/EmailAddressTest.kt`
- Create: `backend/domain/src/test/kotlin/dev/moreal/finds/domain/identity/CredentialPolicyTest.kt`

**Interfaces:**
- Produces: `UserId`, `EmailAddress`, `UserStatus(PENDING_PASSKEY, ACTIVE, SUSPENDED)`, `UserRole(USER, ADMIN)`, opaque `CredentialId`, and pure credential/recovery decisions.

- [ ] **Step 1: Write table-driven failing tests**

Cover Unicode/ASCII email normalization policy, invalid addresses, activation only after first credential, last-credential removal rejection, suspended-user rejection, role grant/revoke invariants, and 128-bit recovery-code format/parse.

- [ ] **Step 2: Run domain tests**

Run: `cd backend && ./gradlew :domain:test --tests '*identity*'`

Expected: FAIL because types do not exist.

- [ ] **Step 3: Implement pure immutable values**

Use Java `IDN` for domains, preserve local-part case in display but use a documented case-folded normalized lookup key, and never expose recovery-code random generation inside the domain; accept random bytes as input.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :domain:test --tests '*identity*'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/domain
git commit -m "feat(domain): model user identity policy" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 2: Identity application ports and enrollment use cases

**Files:**
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/port/IdentityPorts.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/RequestEnrollmentOtp.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/VerifyEnrollmentOtp.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/CompletePasskeyEnrollment.kt`
- Create: `backend/application/src/testFixtures/kotlin/dev/moreal/finds/application/testing/IdentityFakes.kt`
- Create: `backend/application/src/test/kotlin/dev/moreal/finds/application/usecase/EnrollmentTest.kt`

**Interfaces:**
- Produces repositories for user, OTP challenge, credential metadata, restricted session, recovery hash, randomness, keyed hashing, initial-role policy, and `VerificationCodeNotifier`.

- [ ] **Step 1: Write failing enrollment flow tests**

Assert generic request response, eight-digit code, ten-minute expiry, five attempts, no counter reset on reissue, single consumption, restricted session issuance, first credential activation, audit event, outbox creation, and one-time recovery plaintext result.

- [ ] **Step 2: Implement request/verify commands**

`RequestEnrollmentOtpCommand(email, metadata)` creates OTP bytes with `SecureRandomPort`, stores only HMAC-SHA-256 using a versioned server pepper in the OTP store, and enqueues encrypted notification payload in the same transaction.

- [ ] **Step 3: Implement enrollment completion**

Consume already-verified credential material through a framework-neutral `VerifiedPasskeyRegistration` value. Atomically save it, activate user, invalidate enrollment session, append audit, and save the recovery code as HMAC-SHA-256 with a separate versioned pepper. `InitialRolePolicyPort` always returns `USER` plus `ADMIN` only when the verified normalized email is in the deployment bootstrap allowlist.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :application:test --tests '*EnrollmentTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/application
git commit -m "feat(application): implement account enrollment" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 3: Recovery and security-management use cases

**Files:**
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/RequestRecoveryOtp.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/VerifyRecoveryProofs.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/CompletePasskeyRecovery.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/ManagePasskeys.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/ManageSessions.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/ManageRoles.kt`
- Create: `backend/application/src/test/kotlin/dev/moreal/finds/application/usecase/RecoveryTest.kt`
- Create: `backend/application/src/test/kotlin/dev/moreal/finds/application/usecase/SecurityManagementTest.kt`

**Interfaces:**
- Produces: restricted recovery-session scope, new-credential-only recovery completion, Passkey/session listing and revocation, recovery rotation, and role commands.

- [ ] **Step 1: Write failing recovery tests**

Cover OTP-only rejection, recovery-code-only rejection, both proofs accepted, old code single use, restricted scope, new credential storage, all old credentials/sessions revoked, new code returned once, and concurrent second completion rejected.

- [ ] **Step 2: Write failing management tests**

Cover recent-auth five-minute boundary, last credential rejection, own-current-session behavior, other-session revocation, admin-only role changes, and idempotent role commands.

- [ ] **Step 3: Implement minimal use cases**

Recovery proof verification does not change `UserStatus`; it creates a separate expiring scoped session. Every successful sensitive change appends the allowlisted audit action.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :application:test --tests '*RecoveryTest' --tests '*SecurityManagementTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/application
git commit -m "feat(application): implement secure account recovery" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 4: Identity persistence schema and adapters

**Files:**
- Create: `backend/adapter-persistence/src/main/resources/db/migration/V4__identity.sql`
- Create: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqIdentityRepository.kt`
- Create: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqOtpChallengeStore.kt`
- Create: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqSessionRepository.kt`
- Create: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqCredentialRepository.kt`
- Create: `backend/adapter-persistence/src/test/kotlin/dev/moreal/finds/persistence/JooqIdentityRepositoryTest.kt`
- Create: `backend/adapter-persistence/src/test/kotlin/dev/moreal/finds/persistence/JooqOtpChallengeStoreTest.kt`
- Create: `backend/adapter-persistence/src/test/kotlin/dev/moreal/finds/persistence/JooqRecoveryConcurrencyTest.kt`

**Interfaces:**
- Implements: identity application repositories and single-consumption semantics.

- [ ] **Step 1: Write migration/adapter tests**

Assert unique normalized email, no credential-id collision, OTP/recovery hashes only, expiry indexes, atomic attempt increment, single consumption, and concurrent recovery winner.

- [ ] **Step 2: Add schema**

Create `users`, `user_roles`, `passkey_credentials`, `otp_challenges`, `recovery_codes`, `user_sessions`, and `webauthn_challenges` with explicit checks and foreign-key deletion behavior.

- [ ] **Step 3: Implement adapters**

Use compare-and-update predicates for attempt and consumption races. Store credential public key, signature counter, transports, backup eligibility/state, creation/last-used time, and user handle.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :adapter-persistence:test --tests '*Identity*' --tests '*Otp*' --tests '*Recovery*'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/adapter-persistence
git commit -m "feat(persistence): store users and passkeys" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 5: Spring Security WebAuthn adapter

**Files:**
- Modify: `backend/gradle/libs.versions.toml`
- Modify: `backend/bootstrap/build.gradle.kts`
- Create: `backend/bootstrap/src/main/kotlin/dev/moreal/finds_team/security/SecurityConfiguration.kt`
- Create: `backend/bootstrap/src/main/kotlin/dev/moreal/finds_team/security/ActorResolver.kt`
- Create: `backend/bootstrap/src/main/kotlin/dev/moreal/finds_team/security/WebAuthnPersistenceAdapters.kt`
- Create: `backend/bootstrap/src/main/kotlin/dev/moreal/finds_team/security/ConfiguredInitialRolePolicy.kt`
- Create: `backend/bootstrap/src/test/kotlin/dev/moreal/finds_team/security/WebAuthnHttpTest.kt`
- Modify: `backend/bootstrap/src/main/resources/application.yml`

**Interfaces:**
- Produces HTTP endpoints `/webauthn/register/options`, `/webauthn/register`, `/webauthn/authenticate/options`, `/login/webauthn`, `/auth/logout`, and framework-neutral Actor mapping.

- [ ] **Step 1: Write failing MockMvc security tests**

Test HTTPS/RP/origin configuration, CSRF, expired and replayed challenge, wrong user handle, missing user verification, unknown credential, counter update, session-id rotation, discoverable credential login, and exact normalized-email matching for the initial administrator allowlist.

- [ ] **Step 2: Add Spring Security WebAuthn dependency**

Use the Spring Boot-managed `spring-security-webauthn` version. Do not expose its repository types outside bootstrap adapter classes.

- [ ] **Step 3: Configure request policies**

Permit public GraphQL queries and ceremony-start endpoints, require restricted scope for registration/recovery completion, require `ACTIVE` session for viewer operations, and require `ADMIN` plus recent Passkey for protected mutations.

- [ ] **Step 4: Map principal to Actor**

`ActorResolver` extracts only user id, roles, authentication time, and strength. GraphQL and HTTP application adapters consume that value.

- [ ] **Step 5: Verify**

Run: `cd backend && ./gradlew :bootstrap:test --tests '*WebAuthnHttpTest'`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/bootstrap backend/gradle/libs.versions.toml
git commit -m "feat(bootstrap): add Passkey authentication" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 6: OTP and recovery HTTP adapter

**Files:**
- Create: `backend/bootstrap/src/main/kotlin/dev/moreal/finds_team/security/EnrollmentController.kt`
- Create: `backend/bootstrap/src/main/kotlin/dev/moreal/finds_team/security/RecoveryController.kt`
- Create: `backend/bootstrap/src/main/kotlin/dev/moreal/finds_team/security/AuthSessionController.kt`
- Create: `backend/bootstrap/src/test/kotlin/dev/moreal/finds_team/security/EnrollmentHttpTest.kt`
- Create: `backend/bootstrap/src/test/kotlin/dev/moreal/finds_team/security/RecoveryHttpTest.kt`
- Modify: `backend/bootstrap/src/main/kotlin/dev/moreal/finds_team/config/RuntimeConfiguration.kt`

**Interfaces:**
- Implements the approved `/auth/enrollment/*`, `/auth/recovery/*`, and `/auth/session` contract using problem-details errors.

- [ ] **Step 1: Write response-equivalence and rate-limit tests**

Compare status/body/timing class for registered and unregistered OTP requests, verify `429` bounds, malformed `400`, conflict `409`, restricted session cookie, and no secrets in serialized errors/log capture.

- [ ] **Step 2: Implement controllers as thin adapters**

Parse transport DTOs, call use cases, establish restricted sessions on verified results, and convert sealed results to problem details. No account/recovery decisions live in controllers.

- [ ] **Step 3: Verify**

Run: `cd backend && ./gradlew :bootstrap:test --tests '*EnrollmentHttpTest' --tests '*RecoveryHttpTest'`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/bootstrap
git commit -m "feat(bootstrap): expose enrollment and recovery" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 7: End-to-end authentication slice

**Files:**
- Create: `backend/bootstrap/src/test/kotlin/dev/moreal/finds_team/security/AuthenticationVerticalSliceTest.kt`
- Modify: `README.md`

**Interfaces:**
- Verifies mail outbox → OTP → Passkey → session → recovery across assembled adapters.

- [ ] **Step 1: Add full vertical tests**

Use PostgreSQL Testcontainers, recording mail transport, and Spring WebAuthn test fixtures to prove enrollment, Passkey-only login, failed OTP normal login, two-proof recovery, old credential rejection, new recovery code, role enforcement, and audit rows.

- [ ] **Step 2: Document local secure setup**

Document localhost RP/origin values, recording/local SMTP development, and how to register the first admin through deployment bootstrap configuration.

- [ ] **Step 3: Run all identity checks**

Run: `cd backend && ./gradlew :domain:test :application:test :adapter-persistence:test :adapter-notification:test :bootstrap:test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/bootstrap/src/test README.md
git commit -m "test(auth): prove Passkey account lifecycle" -m "Assisted-by: Codex:gpt-5.6-sol"
```
