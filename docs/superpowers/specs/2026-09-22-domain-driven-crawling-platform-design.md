# Domain-Driven Crawling Platform Design

Date: 2026-09-22
Status: Approved direction; implementation specification

## 1. Purpose

finds.team aggregates public job postings from company career sites into a
searchable service. The system must make exact, explainable decisions about
when to crawl, what changed, and when a posting is closed while remaining easy
to extend with new applicant-tracking-system providers.

This design replaces the current JPA/H2 prototype with a domain-driven,
multi-module Kotlin system using PostgreSQL, Flyway, and jOOQ. Flex, Greeting,
and Ninehire are the first supported providers. Provider-specific parsing is
kept outside crawling and reconciliation policy.

## 2. Architectural approach

The migration uses an evolutionary vertical replacement. New modules are built
alongside the prototype, the complete registration-to-search path is proven,
and only then is the legacy path deleted. This keeps existing Flex and Greeting
parser behavior available as regression evidence without preserving its JPA
coupling.

The alternatives are rejected for these reasons:

- A big-bang rewrite removes the only working parser path before its replacement
  is proven and makes regressions difficult to localize.
- Refactoring inside the existing monolith leaves domain policy dependent on
  Spring, JPA entities, and provider implementation details.

## 3. Contexts and modules

### 3.1 Domain

`domain` is pure Kotlin. It has no Spring, HTTP, SQL, filesystem, clock, or
randomness dependencies. Time is passed as a value.

It owns:

- career-site identity and invariants;
- provider identity as a business classification;
- job-posting identity, lifecycle, and searchable attributes;
- crawl eligibility, retry, and freshness decisions;
- snapshot reconciliation and closure decisions;
- composable search-filter semantics.

It does not own HTML/JSON shapes, HTTP behavior, scheduler annotations,
transactions, or database rows.

### 3.2 Application

`application` owns use cases and ports. It loads state, asks the domain for a
decision, then invokes ports to execute that decision. It contains orchestration
branches for success/failure routing but no provider parsing rules or policy
constants.

### 3.3 Source adapter

`adapter-source` implements external web protocols and provider parsing. It
contains shared robots.txt, sitemap, HTTP politeness, and safety mechanisms plus
one adapter per provider. It returns raw source facts and never decides whether
a missing posting is closed.

### 3.4 Persistence adapter

`adapter-persistence` owns Flyway migrations, jOOQ generation, SQL condition
translation, transactions, locking, and PostgreSQL repositories. SQL records
never cross its public boundary.

### 3.5 GraphQL adapter

`adapter-graphql` maps the schema contract to application commands and query
models. GraphQL types never enter application or domain modules.

### 3.6 Bootstrap

`bootstrap` owns Spring configuration, dependency wiring, scheduling,
observability, and runtime configuration. It depends on all concrete adapters;
no other module depends on it.

## 4. Domain model

### 4.1 Career site

```kotlin
data class CareerSite(
  val id: CareerSiteId,
  val canonicalBaseUrl: SiteUrl,
  val provider: SourceProvider,
  val displayName: String,
  val crawlSettings: CrawlSettings,
)
```

`SiteUrl` accepts only absolute HTTPS URLs without user information or explicit
ports. Its host is lowercase and IDNA-normalized. Custom domains are valid; a
provider is not derived repeatedly from the host after registration.

`SourceProvider` initially contains `FLEX`, `GREETING`, and `NINEHIRE`. It is a
stable domain value because selecting an adapter is part of a registered site's
identity, while detection itself is an external discovery operation.

### 4.2 Raw posting and snapshot

```kotlin
data class RawPosting(
  val externalKey: String,
  val title: String,
  val descriptionText: String,
  val canonicalUrl: PostingUrl,
  val employmentHint: String?,
  val locationHint: String?,
  val remoteHint: String?,
  val sourceUpdatedAt: Instant?,
)

data class Snapshot(
  val careerSiteId: CareerSiteId,
  val fetchedAt: Instant,
  val postings: List<RawPosting>,
  val sourceRevision: String?,
)
```

`externalKey` is stable within a career site. Provider adapters derive it from
provider identifiers rather than the mutable title. A snapshot is complete only
when discovery and every required detail request succeed. Partial results are a
failed crawl and cannot close postings.

