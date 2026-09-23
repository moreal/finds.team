# finds.team

A domain-driven job aggregation service for Flex, Greeting, and Ninehire career
sites. PostgreSQL migrations are the persistence source of truth; the runtime
uses Flyway and jOOQ and contains no JPA or H2 path.

## Local development

Prerequisites are Nix and a Docker-compatible daemon. Start PostgreSQL from the
repository root, enter the Java 25 LTS shell, and run Spring Boot:

```sh
docker compose up -d postgres
docker compose run --rm postgres-roles
nix develop
cd backend
SPRING_PROFILES_ACTIVE=dev FINDS_MAIL_RECORDING=true ./gradlew :bootstrap:bootRun
```

### Frontend workspace

The Nix development shell supplies Node.js 24 and a `pnpm` command backed by
Corepack. Its version is pinned by the root `packageManager` field. When using
Node outside Nix, enable the Corepack shims once before running pnpm:

```sh
corepack enable
```

Install the locked frontend dependencies from the repository root:

```sh
pnpm install --frozen-lockfile
pnpm --dir frontend exec playwright install chromium
pnpm frontend:check
```

`pnpm frontend:check` is the canonical compatibility gate. It fails at the
first error and runs Relay artifact validation, TypeScript, Vitest, all fixture
Playwright acceptance specifications, the production build, and the
built-handler security/artifact tests in that order. On macOS, use the Linux
Chromium server documented in
[`frontend/COMPATIBILITY.md`](frontend/COMPATIBILITY.md) for the native-select
keyboard case, then run the same root command with
`PW_TEST_CONNECT_WS_ENDPOINT` and `PW_TEST_CONNECT_EXPOSE_NETWORK` set.

The browser suite includes discovery, authentication, administration, component
catalogs, keyboard and axe checks for 11 primary routes at desktop/mobile widths
in light/dark themes, and concurrent two-user SSR isolation. Browser fixture
servers stop before the production build starts. Run it alone with
`pnpm --dir frontend test:e2e`.

Each route/theme/viewport check attaches a full-page PNG to Playwright results
for visual review and checks horizontal overflow and visible keyboard focus.
These main-route captures are review artifacts rather than host-dependent pixel
goldens; catalog pixel baselines remain in `frontend/e2e/ui-catalog.spec.ts-snapshots`.
Review the route captures when changing layout and retain them as CI artifacts.
Update catalog baselines only after reviewing differences on the pinned browser
and matching OS. Fixture coverage does not prove real Spring/browser integration:
the same-origin production smoke below remains a separate final program gate.

Flyway applies the schema at startup. The service listens on port 8080 by
default. Its operational endpoints are:

```text
POST /graphql
GET  /actuator/health
GET  /actuator/info
GET  /actuator/metrics
```

A small GraphQL smoke query is:

```sh
curl --fail-with-body \
  -H 'content-type: application/json' \
  --data '{"query":"{ jobPostings { totalCount } viewer { user { id roles } } }"}' \
  http://localhost:8080/graphql
```

`viewer` is null anonymously. Public company pages can read `crawlSummary`;
`crawlHistory`, `crawlStatuses` and `auditEvents` require an administrator.
These collections use bounded Relay connections (`edges`, `pageInfo`,
`totalCount`) with `first` from 1–100 and opaque `after` cursors. Account and
administrator mutations use input objects with UUID `idempotencyKey` and optional
`clientMutationId`; `triggerCrawl` takes `TriggerCrawlInput`. Authenticated
mutations still require a session CSRF token and application authorization.

Stop the local database without deleting its named volume:

```sh
cd ..
docker compose down
```

### Same-origin production topology

The opt-in `app` Compose profile builds the Spring Boot and TanStack Start
production images and exposes one Caddy origin at `http://127.0.0.1:8080`:

The backend explicitly uses the `dev` Spring profile and recording mail for this
local topology; it sends no external mail. External deployments must supply the
production mail settings described below.

```sh
docker compose --profile app up -d --build
FINDS_PUBLIC_ORIGIN=http://127.0.0.1:8080 pnpm --dir frontend test:production
```

Caddy sends `/graphql`, `/auth/*`, `/webauthn/*`, and `/login/webauthn` directly to Spring and
sends every other path to the Start production server. The browser therefore
uses relative `/graphql` requests without CORS, while Start SSR alone receives
`FINDS_INTERNAL_GRAPHQL_URL=http://backend:8080/graphql`. The backend and
frontend have no host ports; set `FINDS_APP_PORT` to change the proxy's
localhost-only host port. Set `FINDS_PUBLIC_ORIGIN` to the matching origin when
running the routing test on a custom port, for example:

