# finds.team Web Product Program Design

**Status:** Approved in conversation on 2026-09-22; written review pending

**Audience:** implementers and reviewers of the finds.team web product

**Purpose:** define the program boundaries, shared constraints, and delivery order for the frontend, authentication, mail, audit, and Relay work

## Outcome

finds.team will provide a server-rendered public job discovery product and an authenticated operations console. Public users can create accounts and manage their security credentials, but applying to jobs, saved searches, alerts, and other account-backed job-seeker features remain outside this program. Administrators can register career sites, inspect crawl health, trigger crawls, and review audit events.

## Existing state

The repository has no frontend implementation and no user, account, role, credential, session, or authentication model. `PRD.md` contains an untracked frontend draft built around React/Vite/urql, but the tracked roadmap ends after the backend. The implemented GraphQL schema exposes posting search, crawl status, career-site registration, and crawl triggering; it is not Relay-compliant and lacks posting detail, company, skill, viewer, audit, and crawl-history queries.

## Approved decisions

- Use SolidJS 2 RC and TanStack Start 2 RC with full-document streaming SSR.
- Use Relay compiler and `solid-relay`; generated types replace hand-written response types.
- Use Kobalte through local design-system wrappers, subject to the compatibility gate below.
- Use TanStack Virtual only after hydration and only where measured DOM size warrants it.
- Use a same-origin reverse proxy, not a TanStack Start BFF.
- During SSR, TanStack Start calls Spring GraphQL over an internal address and serializes request-scoped Relay records into the document.
- During hydration, the browser restores those records without re-fetching the initial query.
- After hydration, browser Relay requests go directly to same-origin `/graphql`, routed by ingress to Spring.
- Public reads remain anonymous. Account and administrator operations use server sessions in `Secure`, `HttpOnly`, `SameSite=Lax` cookies plus CSRF protection.
- Normal login is Passkey-only. Email OTP is used only for enrollment and account recovery.
- Account recovery requires both email OTP and a saved 128-bit recovery code. Losing both means there is no automatic recovery.
- `ADMIN` is a role on the same user model, not a separate account type or authentication system.
- Spring Security and WebAuthn types stay in HTTP/bootstrap adapters. Domain and application code remain framework-agnostic.
- Mail is a set of independent Gradle modules in this monorepo under a provider-neutral namespace.
- Business changes, audit append, command idempotency record, and required outbox rows commit in one PostgreSQL transaction.
- Full CQRS and event sourcing are not adopted. Commands and queries have explicit code boundaries while PostgreSQL remains the single system of record.

## Program decomposition

This program is intentionally split into five independently reviewable subprojects. Each receives its own implementation plan and can only begin after its required predecessors are green.

1. [Identity and authentication](2026-09-22-identity-authentication-design.md)
2. [Mail transports and delivery](2026-09-22-mail-transport-design.md)
3. [Transactional audit and idempotency](2026-09-22-transactional-audit-design.md)
4. [Relay GraphQL contract and backend enrichment](2026-09-22-relay-graphql-design.md)
5. [Solid frontend and design system](2026-09-22-solid-frontend-design.md)

The mail core and compatibility spike can proceed independently. Authentication depends on mail delivery for enrollment and recovery. Relay schema work depends on the identity and audit application contracts but can develop public discovery fields in parallel. The frontend begins with the compatibility spike and design system, then consumes schema milestones as Relay artifacts become available.

## Shared architecture

```text
Browser
  ├─ page requests ────────────────> TanStack Start
  ├─ /graphql ─────────────────────> Spring GraphQL
  └─ /auth/* and /webauthn/* ─────> Spring Security HTTP adapters

TanStack Start SSR ── internal HTTP ──> Spring GraphQL

Spring bootstrap
  ├─ HTTP / GraphQL / security adapters
  ├─ application commands, queries, and ports
  ├─ pure domain rules
  └─ persistence, source, notification, and mail adapters

PostgreSQL
  ├─ business state
  ├─ identities and credential public material
  ├─ command idempotency
  ├─ append-only audit events
  └─ transactional outboxes
```

## Compatibility gate

