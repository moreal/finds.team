# Identity and Authentication Design

## Goal

Add public user accounts, Passkey-only normal login, email-OTP enrollment, and two-proof account recovery without allowing Spring Security or WebAuthn implementation types into the domain or application layers.

## Domain model

`User` is the aggregate root. It contains `UserId`, normalized `EmailAddress`, `UserStatus`, a set of `UserRole`, and security-policy state. Initial roles are `USER` and `ADMIN`. `ADMIN` changes authorization only; it does not select another authentication flow.

User statuses are:

- `PENDING_PASSKEY`: email ownership is verified, but the first Passkey is not registered.
- `ACTIVE`: at least one usable Passkey exists.
- `SUSPENDED`: no new sessions or credential changes are permitted.

Recovery is a scope on a short-lived session, not a user status. Starting recovery never downgrades or locks an otherwise active user account.

Passkey cryptographic records are external credential data owned by the authentication adapter/persistence boundary. The domain sees opaque `CredentialId` metadata when applying policies such as “the last credential cannot be removed.”

## Application use cases

- `RequestEnrollmentOtp`
- `VerifyEnrollmentOtp`
- `CompletePasskeyEnrollment`
- `AuthenticateWithPasskey`
- `RequestRecoveryOtp`
- `VerifyRecoveryProofs`
- `CompletePasskeyRecovery`
- `ListPasskeys`
- `RenamePasskey`
- `RemovePasskey`
- `RotateRecoveryCode`
- `ListSessions`
- `RevokeSession`
- `RevokeOtherSessions`
- `GrantRole` and `RevokeRole`

Each use case consumes plain application commands and produces sealed results. Controllers convert HTTP/WebAuthn/Spring values at the boundary.

## Enrollment

1. The user submits an email address and a request idempotency key.
2. The server normalizes the address, enforces email/IP/device rate limits, and always returns the same public response.
3. One transaction invalidates older enrollment challenges, stores a keyed hash of a new eight-digit OTP, records attempt counters and expiry, and appends a verification-mail outbox row.
4. The OTP expires after ten minutes, is single-use, permits five failed entries, and issuing another OTP does not reset the account-level consecutive-failure counter.
5. Successful verification creates or advances the user to `PENDING_PASSKEY` and creates a restricted enrollment session.
6. Spring Security begins a WebAuthn registration ceremony for that authenticated restricted session.
7. After origin, RP ID, challenge, user-verification, and credential checks pass, `CompletePasskeyEnrollment` stores the credential, activates the user, appends an audit event, creates the initial recovery-code hash, and invalidates the restricted session in one transaction.
8. The 128-bit recovery code plaintext is returned once and never stored or logged.

## Normal authentication

Normal login uses discoverable Passkeys and WebAuthn user verification. Email OTP is not accepted as an ordinary login credential. On success, Spring Security establishes a server-side session and rotates the session identifier. The session cookie is `Secure`, `HttpOnly`, and `SameSite=Lax`; state-changing requests also require a CSRF token.

The adapter maps the authenticated principal into the application value `Actor(UserId, roles, authenticatedAt, authenticationStrength)`. Application authorization consumes `Actor` and never reads `SecurityContext`.

## Recovery

Recovery requires two independent proofs:

1. A short-lived eight-digit OTP delivered to the registered email address.
2. The 128-bit saved recovery code issued during enrollment or the previous recovery.

The recovery code is generated with a cryptographically secure random generator, encoded as grouped Base32 for manual entry, shown once, stored as a keyed hash, rate-limited, and single-use.

Successful proof verification creates a restricted recovery session. That session can only request and complete a new Passkey registration. Completing recovery atomically stores the new credential, revokes every pre-recovery Passkey and session, consumes the old recovery code, stores the hash of a new recovery code, returns its plaintext once, and appends an audit event. Losing both a usable Passkey and the recovery code leaves no automatic recovery path.

## Credential and session management

- All users may register multiple Passkeys and receive clear device labels.
- The UI recommends at least two Passkeys. Administrators may be required by policy to retain two, but use the same credential and recovery mechanisms.
- Removing a credential requires a recent Passkey authentication and cannot leave an active account with zero credentials.
- Rotating the recovery code requires recent Passkey authentication and invalidates the old code immediately.
- Granting or revoking `ADMIN` requires an administrator, recent Passkey authentication, an idempotency key, and an audit event.
- High-impact administrator commands require `authenticatedAt` within five minutes.

## HTTP surface

Security ceremonies remain HTTP endpoints rather than GraphQL fields:

```text
POST /auth/enrollment/otp/request
POST /auth/enrollment/otp/verify
POST /auth/recovery/otp/request
POST /auth/recovery/otp/verify
POST /webauthn/register/options
POST /webauthn/register
POST /webauthn/authenticate/options
POST /login/webauthn
POST /auth/logout
GET  /auth/session
```

Errors use problem-details JSON. OTP request responses do not disclose account existence. Malformed requests are `400`, missing authentication `401`, insufficient role or step-up `403`, state conflicts `409`, and throttling `429` with bounded retry metadata.

## Persistence

Required tables are `users`, `user_roles`, `passkey_credentials`, `otp_challenges`, `recovery_codes`, `user_sessions`, and WebAuthn challenge storage. Credential public keys, counters, transports, backup eligibility/state, creation time, and last-use time are retained. Challenges and OTPs have database-enforced single-consumption state.

## Framework boundary

Spring Security WebAuthn implements ceremony parsing, cryptographic verification, CSRF integration, and session establishment. Adapter code implements Spring repositories by delegating to persistence ports or transaction-scoped stores. Architecture tests reject imports beginning with `org.springframework`, `com.webauthn4j`, `jakarta.servlet`, `graphql`, or `org.jooq` from domain and application production sources.

## Verification

- Pure tests cover email normalization, roles, account states, last-credential rules, recovery-code lifecycle, and step-up expiry.
- Application tests use fake ports for every sealed use-case result.
- MockMvc/WebAuthn tests cover RP/origin mismatch, expired/replayed challenge, wrong user handle, missing user verification, counter handling, CSRF, and session rotation.
- PostgreSQL tests cover concurrent OTP consumption, concurrent credential insertion, session revocation, and transaction rollback.
- End-to-end tests use a browser virtual authenticator and recording mail transport.
