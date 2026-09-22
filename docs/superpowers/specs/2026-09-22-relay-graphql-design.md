# Relay GraphQL Contract and Backend Enrichment Design

## Goal

Expand the schema-first backend into a Relay-compatible product contract for job, company, skill, account, crawl, and audit screens while preserving domain identifiers and clean architecture boundaries.

## Relay requirements

The schema adds:

- `Node { id: ID! }` and root `node(id: ID!): Node`.
- Opaque globally unique IDs encoded and decoded only in `adapter-graphql`.
- Connections with `edges`, `pageInfo`, and `totalCount`.
- Complete `PageInfo`: `hasNextPage`, `hasPreviousPage`, `startCursor`, and `endCursor`.
- Forward pagination with bounded `first` and opaque `after` for every product collection.
- Stable cursors based on explicit ordering plus a unique tie-breaker.

Domain ids remain typed numeric/value ids. The GraphQL adapter encodes a type discriminator and domain id into an opaque external id and rejects type-confused ids.

## Query surface

```graphql
interface Node { id: ID! }

type Query {
  node(id: ID!): Node
  jobPosting(id: ID!): JobPosting
  jobPostings(
    filter: PostingFilterInput
    orderBy: PostingOrder = UPDATED_DESC
    first: Int = 20
    after: String
  ): JobPostingConnection!
  careerSite(slug: String!): CareerSite
  careerSites(first: Int = 20, after: String): CareerSiteConnection!
  skill(slug: String!): Skill
  skills(query: String, first: Int = 20, after: String): SkillConnection!
  viewer: Viewer
  crawlStatuses(first: Int = 50, after: String): CrawlStatusConnection!
  auditEvents(filter: AuditFilterInput, first: Int = 50, after: String): AuditEventConnection!
}
```

`CareerSite` exposes its open-posting connection and crawl summary. Its slug is stored, unique, collision-resolved at registration, and stable across display-name changes. `Skill` exposes related companies, open postings, requirement counts, and related skills. `Viewer` exposes user id, roles, Passkey metadata, and session metadata but no credential material.

## Filter and normalized data

The existing recursive `all`, `any`, and `not` filter algebra is retained and extended with normalized fields for canonical skill plus requirement level, role category, employment type, remote policy, location, career site, text, status, and update time. Unknown canonical skill identifiers are typed validation failures, not silent empty results.

The backend currently stores skill strings without requirement level and raw employment/location/remote hints. This subproject adds deterministic enrichment and persistence for:

- canonical skills and aliases
- `REQUIRED`, `PREFERRED`, or `MENTIONED` relationship
- role category
- normalized employment type
- normalized remote policy
- normalized location display/search values

Pure domain classifiers and taxonomy mapping precede persistence and GraphQL exposure.

## Mutations

Administrator and account-management mutations use input objects with explicit UUID `idempotencyKey` and optional Relay `clientMutationId`. Payloads contain the typed result, expected user-correctable errors, and the echoed client mutation id. Registering a site and triggering a crawl are administrator-only. Credential ceremonies stay on security HTTP endpoints; GraphQL may rename/remove credentials, rotate recovery codes, and revoke sessions after recent-auth checks.

## Error semantics

- Invalid filters, id types, state conflicts, duplicates, and domain validation use typed payload errors.
- Missing authentication and authorization are surfaced consistently for the frontend route boundary.
- Unexpected defects become GraphQL errors with a correlation id and no internal message.
- Field resolvers do not silently convert infrastructure failures into nullable business data.

## Resolver performance

Relationship resolvers batch by stable ids to prevent N+1 queries. Connection repositories return edges and page metadata in bounded queries. Query-count integration tests set explicit ceilings for company, skill, crawl, and audit pages.

## Relay compiler contract

Frontend operations use Relay compiler directives and generated artifacts. CI validates the committed schema, compiles operations, and fails on stale generated output. Components receive fragment keys rather than broad page response objects. Pagination uses connection keys that include every filter and ordering variable.

## Verification

- Schema contract tests cover every root field and object/interface resolution.
- Node tests round-trip every supported type and reject type-confused ids.
- Cursor tests prove no duplicate or missing rows under equal sort values and concurrent inserts.
- Filter property tests keep in-memory and jOOQ semantics equivalent.
- Query-count tests prevent N+1 regressions.
- Relay compiler validation runs in backend schema and frontend changes.
- Representative operations execute through the assembled Spring endpoint against PostgreSQL.
