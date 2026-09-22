# finds.team

A domain-driven job aggregation service for Flex, Greeting, and Ninehire career
sites. PostgreSQL migrations are the persistence source of truth; the runtime
uses Flyway and jOOQ and contains no JPA or H2 path.

## Local development

Prerequisites are Nix and a Docker-compatible daemon. Start PostgreSQL from the
repository root, enter the Java 25 LTS shell, and run Spring Boot:

```sh
docker compose up -d postgres
nix develop
cd backend
./gradlew :bootstrap:bootRun
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
```

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

## Configuration

Defaults live in `backend/bootstrap/src/main/resources/application.yml`. The
most commonly deployed overrides are:

| Environment variable | Purpose | Default |
|---|---|---|
| `FINDS_DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:55432/finds_team` |
| `FINDS_DB_PORT` | Compose host port | `55432` |
| `FINDS_DB_USER` | PostgreSQL user | `finds` |
| `FINDS_DB_PASSWORD` | PostgreSQL password | `finds` |
| `FINDS_USER_AGENT_PRODUCT` | HTTP User-Agent product | `finds.team` |
| `FINDS_ROBOTS_PRODUCT_TOKEN` | robots.txt product token | `findsteam` |
| `FINDS_CONTACT_URL` | HTTPS operator contact in User-Agent | `https://finds.team/contact` |
| `FINDS_LEASE_OWNER` | Unique database lease owner | container hostname, then `local` |

Durations, concurrency, retry backoff, close grace, sitemap bounds, response
limits, and the GraphQL body limit are normal Spring configuration properties
under `finds.source`, `finds.crawl`, and `finds.graphql`.

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
