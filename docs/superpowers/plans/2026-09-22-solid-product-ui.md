# Solid Product UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved public discovery, account security, and administrator experiences with a shared accessible design system and SSR-first Relay data flow.

**Architecture:** File routes own URL parsing and page-level Relay queries. Components consume generated fragments. Local design-system wrappers own accessible primitives and semantic tokens; product patterns compose them without importing Kobalte or virtualization libraries directly.

**Tech Stack:** SolidJS 2 RC, TanStack Start 2 RC, Relay runtime through the approved local Solid 2 binding, CSS Modules/custom properties, Kobalte adapter, TanStack Virtual adapter, Vitest, Testing Library, axe, Playwright

**Spec:** `docs/superpowers/specs/2026-09-22-solid-frontend-design.md`

## Global Constraints

- `/` permanently redirects to `/jobs`.
- Job filters and ordering are canonical URL state; cursors are not share state.
- Initial public content must exist in SSR HTML without JavaScript.
- Hydration must not duplicate the initial Relay request.
- All page controls come from local UI modules and meet WCAG 2.2 AA.
- Light/dark colors use the approved OKLCH semantic tokens.
- Server pagination remains authoritative; virtualization is hydration-only and thresholded.

## Review Focus

- Unknown/invalid URL filters must canonicalize without losing valid filters and announce the correction.
- Filtered empty, true empty, pagination pending, authorization, and network error states must remain distinct.
- External application links must be visually and semantically identified as leaving finds.team.
- Mobile filter dialog must restore focus and expose every desktop filter.
- Two concurrent authenticated SSR requests must never display another user's viewer/admin data.

---

### Task 1: Semantic tokens and component catalog

**Files:**
- Create: `frontend/src/ui/tokens.css`
- Create: `frontend/src/ui/foundations.css`
- Create: `frontend/src/ui/Button.tsx`
- Create: `frontend/src/ui/Link.tsx`
- Create: `frontend/src/ui/TextField.tsx`
- Create: `frontend/src/ui/Badge.tsx`
- Create: `frontend/src/ui/IconButton.tsx`
- Create: `frontend/src/ui/Skeleton.tsx`
- Create: `frontend/src/ui/AsyncState.tsx`
- Create: `frontend/src/routes/__dev/ui.tsx`
- Create: `frontend/src/ui/__tests__/foundations.test.tsx`
- Create: `frontend/e2e/ui-catalog.spec.ts`

**Interfaces:**
- Produces semantic color/spacing/type/elevation/focus/motion tokens and native-first primitives.

- [ ] **Step 1: Write failing state/accessibility tests**

Render every primitive in default, hover-equivalent, focus, active, disabled, invalid, loading, light, and dark states. Assert accessible names, error association, and 40/44 px hit areas.

- [ ] **Step 2: Implement approved tokens**

Encode every light/dark OKLCH value from the spec, separate decorative/control borders, use action fill for primary CTA, soft danger ordinarily, filled danger only in final confirmation.

- [ ] **Step 3: Add component catalog route**

The route is included only in development builds and displays every state, Korean copy, long labels, and dynamic tabular numbers.

- [ ] **Step 4: Verify axe and visual snapshots**

Run: `pnpm --dir frontend test --run src/ui && pnpm --dir frontend exec playwright test e2e/ui-catalog.spec.ts`

Expected: PASS in light/dark desktop/mobile projects with no serious axe violations.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(ui): establish accessible design system" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 2: Composite UI wrappers and product patterns

**Files:**
- Create: `frontend/src/ui/Dialog.tsx`
- Create: `frontend/src/ui/Select.tsx`
- Create: `frontend/src/ui/Combobox.tsx`
- Create: `frontend/src/ui/Popover.tsx`
- Create: `frontend/src/ui/Toast.tsx`
- Create: `frontend/src/ui/Tabs.tsx`
- Create: `frontend/src/ui/Tooltip.tsx`
- Create: `frontend/src/features/postings/PostingCard.tsx`
- Create: `frontend/src/features/postings/ConnectionList.tsx`
- Create: `frontend/src/features/admin/CrawlStatusCell.tsx`
- Create: `frontend/src/features/security/PasskeyList.tsx`
- Create: `frontend/src/features/admin/AuditTimeline.tsx`
- Create: `frontend/src/ui/__tests__/composites.test.tsx`

**Interfaces:**
- Consumes compatibility adapters from the foundation plan.
- Produces product-facing component APIs without Kobalte types.