The 2026-09-22 package snapshot has no peer-dependency set that directly satisfies every requested library:

- `solid-js` latest `next`: `2.0.0-rc.9`.
- `@tanstack/solid-start` `rc`: `2.0.0-rc.8`, requiring Solid 2 pre-release and `@solidjs/web >=2.0.0-rc.6`.
- `@kobalte/core` `alpha`: `2.0.0-alpha.2`, declaring exact peers on Solid/Web `2.0.0-rc.3`.
- `@tanstack/solid-virtual` current packages still declare Solid 1 peer ranges.
- `solid-relay`: `1.0.0-beta.29`, allowing Solid `>=1.4.0` but requiring an SSR/codegen proof against the chosen RC set.

The first frontend task must therefore create a minimal production-build spike that proves:

1. TanStack Start SSR and hydration on SolidJS 2 RC.
2. Relay compile, query rendering, record serialization, and hydration without a duplicate fetch.
3. One Kobalte dialog and one keyboard-driven select under SSR/hydration.
4. One variable-height virtual list under hydration.
5. Per-request cryptographic nonce protection for every SSR script under a
   strict Content Security Policy that permits only the request nonce and
   forbids `unsafe-eval` and unrestricted `unsafe-inline`.

If the Kobalte peer override fails behavior or hydration tests, retain the local design-system interfaces and temporarily implement the required native controls without Kobalte until a compatible Kobalte build exists. If the Solid Virtual adapter fails, implement a small local adapter over `@tanstack/virtual-core`. The target remains SolidJS 2 RC; downgrading the application to Solid 1 is not a fallback.

## Cross-cutting idempotency

Idempotency is part of every externally retryable command:

- GraphQL mutations accept an explicit UUID `idempotencyKey`; Relay `clientMutationId` remains correlation metadata and is not the idempotency contract.
- The server scopes a key by actor and operation and stores a canonical request hash.
- Repeating the same key and hash returns the recorded result without repeating side effects.
- Reusing the same key with a different hash returns an idempotency conflict.
- Anonymous enrollment commands scope keys by normalized email hash and operation after rate limiting.
- WebAuthn challenge identifiers are one-time replay controls rather than generic mutation keys.
- Every mail outbox row has a stable `MailMessageId`, forwarded to providers that support idempotency.
- Manual crawl triggers return the original crawl run when retried with the same command key.

## Security and privacy baseline

- TLS is mandatory outside localhost. RP ID and allowed origins are explicit configuration.
- No passwords are introduced.
- OTP, recovery code, session secret, CSRF token, WebAuthn challenge, private credential material, or provider API key is logged.
- Public responses do not reveal whether an email address is registered.
- Application errors do not expose provider messages or stack traces.
- All user-controlled filters and cursor bounds are validated before repository execution.
- Administrator role assignment is only available through deployment bootstrap or an existing administrator command.

## Delivery order

1. Frontend compatibility spike and repository toolchain.
2. Mail core, recording transport, outbox contract, SMTP, and Amazon SES transports.
3. Transactional command runner, idempotency store, audit ledger, and security events.
4. Identity domain/application model, OTP enrollment, Passkey registration/login, recovery, and sessions.
5. Relay server contract and backend data enrichment.
6. Design-system foundations and account-security UI.
7. Public discovery routes.
8. Administrator routes and audit UI.
9. Cross-browser, accessibility, security, SSR-isolation, and end-to-end verification.

## Program completion criteria

- Every subproject specification has an approved implementation plan and passing verification.
- No Spring, GraphQL, jOOQ, mail-provider, or WebAuthn implementation type is imported by `domain` or `application`.
- A clean build produces Relay artifacts and both frontend/server production bundles.
- Public job, company, and skill routes render meaningful HTML without client JavaScript.
- Hydration does not repeat the initial GraphQL query or share Relay records across requests.
- Enrollment, Passkey login, and two-proof recovery pass with a virtual authenticator and recording mail transport.
- Administrator mutations are authorized, idempotent, audited atomically, and protected by recent Passkey authentication where specified.
- Light and dark themes meet WCAG 2.2 AA for text, controls, focus, keyboard operation, and motion preferences.
