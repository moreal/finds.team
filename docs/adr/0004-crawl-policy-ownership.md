# ADR 0004: Separate Crawl Decisions from Source Execution

## Status

Accepted on 2026-09-22.

## Context

A provider parser knows how to discover and parse source facts. It should not
decide how often a company is crawled, how failures back off, when an absent
posting closes, or whether a snapshot is safe to apply. Mixing these concerns
would make policy vary accidentally between Flex, Greeting, and Ninehire.

## Decision

- The pure domain owns crawl eligibility, retry delay selection, close grace,
  suspicious-snapshot rejection, and snapshot reconciliation.
- Application use cases load history, request a domain decision, invoke a source
  port, and apply the returned sync plan.
- Source adapters perform I/O and structural parsing only and return complete
  source facts or a typed failure.
- Bootstrap owns scheduler polling and global concurrency configuration.
- The source HTTP infrastructure owns per-host serialization, request spacing,
  timeouts, robots.txt enforcement, sitemap limits, redirects, and response-size
  limits.
- Persistence owns atomic leases and transactional sync-plan execution.

## Consequences

All providers follow the same lifecycle rules and policy tests need no HTTP,
Spring, or database. Execution mechanisms remain independently configurable.
Adding a provider requires parsing and fixtures, not another implementation of
cadence or closure logic.