```sh
FINDS_APP_PORT=8081 docker compose --profile app up -d --build
FINDS_PUBLIC_ORIGIN=http://127.0.0.1:8081 pnpm --dir frontend test:production
```

The existing PostgreSQL development binding and named volume are unchanged.
The `postgres-roles` service runs before the backend, including on an existing
volume. It creates the limited `finds_app` and schema-owning `finds_migrator`
roles and transfers existing public schema objects to the migrator without
changing rows or Flyway history. The backend then applies pending migrations
through Flyway. Repeating the bootstrap is safe and does not broaden audit
permissions. No `down -v` or volume reset is needed.

Inspect health and stop the application without deleting database data:

```sh
docker compose --profile app ps
docker compose --profile app down
```

## Configuration

### Passkey authentication

Passkeys require an HTTPS browser origin, including local development. The plain
HTTP Compose topology above remains useful for public pages and queries. To use
the explicit local origin `https://localhost:8443` and RP ID `localhost`:

```sh
docker compose -f compose.yaml -f compose.https.yaml --profile app up -d --build
docker compose -f compose.yaml -f compose.https.yaml exec -T proxy cat /data/caddy/pki/authorities/local/root.crt > /tmp/finds-local-root.crt
```

Trust that development CA in your browser/OS before opening the site. Only the
public root certificate is exported. The proxy runs unprivileged and keeps its
development CA in memory-backed storage, so recreating it requires trusting a new
root. Do not use this override or local profiles in production.
If port 8443 is occupied, set `FINDS_TLS_PORT` on both Compose commands and use
that port in the browser; the override also sets the matching backend origin.

Production startup requires `FINDS_WEBAUTHN_RP_ID`, exact HTTPS
`FINDS_WEBAUTHN_ORIGINS` (comma-separated), and externally injected
`finds.security.hash-keys.<version>` values (Base64 random keys of at least 32
bytes). Set `FINDS_IDENTITY_HASH_VERSION` to an available positive version and
retain old versions while stored challenges/recovery proofs remain valid. The
adapter derives independent HMAC keys for each identity purpose. Only an explicit
`dev` or `test` profile allows temporary in-memory keys and the local RP/origin
defaults; adding a production profile disables these defaults.
Keep `finds.security.command-scope-hash-version` (default `1`) and its key stable
through the command retention window when rotating the active proof-hash key.
Each minute, maintenance removes at most 100 restricted sessions after their
24-hour replay window and 100 authentication challenges after expiry plus 24
hours. Registration challenges are deleted with their parent restricted session.

`FINDS_INITIAL_ADMIN_EMAILS` is an exact normalized-email allowlist, evaluated
only after email ownership verification. It does not match domains, wildcards,
plus-tags, or dot variations. Call `GET /auth/csrf` to obtain the session-bound
token and header name before ceremony POSTs. Cookies are always `Secure`,
`HttpOnly`, and `SameSite=Lax`; normal sessions require a discoverable Passkey
with user verification. Public GraphQL queries remain open; HTTP GraphQL
mutations stay closed until the audited command adapters are connected.

Registration completion uses `Idempotency-Key` (UUID), with optional UUID
`X-Request-ID` and `X-Correlation-ID`. Only a server-bound restricted enrollment,
recovery or additional-Passkey session can register. Completion returns the
recovery code once; same-command retries return success without the secret.
`POST /auth/enrollment/otp/request` and `/auth/recovery/otp/request` accept
`{"email":"person@example.com"}` and the same idempotency/correlation headers.
Both return `202 {"accepted":true}` regardless of account existence, with the
same 200–220ms minimum response timing class. Database slowness may exceed that
floor. `/auth/enrollment/otp/verify` accepts `email` and `otp`; recovery verification
also requires `recoveryCode`. Proof verification replaces the browser session and
CSRF token; fetch `/auth/csrf` again before registering a Passkey. Neither proof
flow logs in. Restricted sessions cannot access `/auth/session` or GraphQL.
`GET /auth/session` returns current user ID, roles and authentication time only
for a live Passkey session; expired/revoked sessions return problem-details 401.

