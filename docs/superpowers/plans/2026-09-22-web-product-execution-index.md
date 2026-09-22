# Web Product Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved finds.team web product by executing six focused plans in dependency-safe order.

**Architecture:** Compatibility risk is retired first. Mail and transactional foundations precede identity; identity unlocks audited administrator commands and authenticated Relay fields; backend Relay contract precedes route implementation; final verification crosses every boundary.

**Tech Stack:** Kotlin/JVM 25, Spring Boot/Security, jOOQ/PostgreSQL, SolidJS 2 RC, TanStack Start 2 RC, Relay, Kobalte adapter, TanStack Virtual adapter, pnpm, Gradle

**Spec:** `docs/superpowers/specs/2026-09-22-web-product-program-design.md`

## Global Constraints

- Do not begin a dependent gate until every command in its prerequisite gate passes.
- Preserve user-owned `.idea/`, `.superpowers/`, and untracked `PRD.md` unless the user separately changes scope.
- Every created/amended commit contains exactly `Assisted-by: Codex:gpt-5.6-sol` once.
- Use test-first steps and independently review every task before advancing.
- Keep externally retryable operations idempotent end-to-end.

## Review Focus

- Prerelease frontend incompatibility must stop feature work rather than being hidden by type suppression.
- Audit/transaction foundation must exist before identity state-changing use cases are wired to production.
- Authentication must be green before authenticated GraphQL/admin UI work.
- Schema/codegen drift must be impossible at every frontend/backend integration point.
- Final verification must start from clean installs/build outputs, not incremental state.

---

### Gate 1: Frontend compatibility

**Plan:** [Frontend Compatibility Foundation](2026-09-22-frontend-compatibility-foundation.md)

- [ ] Execute Tasks 1–7.
- [ ] Run `pnpm frontend:check` from a clean install.
- [ ] Record the validated Kobalte and virtualization adapter branches in `frontend/COMPATIBILITY.md`.

### Gate 2: Mail and transaction foundations

**Plans:**
- [Mail Delivery](2026-09-22-mail-delivery.md)
- [Transactional Audit and Idempotency](2026-09-22-transactional-audit-idempotency.md)

- [ ] Execute Mail Tasks 1–6.
- [ ] Execute Audit Tasks 1–4.
- [ ] Execute Mail Task 7 using the production transaction/outbox foundation.
- [ ] Run all mail modules plus application and persistence tests.

### Gate 3: Identity and protected commands

**Plan:** [Passkey Identity](2026-09-22-passkey-identity.md)

- [ ] Execute Identity Tasks 1–7.
- [ ] Execute Audit Tasks 5–7 after Actor/session adapters exist.
- [ ] Run the authentication vertical slice and all audit/idempotency tests.

### Gate 4: Relay backend contract

**Plan:** [Relay GraphQL Backend](2026-09-22-relay-graphql-backend.md)

- [ ] Execute Tasks 1–7.
- [ ] Run the backend full suite and frontend Relay validation.

### Gate 5: Product UI

**Plan:** [Solid Product UI](2026-09-22-solid-product-ui.md)

- [ ] Execute Tasks 1–8.
- [ ] Run `pnpm frontend:check` in light/dark mobile/desktop projects.

### Gate 6: Whole-program verification

**Files:**
- Modify: `README.md`
- Modify: `ROADMAP.md`

- [ ] Run backend clean verification.

Run: `cd backend && ./gradlew clean check`

Expected: all domain, application, adapter, persistence, security, GraphQL, and bootstrap tests PASS.

- [ ] Run frontend clean verification.

Run: `pnpm install --frozen-lockfile && pnpm frontend:check`

Expected: Relay validation, typecheck, Vitest, Playwright, accessibility, SSR isolation, and production build PASS.

- [ ] Run assembled local smoke test.

Run: `docker compose up -d postgres && cd backend && ./gradlew :bootstrap:bootRun`

Expected: health is UP; public SSR pages return populated HTML; recording/local SMTP enrollment reaches Passkey registration; administrator site registration and crawl trigger produce one audited effect under a replayed idempotency key.

- [ ] Update durable documentation with only verified commands and results.

- [ ] Commit completion evidence.

```bash
git add README.md ROADMAP.md
git commit -m "docs: record web product completion" -m "Assisted-by: Codex:gpt-5.6-sol"
```