### 4.3 Posting lifecycle

`JobPosting` is identified by `(careerSiteId, externalKey)`. Status is `OPEN` or
`CLOSED` and is derived locally. Source adapters do not author local lifecycle
state.

A content hash covers normalized source fields that affect users. Discovery
timestamps and transport metadata are excluded. Reconciliation returns:

```kotlin
data class SyncPlan(
  val insert: List<NewPosting>,
  val update: List<PostingUpdate>,
  val touch: List<JobPostingId>,
  val close: List<JobPostingId>,
  val reopen: List<PostingUpdate>,
)
```

The plan is deterministic for existing state, snapshot, policy, and current
time. Persistence applies the entire plan in one transaction.

## 5. Crawl policy

Crawl policy is a domain capability, not a provider capability. It is expressed
as pure decisions over site settings and crawl history.

### 5.1 Eligibility

`decideCrawlEligibility(site, history, now)` returns one of:

- `Due(reason)`;
- `NotDue(nextEligibleAt)`;
- `Disabled(reason)`.

A never-crawled site is immediately due. After success, the next crawl is due at
`finishedAt + successfulInterval`. After failure, policy selects a bounded
backoff from the consecutive failure count. Deployment configuration supplies
the initial defaults; the domain enforces positive durations and maximum bounds.

### 5.2 Retry

The initial retry sequence is 5 minutes, 30 minutes, 2 hours, then the normal
6-hour interval. Retry scheduling is based on persisted crawl history, so it
survives restarts. A single crawl execution does not sleep and retry internally;
the scheduler reevaluates eligibility.

### 5.3 Concurrency and leases

Concurrency limits are execution concerns configured in bootstrap, while the
invariant "at most one active crawl for a career site" is enforced by a
database-backed lease port. The scheduler:

1. queries candidate sites;
2. asks domain policy which are due;
3. attempts to acquire a lease;
4. submits at most the configured global concurrency;
5. releases or expires the lease after completion.

Per-host serialization and spacing are enforced by the shared source HTTP
client because they govern network execution rather than business timing.

### 5.4 Closure grace

A posting missing from one successful complete snapshot remains open. It closes
after two consecutive successful complete snapshots in which it is absent.
Failed or partial crawls do not increment absence. A previously closed posting
present in a later snapshot reopens.

This is modeled with `consecutiveMisses`, not elapsed time, so extra scheduler
runs and retry timing cannot accidentally close postings early.

### 5.5 Suspicious snapshots

A zero-posting snapshot for a site with open postings is rejected as
`SuspiciousEmptySnapshot`. It records a failed crawl and applies no plan. A site
that genuinely has no openings can be transitioned to zero through an explicit
operator override or a later policy extension backed by source-level evidence;
the initial implementation prioritizes preventing mass closure.

## 6. Web protocol policy

### 6.1 robots.txt

The source infrastructure follows RFC 9309 for the configured finds.team
User-Agent:

- fetch `/robots.txt` before other paths when there is no fresh cached policy;
- choose the most specific matching user-agent group;
- use longest-path matching and allow on equal-length allow/disallow conflicts;
- follow RFC status handling and redirect limits;
- cap content size and cache successful policy for 24 hours;
- cache transient unavailability for 5 minutes and fail the crawl closed during
  that period rather than guessing permission;
- record a typed `RobotsDenied` or `RobotsUnavailable` outcome.

robots.txt is access guidance, not authorization. Normal HTTP authentication
and response rules still apply.

### 6.2 Sitemaps

The sitemap component supports both `<urlset>` and `<sitemapindex>`. It:

- accepts sitemap locations advertised by robots.txt and the conventional
  `/sitemap.xml` fallback;
- follows nested indexes with bounded depth, total documents, total URLs, and
  response bytes;
- resolves and validates absolute URLs;
- admits posting URLs only when the provider adapter recognizes their paths and
  site ownership;
- preserves optional `lastmod` as a discovery hint but never treats it as proof
  that content is unchanged;
- deduplicates canonical URLs;
- rejects malformed or oversized documents with typed failures.

Custom-domain sitemaps are allowed to reference the registered site host.
Cross-host sitemap submission is not followed in the initial implementation,
because verifying delegated ownership would add security and policy complexity
without helping the first three providers.