V6 stores shared fixed-window abuse counters keyed by purpose-separated HMACs;
raw email/IP/device values never enter the counter table. Enrollment and recovery
share request budgets of 3/email, 30/IP and 10/device per 60 seconds. Verification
has separate budgets of 10/email, 60/IP and 20/device. Idempotent retries count
toward these budgets. Throttling returns generic problem-details 429 and
`Retry-After` between 1 and 60 seconds. Account lockout remains 15 minutes after
five failed proofs, and reissue never resets failures. Recovery reissue requires
60 seconds. Cleanup deletes at most 1,000 expired buckets every 10 seconds.

The `__Host-finds-device` cookie is server-issued, MAC-authenticated, Secure,
HttpOnly, SameSite=Lax and valid for 30 days. It survives session rotation and
is an abuse signal, never an authentication credential. Client IP defaults to the
socket peer. Set `FINDS_TRUSTED_PROXY_CIDRS` to the actual ingress proxy addresses
or narrowly scoped networks to enable right-to-left `X-Forwarded-For` parsing.
Leave framework forwarded-header rewriting disabled. Without an allowlist,
proxied clients share the proxy's IP budget; arbitrary forwarding headers are
ignored. Production replicas must share stable identity HMAC keys and PostgreSQL.
Security-management HTTP routes are added separately.

To bootstrap the first administrator, put the administrator's full email address
in `FINDS_INITIAL_ADMIN_EMAILS` in the **backend deployment environment** before
enrollment. A host shell variable is not automatically forwarded into a Compose
container; include it in that service's environment or deployment override.
Complete email verification and the first Passkey registration, save the recovery
code, then sign in with that Passkey. Only completed enrollment assigns `ADMIN`;
the allowlist does not promote existing users on restart. Administrators use the
same Passkey login and two-proof recovery as everyone else. Role changes require
a live administrator session authenticated with a Passkey within five minutes.

Recording mode has no public inbox or OTP-reading HTTP endpoint and never logs
codes. Automated lifecycle tests read the recording transport directly after
the real encrypted outbox dispatcher runs. For manual enrollment, use a local
SMTP capture server: disable `FINDS_MAIL_RECORDING`, enable SMTP, configure its
host/port, and explicitly select `finds.mail.smtp.tls=NONE` only for that trusted
local relay. Supply an external 32-byte AES key via `FINDS_MAIL_ENCRYPTION_KEY`
even under `dev` when SMTP is enabled. Read the OTP in the capture server's inbox;
no production mailbox, SMTP credentials, or production secrets are needed.
Keep using `localhost` in the browser: `127.0.0.1` is a different WebAuthn RP/origin.

The real-signature lifecycle gate runs PostgreSQL Testcontainers, encrypted mail
delivery, Spring WebAuthn registration/login, session and credential invalidation,
two-proof recovery, role authorization, and audit/outbox rollback tests:

```sh
cd backend
./gradlew :domain:test :application:test :adapter-persistence:test :adapter-notification:test :bootstrap:test
./gradlew check
```

After installing the locked frontend dependencies and Playwright Chromium above,
run the browser gate from `backend/`:

```sh
./gradlew :bootstrap:test -PbrowserSmoke --tests '*AuthenticationBrowserSmokeTest'
```

This opt-in test starts a temporary loopback HTTPS server on a free port with RP
`localhost`, an exact matching origin, and a one-day test certificate. Chromium
uses a CDP virtual authenticator with resident credentials and user verification.
The test alone accepts that temporary certificate; OS trust is unchanged. OTPs
travel through the recording transport and a private test process pipe. The
browser checks actual Secure/HttpOnly/SameSite cookies, session and CSRF rotation,
registration, discoverable login, recovery, and rejection of the old key and
session. The certificate, database, and browser are removed when the test ends.
The ordinary backend gate does not require Node or a browser installation.

Defaults live in `backend/bootstrap/src/main/resources/application.yml`. The
most commonly deployed overrides are:

| Environment variable | Purpose | Default |
|---|---|---|
| `FINDS_DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:55432/finds_team` |
| `FINDS_DB_PORT` | Compose host port | `55432` |
| `FINDS_DB_USER` | Runtime PostgreSQL user | `finds_app` |
| `FINDS_DB_PASSWORD` | Runtime PostgreSQL password | `finds` (development only) |
| `FINDS_MIGRATION_DB_URL` | Flyway JDBC URL | Runtime JDBC URL |
| `FINDS_MIGRATION_DB_USER` | Flyway schema owner | `finds_migrator` |
| `FINDS_MIGRATION_DB_PASSWORD` | Flyway password | `finds` (development only) |
| `FINDS_APP_PORT` | Same-origin proxy host port | `8080` |
| `FINDS_PUBLIC_ORIGIN` | Routing Playwright test origin; set to match `FINDS_APP_PORT` | `http://127.0.0.1:8080` |
| `FINDS_USER_AGENT_PRODUCT` | HTTP User-Agent product | `finds.team` |
| `FINDS_ROBOTS_PRODUCT_TOKEN` | robots.txt product token | `findsteam` |
| `FINDS_CONTACT_URL` | HTTPS operator contact in User-Agent | `https://finds.team/contact` |
| `FINDS_LEASE_OWNER` | Unique database lease owner | container hostname, then `local` |