- [ ] **Step 1: Write keyboard and async-state tests**

Test focus trap/restoration, Escape, arrow selection, live announcements, loading-disabled mutations, empty/error rendering, and no high-frequency decorative motion.

- [ ] **Step 2: Implement wrappers and patterns**

Use exact-property transitions under 150 ms for routine state changes and honor reduced motion. Apply tabular numerals to crawl counts/times.

- [ ] **Step 3: Verify**

Run: `pnpm --dir frontend test --run src/ui src/features`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/src
git commit -m "feat(ui): add product interaction patterns" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 3: Canonical job-filter URL codec

**Files:**
- Create: `frontend/src/features/postings/filterSchema.ts`
- Create: `frontend/src/features/postings/filterCodec.ts`
- Create: `frontend/src/features/postings/filterVariables.ts`
- Create: `frontend/src/features/postings/__tests__/filterCodec.test.ts`
- Create: `frontend/src/features/postings/__tests__/filterVariables.test.ts`

**Interfaces:**
- Produces: `parseJobSearch(search): ParseResult`, `serializeJobSearch(state): string`, and `toPostingFilterInput(state)`.

- [ ] **Step 1: Write property/table tests**

Cover text, include/exclude skills, role, employment, remote, company, update window, order, Unicode, duplicates, invalid enums, unknown keys, canonical ordering, and parse/serialize round trips.

- [ ] **Step 2: Implement canonical codec**

Use readable repeated params and explicit negative skill prefix. Drop invalid values while returning corrections for an accessible announcement. Never serialize pagination cursor.

- [ ] **Step 3: Map to recursive GraphQL input**

Positive filters form `all`; exclusions become `not` children. Preserve required/preferred/mentioned semantics.

- [ ] **Step 4: Verify**

Run: `pnpm --dir frontend test --run src/features/postings/__tests__/filter*`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/postings
git commit -m "feat(frontend): encode job filters in URLs" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 4: Job list and filter experience

**Files:**
- Create: `frontend/src/routes/jobs/index.tsx`
- Create: `frontend/src/features/postings/JobsPageQuery.ts`
- Create: `frontend/src/features/postings/FilterBuilder.tsx`
- Create: `frontend/src/features/postings/JobsConnection.tsx`
- Create: `frontend/src/features/postings/__tests__/FilterBuilder.test.tsx`
- Create: `frontend/e2e/jobs-list.spec.ts`
- Modify: `frontend/src/routes/index.tsx`

**Interfaces:**
- Consumes: URL codec, Relay posting connection, `PostingCard`, `ConnectionList`, and optional `VirtualList`.

- [ ] **Step 1: Write failing route/E2E tests**

Assert `/` redirects permanently, SSR HTML contains first-page posting titles, URL filters produce expected GraphQL variables, reload persists filters, mobile dialog matches desktop controls, filtered empty suggests removing constraints, and pagination retains old results.

- [ ] **Step 2: Implement route query and metadata**

Validate route search before query execution. Set canonical URL, title, description, and noindex only for invalid/unrecoverable filter states.

- [ ] **Step 3: Implement progressive list**

Render the server page normally. Enable virtualization only after hydration and threshold measurement; loading another cursor never replaces existing cards with skeletons.

- [ ] **Step 4: Verify**

