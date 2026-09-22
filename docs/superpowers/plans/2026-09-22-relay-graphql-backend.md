# Relay GraphQL Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the backend Relay-compatible and expose the normalized posting, company, skill, viewer, crawl, and audit data required by every approved route.

**Architecture:** Schema changes land before resolver changes. Domain classifiers enrich crawled postings; application queries expose framework-neutral pages; persistence implements stable cursor and batch queries; adapter-graphql alone handles opaque global IDs and Relay shapes.

**Tech Stack:** GraphQL Java, Relay compiler contract, Kotlin domain/application, jOOQ/PostgreSQL, property testing, Testcontainers

**Spec:** `docs/superpowers/specs/2026-09-22-relay-graphql-design.md`

## Global Constraints

- Domain ids remain typed internal values; only GraphQL adapter encodes global IDs.
- Every collection is a bounded forward connection with complete PageInfo.
- Every ordering includes a unique tie-breaker and opaque cursor validation.
- Unknown skills and invalid global-id types are typed errors, not empty data.
- Resolver relationship loads are batched; representative pages have query-count ceilings.
- Schema and Relay artifacts must change in the same delivery slice.

## Review Focus

- Equal sort values and concurrent inserts must not duplicate or skip cursor pages.
- A `CareerSite` global id passed to `jobPosting` must fail as a typed id mismatch.
- Recursive filter normalization must preserve SQL/in-memory equivalence with new dimensions.
- Skill/company relationship fields must avoid N+1 queries.
- Anonymous `viewer` must be null while authenticated viewer data never enters another SSR request.

---

### Task 1: Relay Node and global IDs

**Files:**
- Modify: `schema/finds.graphqls`
- Create: `backend/adapter-graphql/src/main/kotlin/dev/moreal/finds/graphql/GlobalId.kt`
- Modify: `backend/adapter-graphql/src/main/kotlin/dev/moreal/finds/graphql/GraphqlRuntime.kt`
- Create: `backend/adapter-graphql/src/test/kotlin/dev/moreal/finds/graphql/GlobalIdTest.kt`
- Modify: `backend/adapter-graphql/src/test/kotlin/dev/moreal/finds/graphql/SchemaContractTest.kt`

**Interfaces:**
- Produces: `interface Node`, `Query.node(id)`, `GlobalIdCodec.encode(type, value)` and `decode(expectedType, id)`.

- [ ] **Step 1: Write failing global-id contract tests**

Round-trip every node type, reject malformed Base64URL, missing discriminator, negative id, and type confusion. Assert introspection shows `Node` and `Query.node`.

- [ ] **Step 2: Update the schema first**

Make `JobPosting`, `CareerSite`, `User`, `Skill`, `CrawlRun`, and `AuditEvent` implement Node where they have stable identities. Add complete PageInfo fields.

- [ ] **Step 3: Implement codec and node dispatch**

Encode a versioned payload such as `v1:JobPosting:123`; treat the encoding as opaque and keep decoder errors typed.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :adapter-graphql:test --tests '*GlobalIdTest' --tests '*SchemaContractTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add schema/finds.graphqls backend/adapter-graphql
git commit -m "feat(graphql): add Relay node identity" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 2: Posting enrichment domain

**Files:**
- Create: `backend/domain/src/main/kotlin/dev/moreal/finds/domain/posting/Classification.kt`
- Create: `backend/domain/src/main/kotlin/dev/moreal/finds/domain/posting/SkillTaxonomy.kt`
- Create: `backend/domain/src/main/resources/skills.yaml`
- Modify: `backend/domain/src/main/kotlin/dev/moreal/finds/domain/posting/Posting.kt`
- Modify: `backend/domain/src/main/kotlin/dev/moreal/finds/domain/search/Filter.kt`
- Create: `backend/domain/src/test/kotlin/dev/moreal/finds/domain/posting/ClassificationTest.kt`
- Create: `backend/domain/src/test/kotlin/dev/moreal/finds/domain/posting/SkillTaxonomyTest.kt`
- Modify: `backend/domain/src/test/kotlin/dev/moreal/finds/domain/search/FilterTest.kt`

**Interfaces:**
- Produces: `SkillRequirementLevel(REQUIRED, PREFERRED, MENTIONED)`, role, employment, remote and location normalized values plus new filter nodes.

- [ ] **Step 1: Write failing fixture/table tests**

Use Korean/English headings and hints to distinguish required/preferred skills, classify common role/employment/remote values, preserve unknown values, and resolve aliases to canonical slugs.

