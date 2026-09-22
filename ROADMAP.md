# finds.team Roadmap

This roadmap turns finds.team from its JPA prototype into a domain-driven job
aggregation service. The roadmap is executable: every phase has an observable
exit condition and exact verification commands. Detailed local agent working
documents live under the ignored `docs/superpowers/` directory; this roadmap is
the repository's durable delivery contract.

## Delivery principles

- Target Java 25 LTS with the Gradle 9.7.1 wrapper, Kotlin 2.4.20, and
  Spring Boot 4.1.1; the Nix shell provides JDK 25 and builds use the wrapper.
- Build a pure Kotlin domain before infrastructure.
- Keep provider parsing separate from crawl policy and orchestration.
- Treat PostgreSQL migrations as the source of truth and generate jOOQ code
  from them.
- Introduce each new behavior with a failing test, then implement the smallest
  complete behavior that makes it pass.
- Preserve the working Flex and Greeting parsing behavior while removing its
  dependency on JPA entities.
- Finish each phase with the listed verification before starting a dependent
  phase.
- Remove the legacy JPA/H2 path only after the replacement vertical slice is
  proven end to end.

## Target structure

```text
schema/                         GraphQL API contract
backend/
  domain/                       Pure domain model and policies
  application/                  Use cases and ports
  adapter-source/               Flex, Greeting, Ninehire and web protocols
  adapter-persistence/          Flyway, jOOQ and PostgreSQL adapters
  adapter-graphql/              GraphQL transport mapping
  bootstrap/                    Spring wiring, scheduling and configuration
docs/adr/                       Durable technical decisions
```

The dependency direction is:

```text
bootstrap -> adapter-* -> application -> domain
```

## Phase 0 — Architecture baseline

Deliverables:

- [ ] Convert `backend` to a Gradle multi-project build.
- [ ] Add a version catalog and centralize plugin/dependency versions.
- [ ] Add empty `domain`, `application`, `adapter-source`,
      `adapter-persistence`, `adapter-graphql`, and `bootstrap` modules.
- [ ] Add an architecture test that prevents framework, database, HTTP, and
      Spring imports from entering `domain`.
- [ ] Add ADRs for jOOQ/Flyway, error modeling, provider detection, and crawl
      policy ownership.
- [ ] Add CI-compatible `check` tasks for every module.

Exit criteria:

```sh
cd backend
./gradlew check
```

## Phase 1 — Domain core

Deliverables:

- [ ] Model `CareerSite`, `SiteHost`, `SourceProvider`, `JobPosting`,
      `PostingStatus`, identifiers, and normalized posting content as immutable
      Kotlin values.
- [ ] Validate absolute HTTPS URLs, normalized hosts, and provider-independent
      posting identity.
- [ ] Implement the composable `Filter` algebra and in-memory reference
      semantics.
- [ ] Implement crawl eligibility, retry/backoff, freshness, and close-grace
      policies as pure decisions.
- [ ] Implement snapshot reconciliation producing a `SyncPlan` with insert,
      update, touch, close, and reopen operations.
- [ ] Reject suspicious empty snapshots when previously open postings exist.
- [ ] Unit- and property-test all domain decisions without Spring or a database.

Exit criteria:

```sh
cd backend
./gradlew :domain:test
```

## Phase 2 — Application use cases and ports

Deliverables:

- [ ] Define repository, transaction, clock, source discovery, source fetch,
      robots policy, and crawl lease ports.
- [ ] Implement `RegisterCareerSite`, `SearchPostings`, `CrawlSite`,
      `CrawlAllDue`, and `GetCrawlStatus` use cases.
- [ ] Keep policy decisions in `domain`; application code only coordinates
      ports and executes domain decisions.
- [ ] Record every crawl attempt and its outcome without applying a sync plan
      after fetch or validation failure.
- [ ] Add deterministic in-memory fakes in application test fixtures.

Exit criteria:

```sh
cd backend
./gradlew :application:test
```

## Phase 3 — Source protocol and provider adapters

Deliverables:

- [ ] Implement an RFC 9309 robots.txt client with bounded caching and explicit
      allow/deny/unavailable decisions.
- [ ] Implement sitemap URL-set and sitemap-index parsing, including recursive
      indexes, same-site validation, limits, and optional `lastmod` metadata.
- [ ] Implement per-host request serialization, minimum request spacing,
      timeouts, response-size limits, redirects, and an identifying User-Agent.
- [ ] Migrate Flex parsing to `FlexSourceAdapter` returning `RawPosting` values.
- [ ] Migrate Greeting parsing to `GreetingSourceAdapter` returning
      `RawPosting` values.