### 6.3 HTTP safety and politeness

The shared client uses HTTPS only, blocks redirects to non-HTTPS or disallowed
hosts, rejects private/link-local destinations after DNS resolution, applies
connect/request/site timeouts, caps decompressed response sizes, serializes
requests per host, and waits at least 500 ms between host requests. The
User-Agent identifies finds.team and includes an operator contact URL.

These protections apply equally to provider adapters and sitemap/robots
requests, avoiding provider-specific loopholes.

## 7. Provider discovery and adapters

### 7.1 Discovery

Registration calls `SourceDiscoveryPort.detect(siteUrl)`. Detection proceeds
without fetching job detail pages:

1. match a known managed hostname where available;
2. fetch allowed homepage metadata through the safe HTTP client;
3. inspect provider-specific, stable fingerprints;
4. require exactly one provider match.

No match returns `UnsupportedProvider`; multiple matches return
`AmbiguousProvider`. The detected provider is persisted on `CareerSite`.

This supports Ninehire custom domains without weakening the fast path for
`*.ninehire.site`, `*.careers.team`, and `*.career.greetinghr.com`.

### 7.2 Adapter contract

```kotlin
interface SourceAdapter {
  val provider: SourceProvider
  suspend fun fetch(site: CareerSiteSource): SourceFetchResult
}
```

`SourceFetchResult.Success` contains a complete `Snapshot`.
`SourceFetchResult.Failure` contains a typed transport, robots, parsing,
unsupported-shape, suspicious-empty, or timeout error. Exceptions do not cross
the adapter boundary.

### 7.3 Flex

Flex discovery recognizes direct `*.careers.team` hosts and its stable homepage
bootstrap data. The adapter reads the customer identifier, uses the public job
description endpoint, and returns one raw posting per valid description.

### 7.4 Greeting

Greeting discovery recognizes direct `*.career.greetinghr.com` hosts and
Greeting hydration fingerprints. Its sitemap supplies `/o/{numericId}` URLs.
The adapter parses open posting data from page hydration and treats missing
required hydration structures as a source-shape failure, not as an empty site.

### 7.5 Ninehire

Ninehire discovery recognizes direct `*.ninehire.site` hosts and stable
Ninehire page fingerprints for custom domains. Sitemaps discover
`/job_posting/{externalKey}` URLs. Tracking query parameters and `/apply`
suffixes are removed when producing canonical posting URLs.

The adapter extracts structured page/bootstrap data when present and uses HTML
selection only through narrowly scoped, fixture-tested fallbacks. Redirects to
`/invalid` or explicit stopped-application pages are source facts for that URL;
they do not make the whole snapshot partial when discovered consistently.

## 8. Application flows

### 8.1 Register career site

1. Parse and validate the submitted URL.
2. Ask source discovery to identify the provider.
3. Canonicalize the base URL.
4. Insert the career site or return `AlreadyRegistered`.
5. Return the site immediately; crawling is scheduled separately.

Registration never performs a complete crawl inside the API request.

### 8.2 Crawl site

1. Load the site and crawl history.
2. For scheduled calls, verify domain eligibility; manual triggers bypass only
   timing, not robots, leases, safety, or validation.
3. Acquire a site lease and create a `RUNNING` crawl run.
4. Fetch a complete snapshot through the selected adapter.
5. Load current postings and reconcile in the domain.
6. In one transaction, apply the sync plan and mark the crawl `SUCCESS`.
7. On any failure, mark the crawl `FAILED`, apply no plan, and release the lease.

### 8.3 Search postings

GraphQL input maps to the domain `Filter` algebra. The persistence adapter
translates the normalized filter to jOOQ conditions. Open status is added at the
top level when the caller does not specify status. Cursor pagination orders by
`updatedAt DESC, id DESC` so ties are stable.

## 9. Persistence design

Flyway migrations are authoritative. jOOQ generates Kotlin-accessible schema
types during the build and generated output is ignored by Git.

### 9.1 Tables

- `career_site`: canonical URL/host, provider, display name, crawl settings,
  enabled state, timestamps.
- `job_posting`: site/external identity, normalized content, status, hash,
  timestamps, consecutive misses, searchable attributes.