- [ ] **Step 2: Extend filter algebra and properties**

Add skill+level, role, employment, remote, and normalized location filters. Property tests assert normalization preserves `matches` semantics, including nested `not/all/any`.

- [ ] **Step 3: Implement deterministic classifiers**

Taxonomy loading is versioned and immutable at runtime. Seed canonical entries and common Korean/English aliases for Kotlin, Java, Spring, JavaScript, TypeScript, React, Vue, SolidJS, Node.js, Python, Django, FastAPI, Go, Rust, C, C++, C#, .NET, Swift, iOS, Android, Flutter, PostgreSQL, MySQL, Redis, MongoDB, Kafka, AWS, GCP, Azure, Docker, Kubernetes, Terraform, Linux, Git, GraphQL, REST, Spark, Airflow, PyTorch, and TensorFlow. No I/O or ML service enters domain classification.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :domain:test --tests '*Classification*' --tests '*SkillTaxonomy*' --tests '*Filter*'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/domain
git commit -m "feat(domain): enrich posting classifications" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 3: Persist enrichment and stable company slugs

**Files:**
- Create: `backend/adapter-persistence/src/main/resources/db/migration/V5__posting_enrichment.sql`
- Modify: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqSuccessfulCrawlAdapter.kt`
- Modify: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqPostingRepository.kt`
- Modify: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqCareerSiteRepository.kt`
- Create: `backend/adapter-persistence/src/test/kotlin/dev/moreal/finds/persistence/PostingEnrichmentPersistenceTest.kt`
- Modify: `backend/adapter-persistence/src/test/kotlin/dev/moreal/finds/persistence/JooqPostingRepositoryTest.kt`

**Interfaces:**
- Produces normalized posting columns, requirement-level posting skills, stable unique site slug, and jOOQ filter translation.

- [ ] **Step 1: Write failing migration/repository tests**

Assert slug collision resolution/stability, requirement-level uniqueness, enrichment round-trip, new SQL filters, and in-memory/SQL filter equivalence.

- [ ] **Step 2: Add migration and reconciliation writes**

Backfill existing sites with deterministic collision-resolved slugs and existing skills as `MENTIONED`. Persist future enriched values during successful crawl transaction.

- [ ] **Step 3: Verify**

Run: `cd backend && ./gradlew :adapter-persistence:test --tests '*PostingEnrichment*' --tests '*JooqPostingRepositoryTest'`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/adapter-persistence
git commit -m "feat(persistence): store posting enrichment" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 4: Stable connection application/persistence ports

**Files:**
- Modify: `backend/application/src/main/kotlin/dev/moreal/finds/application/model/ApplicationTypes.kt`
- Modify: `backend/application/src/main/kotlin/dev/moreal/finds/application/port/PostingPorts.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/port/DiscoveryQueryPorts.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/GetJobPosting.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/GetCareerSite.kt`
- Create: `backend/application/src/main/kotlin/dev/moreal/finds/application/usecase/GetSkill.kt`
- Create: `backend/application/src/test/kotlin/dev/moreal/finds/application/usecase/DiscoveryQueriesTest.kt`
- Create: `backend/adapter-persistence/src/main/kotlin/dev/moreal/finds/persistence/JooqDiscoveryQuery.kt`
- Create: `backend/adapter-persistence/src/test/kotlin/dev/moreal/finds/persistence/JooqStableCursorTest.kt`

**Interfaces:**
- Produces generic `ConnectionPage<T>(edges, pageInfo, totalCount)` and opaque application cursors independent of GraphQL.

- [ ] **Step 1: Write failing page semantics tests**

Cover first bounds, malformed cursor, equal timestamps with id tie-breaker, concurrent newer insert between pages, no duplicate/missing row, and total count under filter.

- [ ] **Step 2: Implement query ports**

Add posting detail, site-by-slug, skill-by-slug, company connection, related skill, and posting connections. Cursors encode order fields and id with a version.

- [ ] **Step 3: Verify**

Run: `cd backend && ./gradlew :application:test --tests '*DiscoveryQueriesTest' :adapter-persistence:test --tests '*JooqStableCursorTest'`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/application backend/adapter-persistence
git commit -m "feat(application): expose discovery connections" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 5: Public discovery GraphQL schema and resolvers

**Files:**
- Modify: `schema/finds.graphqls`
- Create: `backend/adapter-graphql/src/main/kotlin/dev/moreal/finds/graphql/DiscoveryGraphqlMapping.kt`
- Modify: `backend/adapter-graphql/src/main/kotlin/dev/moreal/finds/graphql/FindsGraphqlFacade.kt`
- Modify: `backend/adapter-graphql/src/main/kotlin/dev/moreal/finds/graphql/GraphqlRuntime.kt`
- Create: `backend/adapter-graphql/src/test/kotlin/dev/moreal/finds/graphql/DiscoveryGraphqlTest.kt`
- Create: `backend/bootstrap/src/test/kotlin/dev/moreal/finds_team/graphql/DiscoveryQueryCountTest.kt`

**Interfaces:**
- Produces `jobPosting`, expanded `jobPostings`, `careerSite(s)`, `skill(s)`, and relationship connections.

- [ ] **Step 1: Commit schema-first field additions**

Define normalized enums/objects, recursive filters, orderings, edges/connections, and typed invalid-filter errors without implementing resolvers yet.

- [ ] **Step 2: Write representative failing operations**

Execute job detail, filtered list, company postings, skill companies/postings/related skills, and Node refetch. Add query-count ceilings for nested company/skill pages.

- [ ] **Step 3: Implement mappings and batch loads**

Map pure application values. Add request-scoped batch loaders keyed by internal ids; never process-global cache authenticated fields.

- [ ] **Step 4: Verify**

Run: `cd backend && ./gradlew :adapter-graphql:test :bootstrap:test --tests '*Discovery*'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add schema/finds.graphqls backend/adapter-graphql backend/bootstrap/src/test
git commit -m "feat(graphql): expose public discovery graph" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 6: Viewer, crawl history, and audit GraphQL fields

