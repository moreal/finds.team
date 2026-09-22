# ADR 0003: Detect and Persist Source Providers

## Status

Accepted on 2026-09-22.

## Context

Flex, Greeting, and Ninehire have recognizable managed hostnames, but Ninehire
and future providers support custom domains. Selecting an adapter from a host
regex on every crawl cannot support those domains and can silently choose the
wrong parser as providers evolve.

## Decision

Registration performs provider discovery once through a source-discovery port:

1. Use an exact managed-host pattern as a non-network fast path.
2. For other public HTTPS hosts, fetch only robots-allowed homepage metadata
   through the shared safe HTTP client.
3. Evaluate provider-owned, fixture-tested fingerprints.
4. Accept exactly one match; return typed `UnsupportedProvider` or
   `AmbiguousProvider` results otherwise.
5. Persist the selected `SourceProvider` on the career site and dispatch future
   crawls by that value.

Redirect, DNS, response-size, and private-network protections apply during
discovery exactly as they do during crawling.

## Consequences

Custom domains work without weakening managed-host validation. Registering a
custom domain performs a small network request. Fingerprints become versioned
adapter behavior and require recorded fixtures when provider pages change.