Durations, concurrency, retry backoff, close grace, sitemap bounds, response
limits, and the GraphQL body limit are normal Spring configuration properties
under `finds.source`, `finds.crawl`, and `finds.graphql`.

### Verification mail

Default and production startup require an enabled SMTP or SES transport and an
external AES-256 key. Inject a Base64-encoded 32-byte random key through
`FINDS_MAIL_ENCRYPTION_KEY`, with its positive version in
`FINDS_MAIL_ACTIVE_KEY_VERSION`. Never store this key in the database or repository.
Set `FINDS_MAIL_SENDER` to the verified sending address.

Enable SMTP using `FINDS_MAIL_SMTP_ENABLED=true`, `FINDS_MAIL_SMTP_HOST`, and
`FINDS_MAIL_SMTP_PORT`. Credentials bind through `finds.mail.smtp.username` and
`finds.mail.smtp.password` (Spring environment variables
`FINDS_MAIL_SMTP_USERNAME` and `FINDS_MAIL_SMTP_PASSWORD`). TLS defaults to required
STARTTLS; `finds.mail.smtp.tls=IMPLICIT` selects implicit TLS, and `NONE` is an
explicit trusted-local-relay setting. Enable SES using
`FINDS_MAIL_SES_ENABLED=true` and `FINDS_MAIL_SES_REGION`; SES uses the default AWS
credential chain. `finds.mail.ses.configuration-set` is optional.

Provider `priority` values under `finds.mail.smtp` and `finds.mail.ses` determine
order (larger first, defaults 100 and 50). Each provider has bounded retries under
`finds.mail.retry`; only definite temporary rejection permits retry or fallback.
`finds.mail.dispatch` configures the batch, lease, total send timeout, maximum
outbox attempts and retry delays. The lease must exceed the send timeout by at
least five seconds. SMTP also has connect/read timeouts. Expiry is checked before
every provider attempt. An ambiguous send or recovered lease is held until expiry,
because neither SMTP Message-ID nor SES tags guarantee deduplication.

On key rotation, keep old Base64 keys in `finds.mail.retained-keys` keyed by their
integer version until all outbox payloads for those versions expire or are
redacted. The active version must not also appear in this map. Explicit `dev` or
`test` with `FINDS_MAIL_RECORDING=true` may use an ephemeral in-memory key; queued
development mail cannot survive a restart unless a stable external key is supplied.
Recording cannot be combined with `production`/`prod` or an enabled real provider.
Metrics contain only categorical outcomes, provider names, latency and queue age.

### Database roles and existing volumes

The checked-in Compose credentials are only for local development. For an
external deployment, inject distinct role passwords through the deployment's
secret environment; never paste passwords into commands, documentation, or
version control. The bootstrap reads `FINDS_MIGRATION_DB_PASSWORD` and
`FINDS_DB_PASSWORD` directly from its environment. It requires an administrator
connection (`POSTGRES_USER`, `POSTGRES_DB`, and the usual libpq `PGHOST` /
`PGPASSWORD` settings). Run `deploy/postgres/00-create-runtime-role.sh` once
before deploying the backend, including for databases already at V1 or V2.
The Docker init directory only runs for new volumes; for local existing
volumes use the explicit `docker compose run --rm postgres-roles` command above.
The app profile automates this step.

The script is scoped to a dedicated finds.team database whose `public` schema
belongs to this service. It transfers table, sequence, and non-extension
function ownership in that schema; it refuses roles with inherited memberships.
Take the normal database backup before an operational ownership change. No
application data is deleted and no migrations are marked as applied. Custom
deployments using different login names must provision equivalent ownership
and grants themselves; the bundled SQL provisions `finds_migrator` and
`finds_app`. Future migrations must explicitly grant their runtime privileges.