- [ ] Implement `NinehireSourceAdapter` for `*.ninehire.site`, including
      `/job_posting/{externalKey}` URLs and custom-domain fingerprinting.
- [ ] Store sanitized response fixtures and keep live network tests excluded
      from normal CI.
- [ ] Make provider adapters incapable of deciding crawl cadence, retries, or
      posting closure.

Exit criteria:

```sh
cd backend
./gradlew :adapter-source:test
```

## Phase 4 — PostgreSQL persistence

Deliverables:

- [ ] Add PostgreSQL migrations for career sites, postings, posting skills,
      crawl runs, and crawl leases.
- [ ] Configure jOOQ generation from the migrated schema; generated sources are
      build artifacts and are not committed.
- [ ] Implement jOOQ repositories and transactional `SyncPlan` application.
- [ ] Translate the domain filter algebra to jOOQ `Condition` values.
- [ ] Prove SQL filter semantics agree with the in-memory domain semantics.
- [ ] Prove applying a sync plan is idempotent.
- [ ] Test against PostgreSQL with Testcontainers.

Exit criteria:

```sh
cd backend
./gradlew :adapter-persistence:test
```

## Phase 5 — API contract and transport

Deliverables:

- [ ] Commit the schema-first GraphQL contract for posting search, career-site
      registration, crawl triggering, and crawl status.
- [ ] Map GraphQL inputs to domain commands and filters without leaking GraphQL
      types inward.
- [ ] Implement stable cursor pagination and default filtering to open postings.
- [ ] Return typed registration, validation, unsupported-provider, and crawl
      errors.
- [ ] Verify every schema field has a resolver and that generated client types
      can consume the schema.

Exit criteria:

```sh
cd backend
./gradlew :adapter-graphql:test
```

## Phase 6 — Runtime, scheduling, and operations

Deliverables:

- [ ] Wire the application in `bootstrap` with Spring Boot.
- [ ] Poll for due sites at a short fixed scheduler interval while domain policy
      controls each site's actual recrawl cadence.
- [ ] Bound global and per-host concurrency and acquire database-backed crawl
      leases before fetching.
- [ ] Expose configuration for crawl interval, timeouts, concurrency, request
      spacing, close grace, User-Agent, and contact URL.
- [ ] Provide Docker Compose for local PostgreSQL and documented run commands.
- [ ] Add structured crawl logs and health/metrics endpoints without placing
      operational concerns in the domain.
- [ ] Verify registration -> crawl -> reconciliation -> search end to end.

Exit criteria:

```sh
cd backend
./gradlew :bootstrap:test
docker compose up -d postgres
./gradlew :bootstrap:bootRun
```

The final `bootRun` command is a documented manual smoke test; automated tests
must cover the same vertical slice in CI.

## Phase 7 — Legacy removal and completion audit

Deliverables:

- [ ] Delete JPA entities, Spring Data repositories, legacy REST controllers,
      synchronous crawl-on-registration flow, and the empty background task.
- [ ] Remove H2, `kotlin-jpa`, and Spring Data JPA dependencies.
- [ ] Remove legacy `bin` artifacts and expand ignore rules for local IDE/build
      output without deleting user-owned untracked files.
- [ ] Confirm no `jakarta.persistence`, H2, or Spring Data JPA references remain.
- [ ] Run the full test suite and architecture checks from a clean build.
- [ ] Reconcile this roadmap against the implementation and record every item as
      complete only when direct evidence exists.

Exit criteria:

```sh
cd backend
./gradlew clean check
cd ..
test -z "$(git grep -nE 'jakarta\.persistence|com\.h2database|spring-boot-starter-data-jpa' -- backend || true)"
```

## Initial policy defaults

Defaults are configuration, not hard-coded provider behavior:

| Policy | Initial value |
|---|---:|
| Successful recrawl interval | 6 hours |
| Scheduler scan interval | 15 minutes |
| Failed crawl retry sequence | 5m, 30m, 2h, then 6h |
| Global crawl concurrency | 3 |
| Per-host concurrency | 1 |
| Minimum per-host request spacing | 500 ms |
| Site crawl timeout | 60 seconds |
| Posting close grace | 2 consecutive successful snapshots |
| robots.txt success cache | 24 hours |
| robots.txt transient-failure cache | 5 minutes |

These values can change through deployment configuration. The meaning and
invariants of each policy remain in the domain.

## Definition of complete

The roadmap is complete only when all phases are checked, `./gradlew clean
check` passes, the end-to-end vertical slice works with PostgreSQL, all three
providers have fixture-backed adapter tests, and no JPA/H2 implementation path
remains.
