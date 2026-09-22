# ADR 0002: Expected Failures Use Sealed Results

## Status

Accepted on 2026-09-22.

## Context

Invalid URLs, unsupported providers, denied crawling, malformed source data,
suspicious snapshots, and duplicate registrations are expected outcomes. Using
exceptions for these paths makes orchestration implicit and encourages
transport or infrastructure details to leak into the domain.

## Decision

- Domain and application operations return sealed result types for expected
  failures.
- Result variants carry stable, typed facts rather than throwable instances or
  presentation-ready messages.
- Invalid construction of a value that cannot represent a valid domain state
  uses `require` and fails immediately.
- Infrastructure adapters catch network, parsing, and database exceptions at
  their boundary, log them with operational context, and convert them into
  typed application failures.
- Programmer errors and violated internal assumptions remain exceptions so
  tests and monitoring expose them.
- Arrow is not introduced; Kotlin sealed types and exhaustive `when` expressions
  are sufficient.

## Consequences

Callers must handle all expected outcomes explicitly and the compiler protects
exhaustive branching. Adapter code has a small amount of mapping boilerplate.
Internal defects are not accidentally presented as user-correctable failures.