Set `SPRING_PROFILES_ACTIVE=production` (or `prod`) in deployed environments;
startup rejects equal runtime and migrator usernames. The local app Compose
profile uses `dev` with recording mail and still supplies distinct database roles.
The runtime login has read/insert permission only on
the audit and security ledgers, no ownership or role membership, and no schema
CREATE permission. It cannot change Flyway history, disable triggers, or
truncate the audit ledger. The migrator remains a privileged operational
credential and must not be used for application queries.

Ordinary command request rows expire after 24 hours; `AUDIT` retention has no
ordinary expiration. Scheduled maintenance runs every minute (override
`finds.audit.maintenance-interval`), deleting at most 100 expired ordinary
requests per invocation. It also closes at most 100 abandoned crawl runs as
`LEASE_EXPIRED` after their lease window, provided no matching live lease
remains. It never fetches sources, replays a reserved run, or removes a newer
lease. A later scheduler window may create a new eligible run.

`SearchAuditEvents` is the administrator-only application query for immutable
history. Actor, action, target and half-open time filters use a descending
`(occurred_at, event_id)` cursor, with at most 100 events per page. Runtime
queries use the existing SELECT grant; no audit edit or delete API is exposed.

Identity denials write categorical security events without request bodies or
authentication material. If that independent write fails, the HTTP boundary
preserves its denial status and increments
`finds.security.events{outcome=write_failed,action=...}` with a categorical
warning. Alert on any increase: authorization remains denied, but security
history is degraded and the failed event is not retried. Administrator command
denials retain their stricter event-store failure propagation.

Audit retention/legal deletion is an explicit operator
procedure using a separately controlled administrative connection and backup:
stop writers, begin a transaction, disable `audit_events_immutable`, perform
only the approved scoped deletion, enable the trigger with `ENABLE ALWAYS`,
and commit. Record the authorization and affected event IDs externally. Roll
back on error. Never grant this capability to `finds_app`; automated audit
deletion is not part of application startup or ordinary command cleanup.

The scheduler scans every 15 minutes, but the domain policy—not the provider
adapter—decides whether each site is due. Database leases prevent cross-instance
overlap, a global semaphore bounds total crawling, and the source layer permits
one request per host with at least 500 ms between starts. robots.txt decisions
and bounded sitemap traversal are applied before provider parsing.

## Deployment egress policy

[`deploy/network-policy.yaml`](deploy/network-policy.yaml) is a defense-in-depth
Kubernetes policy for pods labeled `app.kubernetes.io/name=finds-team`. It
allows cluster DNS, PostgreSQL pods labeled
`app.kubernetes.io/name=postgresql`, and public HTTPS while excluding private,
loopback, link-local, documentation, multicast, and reserved address ranges.
Adjust the PostgreSQL peer for an external managed database before applying it.
The in-process DNS destination checks remain required because an egress policy
does not replace application-level redirect and DNS-rebinding validation.

## Verification

From the repository root, verify the shared Relay contract with:

```sh
pnpm relay:check
```

This checks committed Relay artifact freshness before any compiler-enabled test
can refresh output, tests the byte-identical canonical `.graphqls` launcher, and
runs the domain, application, persistence, GraphQL, and assembled Spring backend
tests against PostgreSQL. `RelayVerticalSliceTest` sends the actual generated
Relay request text through `/graphql`, including fragments and variables, to
check public discovery, pagination, account isolation, administration, and
mutation replay. Java 25, the locked frontend dependencies, and a
Docker-compatible daemon are required. When schema or operation sources change,
run `pnpm --dir frontend relay`, commit the generated artifacts, and rerun this
gate. `pnpm frontend:check` remains the complete frontend compatibility gate.

The checked-in Gradle 9.7.1 wrapper uses Java 25 from the Nix shell. Run all
unit, architecture, fixture, PostgreSQL Testcontainers, and vertical-slice tests:

```sh
nix develop
cd backend
./gradlew clean check
```

Run one layer while iterating:

```sh
./gradlew :domain:test
./gradlew :application:test
./gradlew :adapter-source:test
./gradlew :adapter-persistence:test
./gradlew :adapter-graphql:test
./gradlew :bootstrap:test
```

The backend modules have inward-only dependencies:

- `domain`: immutable values and pure business policies;
- `application`: use cases and ports;
- `adapter-source`: safe web protocols and provider parsers;
- `adapter-persistence`: PostgreSQL, Flyway, and jOOQ;
- `adapter-graphql`: schema and transport mapping;
- `bootstrap`: Spring Boot wiring, scheduling, HTTP, and operations.

The dependency direction is `bootstrap -> adapter-* -> application -> domain`.
Generated jOOQ sources stay under Gradle build output and are never committed.