**Files:**
- Modify: `schema/finds.graphqls`
- Create: `backend/adapter-graphql/src/main/kotlin/dev/moreal/finds/graphql/ViewerGraphqlMapping.kt`
- Create: `backend/adapter-graphql/src/main/kotlin/dev/moreal/finds/graphql/AdminGraphqlMapping.kt`
- Modify: `backend/adapter-graphql/src/main/kotlin/dev/moreal/finds/graphql/GraphqlRuntime.kt`
- Create: `backend/adapter-graphql/src/test/kotlin/dev/moreal/finds/graphql/ViewerGraphqlTest.kt`
- Create: `backend/adapter-graphql/src/test/kotlin/dev/moreal/finds/graphql/AdminGraphqlTest.kt`

**Interfaces:**
- Produces nullable `viewer`, Passkey/session metadata, site crawl-history connections, audit connection, and idempotent admin mutations.

- [ ] **Step 1: Write authorization and redaction tests**

Anonymous viewer is null; authenticated viewer contains metadata without credential material; non-admin crawl/audit access is forbidden; audit details remain allowlisted; mutation payload echoes `clientMutationId` and uses explicit `idempotencyKey`.

- [ ] **Step 2: Implement authenticated resolver context**

Resolve Actor once per request in bootstrap and pass it to adapter-graphql facade methods without importing Spring into adapter-graphql.

- [ ] **Step 3: Verify**

Run: `cd backend && ./gradlew :adapter-graphql:test --tests '*Viewer*' --tests '*Admin*' :bootstrap:test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add schema/finds.graphqls backend
git commit -m "feat(graphql): expose viewer and operations data" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 7: Relay compiler drift and vertical contract

**Files:**
- Modify: `package.json`
- Modify: `frontend/relay.config.json`
- Create: `backend/bootstrap/src/test/kotlin/dev/moreal/finds_team/graphql/RelayVerticalSliceTest.kt`
- Modify: `README.md`

**Interfaces:**
- Produces one root verification command covering schema, backend operations, and frontend artifacts.

- [ ] **Step 1: Add representative frontend operations**

Create named Relay queries/fragments for every public/admin route so compiler validation exercises the final schema.

- [ ] **Step 2: Add assembled backend test**

Seed equal-sort postings and execute Node, filtered pagination, company, skill, viewer, crawl, audit, and replayed mutation operations through `/graphql`.

- [ ] **Step 3: Run complete contract verification**

Run: `cd backend && ./gradlew :domain:test :application:test :adapter-persistence:test :adapter-graphql:test :bootstrap:test && cd .. && pnpm --dir frontend relay:validate`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend frontend package.json README.md
git commit -m "test(graphql): enforce Relay product contract" -m "Assisted-by: Codex:gpt-5.6-sol"
```
