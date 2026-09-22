# ADR 0001: PostgreSQL, Flyway, and jOOQ

## Status

Accepted on 2026-09-22.

## Context

finds.team needs composable, exact filtering, transactional posting
reconciliation, stable pagination, and database-backed crawl leases. The
prototype uses JPA with an ephemeral H2 database. Its entity lifecycle and
derived SQL obscure the behavior that the domain has already decided, while H2
does not reproduce PostgreSQL search, locking, or conflict behavior.

## Decision

- PostgreSQL 16 is the production and integration-test database.
- Flyway SQL migrations are the sole authority for the database schema.
- jOOQ generated types and its DSL are the sole application SQL layer.
- jOOQ code is generated during the build from the migrated schema and is not
  committed.
- Repository adapters translate between jOOQ records and domain values; jOOQ
  types never cross the persistence-adapter boundary.
- Persistence integration tests run against PostgreSQL with Testcontainers.
- The JPA/H2 path remains only until the replacement vertical slice is proven,
  then is deleted rather than maintained in parallel.

## Consequences

Queries and transaction boundaries become explicit and can be tested against
the real dialect. Filter-to-SQL translation requires deliberate implementation
and equivalence tests. Builds that generate jOOQ sources need a schema-generation
step, and persistence tests need a container runtime.