Run: `pnpm --dir frontend test --run src/features/postings && pnpm --dir frontend exec playwright test e2e/jobs-list.spec.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): build searchable job list" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 5: Job, company, and skill detail routes

**Files:**
- Create: `frontend/src/routes/jobs/$id.tsx`
- Create: `frontend/src/routes/companies/$slug.tsx`
- Create: `frontend/src/routes/skills/$slug.tsx`
- Create: `frontend/src/features/postings/JobDetail.tsx`
- Create: `frontend/src/features/companies/CompanyPage.tsx`
- Create: `frontend/src/features/skills/SkillPage.tsx`
- Create: `frontend/e2e/discovery-details.spec.ts`

**Interfaces:**
- Consumes: generated refetchable fragments and connections for posting/company/skill.

- [ ] **Step 1: Write failing SSR/SEO tests**

Assert titles/descriptions/canonical URLs, 404 handling, structured visible metadata, required vs preferred skill labels, external-link name, company pagination, skill company/posting lists, and related skills.

- [ ] **Step 2: Implement fragment-driven pages**

Keep page queries thin and pass fragment keys to components. External application CTA opens the canonical posting URL with safe external-link attributes and visible `새 창`/external semantics.

- [ ] **Step 3: Verify**

Run: `pnpm --dir frontend exec playwright test e2e/discovery-details.spec.ts && pnpm --dir frontend build`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add discovery detail pages" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 6: Enrollment, login, recovery, and account security UI

**Files:**
- Create: `frontend/src/routes/join.tsx`
- Create: `frontend/src/routes/login.tsx`
- Create: `frontend/src/routes/recover.tsx`
- Create: `frontend/src/routes/account/security.tsx`
- Create: `frontend/src/features/security/webauthn.ts`
- Create: `frontend/src/features/security/OtpForm.tsx`
- Create: `frontend/src/features/security/RecoveryCodeDisplay.tsx`
- Create: `frontend/src/features/security/SecurityPage.tsx`
- Create: `frontend/e2e/authentication.spec.ts`

**Interfaces:**
- Consumes approved security HTTP endpoints plus Relay viewer/account-management mutations.

- [ ] **Step 1: Write failing virtual-authenticator journeys**

Cover enrollment email/code/Passkey, one-time recovery display/acknowledgement, Passkey login, absence of OTP login, wrong/expired code, two-proof recovery, old credential failure, Passkey rename/remove, and other-session revoke.

- [ ] **Step 2: Implement WebAuthn binary codec**

Convert only documented challenge/user/credential fields between Base64URL and ArrayBuffer. Surface browser cancellation separately from server validation failure.

- [ ] **Step 3: Implement guarded route states**

Enrollment/recovery sessions can only render their completion screens. Recovery code display blocks navigation until the user confirms it was stored, while still allowing safe download/print without transmitting it elsewhere.

- [ ] **Step 4: Verify**

Run: `pnpm --dir frontend exec playwright test e2e/authentication.spec.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add Passkey account flows" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 7: Administrator operations UI

**Files:**
- Create: `frontend/src/routes/admin/index.tsx`
- Create: `frontend/src/routes/admin/sites/index.tsx`
- Create: `frontend/src/routes/admin/sites/$id.tsx`
- Create: `frontend/src/routes/admin/audit.tsx`
- Create: `frontend/src/features/admin/AdminDashboard.tsx`
- Create: `frontend/src/features/admin/RegisterSiteDialog.tsx`
- Create: `frontend/src/features/admin/SiteDetail.tsx`
- Create: `frontend/src/features/admin/AuditFilters.tsx`
- Create: `frontend/e2e/admin.spec.ts`

**Interfaces:**
- Consumes viewer roles, crawl/site/audit connections, explicit idempotency keys, and recent-auth errors.

- [ ] **Step 1: Write failing authorization/operation tests**

Anonymous redirects to login; ordinary users see forbidden without admin data; admins see attention-ordered statuses; registration double-submit uses one key; crawl retry returns same run; step-up prompt resumes the intended action; audit filters persist in URL.

- [ ] **Step 2: Implement information hierarchy**

Dashboard orders failed/stale/running/healthy. Site detail moves from status summary to cause/history and then action. Destructive or high-impact actions require confirmation and recent Passkey.

- [ ] **Step 3: Verify**

Run: `pnpm --dir frontend exec playwright test e2e/admin.spec.ts`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend
git commit -m "feat(frontend): build administrator console" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 8: Final accessibility, SSR-isolation, and production verification

**Files:**
- Create: `frontend/e2e/accessibility.spec.ts`
- Create: `frontend/e2e/ssr-isolation.spec.ts`
- Modify: `frontend/package.json`
- Modify: `package.json`
- Modify: `README.md`

**Interfaces:**
- Produces root `pnpm frontend:check` covering all frontend acceptance gates.

- [ ] **Step 1: Add primary-route accessibility matrix**

Run axe and keyboard journeys on jobs, detail, company, skill, join, login, recovery, security, admin dashboard, site detail, and audit in light/dark mobile/desktop projects.

- [ ] **Step 2: Add simultaneous SSR identity isolation test**

Issue concurrent requests with two session cookies and unique seeded viewer/site markers. Assert neither HTML nor serialized Relay records contains the other marker.

- [ ] **Step 3: Run the entire product UI gate**

Run: `pnpm frontend:check`

Expected: Relay validation, typecheck, Vitest, Playwright, and production build all PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend package.json README.md
git commit -m "test(frontend): verify complete product experience" -m "Assisted-by: Codex:gpt-5.6-sol"
```