- `job_posting_skill`: normalized skills and requirement level.
- `crawl_run`: timing, outcome, counts, source revision, typed error code and
  sanitized message.
- `crawl_lease`: site identity, owner, acquisition and expiry timestamps.

The unique posting key is `(career_site_id, external_key)`. `SyncPlan`
application locks the site's relevant posting rows and upserts deterministically
inside one transaction.

### 9.2 Filter equivalence

`Filter.matches(posting)` is the reference semantics. Property-based
integration tests generate filters and postings, persist the postings, execute
the jOOQ condition, and require the same selected IDs. This guards especially
against incorrect negated skill joins.

## 10. API contract

The API is schema-first GraphQL. Initial operations are:

- `postings(filter, first, after, orderBy)`;
- `posting(id)`;
- `careerSites`;
- `crawlStatus`;
- `registerCareerSite(url, name)`;
- `triggerCrawl(careerSiteId)`.

Registration and crawl failures use typed payloads or stable GraphQL error
extensions. Raw exception messages, SQL details, source response bodies, and
internal URLs are not returned.

## 11. Error handling and observability

Expected domain and integration failures are sealed result types. Exceptions
are caught at infrastructure boundaries, logged with a correlation/crawl-run
identifier, and converted to typed failures. Programmer errors remain
exceptions and fail tests rather than being silently converted.

Every crawl records provider, site ID, run ID, duration, outcome, fetched and
changed counts, and stable error code. Logs avoid full posting bodies and query
parameters. Metrics expose due sites, active leases, crawl outcomes/durations,
and posting changes by provider.

## 12. Testing strategy

- Domain: example and property tests for invariants, filter normalization,
  eligibility, backoff, reconciliation, closure, reopening, and suspicious
  snapshots.
- Application: use cases against hand-written deterministic fakes and a fake
  clock; no mocking of domain decisions.
- Source: sanitized fixtures for every provider, Ktor MockEngine tests for
  robots/sitemaps/redirects/limits/spacing, and separately tagged live smoke
  tests excluded from CI.
- Persistence: Testcontainers PostgreSQL for migrations, repository behavior,
  transactionality, plan idempotence, leases, and filter equivalence.
- GraphQL: schema loading, resolver coverage, mapping tables, pagination, and
  typed error integration tests.
- Bootstrap: a PostgreSQL-backed vertical integration test from registration
  through crawl, reconciliation, and search.
- Architecture: dependency/import constraints, including a hard assertion that
  `domain` has no Spring, jOOQ, Ktor, JDBC, or filesystem imports.

No test that determines business behavior requires Spring, HTTP, or a database.

## 13. Migration and compatibility

The prototype has no production migration requirement recorded in the
repository. The new Flyway schema therefore starts clean rather than attempting
to infer or migrate ephemeral H2 state. If a production database is identified
before legacy removal, a separate explicit import tool will be designed from
the observed schema; it will not alter the domain architecture.

Migration order follows `ROADMAP.md`. Flex and Greeting fixture expectations
are ported before old parsers are removed. The legacy REST API is removed when
the GraphQL vertical slice passes; maintaining two public contracts is outside
scope because no compatibility commitment is documented.

## 14. Security and operational constraints

- Only public HTTPS career sites are supported.
- No login, cookies, browser automation, or access-control bypass is attempted.
- robots.txt denial prevents fetching the denied resource.
- URL and redirect validation mitigate SSRF, DNS rebinding, and private-network
  access.
- Source response sizes, sitemap recursion, URL counts, and parsing work are
  bounded.
- Crawl leases expire so crashed workers do not block sites permanently.
- Database credentials and contact identity come from runtime configuration.

## 15. Completion criteria

The design is implemented only when:

1. PostgreSQL/Flyway/jOOQ is the sole persistence path.
2. Domain logic is pure and protected by architecture tests.
3. Crawl cadence, retry, closure, and reconciliation policies are independent
   of Flex, Greeting, and Ninehire adapters.
4. Robots, sitemaps, politeness, safety, and all three providers have
   fixture-backed tests.
5. Scheduling, leasing, registration, crawling, storage, and search work in one
   verified vertical slice.
6. JPA, H2, synchronous registration crawling, and legacy controllers are gone.
7. The full clean build and the `ROADMAP.md` completion audit pass.
