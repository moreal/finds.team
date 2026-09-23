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
first error and runs Relay artifact validation, TypeScript, Vitest, the Kobalte
and virtual-list Playwright specifications, the production build, and the
built-handler security/artifact tests in that order. On macOS, use the Linux
Chromium server documented in
[`frontend/COMPATIBILITY.md`](frontend/COMPATIBILITY.md) for the native-select
keyboard case, then run the same root command with
`PW_TEST_CONNECT_WS_ENDPOINT` and `PW_TEST_CONNECT_EXPOSE_NETWORK` set.

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
  --data '{"query":"{ jobPostings { totalCount } crawlStatuses { careerSiteId outcome } }"}' \
  http://localhost:8080/graphql
```

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
pnpm --dir frontend exec playwright test e2e/same-origin-routing.spec.ts
```

Caddy sends `/graphql`, `/auth/*`, and `/webauthn/*` directly to Spring and
sends every other path to the Start production server. The browser therefore
uses relative `/graphql` requests without CORS, while Start SSR alone receives
`FINDS_INTERNAL_GRAPHQL_URL=http://backend:8080/graphql`. The backend and
frontend have no host ports; set `FINDS_APP_PORT` to change the proxy's
localhost-only host port. Set `FINDS_PUBLIC_ORIGIN` to the matching origin when
running the routing test on a custom port, for example:

```sh
FINDS_APP_PORT=8081 docker compose --profile app up -d --build
FINDS_PUBLIC_ORIGIN=http://127.0.0.1:8081 pnpm --dir frontend exec playwright test e2e/same-origin-routing.spec.ts
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
ordinary expiration. Audit retention/legal deletion is an explicit operator
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
