# Solid Frontend and Design System Design

## Goal

Build an SSR-first SolidJS 2 web application that lets visitors explore jobs by posting, company, and skill; lets all users manage Passkeys and recovery material; and gives administrators an operational view of crawl sources and audit history.

## Runtime and data architecture

TanStack Start owns routing, full-document streaming SSR, document metadata, and deployment bundles. Relay owns GraphQL normalization, fragment composition, and connection pagination. Each SSR request creates a fresh Relay environment, forwards the incoming cookie to Spring GraphQL over an internal service URL, and serializes normalized records into the document. Browser hydration restores those records without repeating the initial query.

Solid 2 integration uses an isolated local binding over Relay runtime because `solid-relay@1.0.0-beta.29` imports removed Solid 1 APIs. The foundation implements only the environment provider/context; product tasks extend query, fragment, pagination, and mutation bindings only as used. Keep this boundary replaceable by upstream bindings once compatible. Until then, local binding correctness is our responsibility.

The Relay compiler launcher refreshes an ignored `.graphql` copy from the committed `schema/finds.graphqls` bytes on every invocation, including validation and Vite codegen, and refreshes it when the canonical schema changes during watch mode. Spring's schema remains authoritative; remove this adapter when Relay supports its extension.

After hydration, the browser network layer calls same-origin `/graphql` directly. Ingress routes GraphQL and security endpoints to Spring and page requests to TanStack Start. There is no Start BFF and no duplicated application session.

## Routes and information hierarchy

Public discovery:

- `/`: a permanent redirect to `/jobs` until a distinct landing page has an approved product purpose.
- `/jobs`: all postings, result count, URL-backed search/filter/order state, and cursor continuation.
- `/jobs/:id`: title, company, employment/location/remote metadata, required/preferred skills, description, dates, and a prominent external application link.
- `/companies/:slug`: company identity, crawl freshness appropriate for public display, and its open postings.
- `/skills/:slug`: hashtag-style skill identity, related companies, related skills, and open postings.

Identity and security:

- `/join`: email OTP then Passkey enrollment and one-time recovery-code presentation.
- `/login`: discoverable Passkey authentication.
- `/recover`: email OTP plus saved recovery code, followed by Passkey replacement.
- `/account/security`: Passkeys, recovery-code rotation, and active sessions.

Administration:

- `/admin`: crawl-health summary ordered by attention required.
- `/admin/sites`: source registration, filters, latest status, and manual crawl action.
- `/admin/sites/:id`: site configuration, crawl history, error summary, change counts, and related audit events.
- `/admin/audit`: actor/action/target/time filters over the append-only ledger.

## URL filter contract

The job list URL is the source of truth for search state. A canonical parser/serializer maps readable parameters such as text, included/excluded skills, role, employment, remote policy, site, update window, and ordering to Relay variables. Invalid or unknown parameters are removed through canonical navigation and announced to assistive technology. Cursor values are not canonical share state; reloading starts from the first page with the same filters.

## Design system

Pages may import only local design-system components, not Kobalte primitives directly. Layers are:

1. Semantic tokens for color, typography, spacing, radius, elevation, focus, z-index, and motion.
2. Native-first controls: Button, Link, TextField, Badge, IconButton, Skeleton, and Separator.
3. Kobalte wrappers: Dialog, Select, Combobox, Popover, Toast, Tabs, and Tooltip.
4. Product patterns: PostingCard, FilterBuilder, ConnectionList, CrawlStatusCell, PasskeyList, AuditTimeline, and AsyncState.

Styling uses route-aware global CSS plus CSS Modules and custom properties. A second utility or CSS-in-JS styling system is not introduced.

## Color system

The approved direction is “warm paper + slate ink + clear blue.” Semantic values use OKLCH.

| Token | Light | Dark |
|---|---|---|
| canvas | `0.98 0.004 85` | `0.19 0.008 255` |
| surface | `1 0 0` | `0.23 0.01 255` |
| elevated | `0.958 0.006 255` | `0.275 0.014 255` |
| text | `0.26 0.022 255` | `0.94 0.008 255` |
| text-muted | `0.49 0.022 255` | `0.73 0.018 255` |
| border-subtle | `0.875 0.012 255` | `0.355 0.014 255` |
| border-control | `0.64 0.02 255` | `0.56 0.022 255` |
| action | `0.49 0.16 260` | `0.77 0.115 260` |
| action-hover | `0.44 0.15 260` | `0.80 0.095 260` |
| action-soft | `0.945 0.025 260` | `0.30 0.05 260` |
| on-action | `0.99 0 0` | `0.19 0.04 260` |
| success-text | `0.45 0.09 155` | `0.78 0.12 155` |
| success-surface | `0.956 0.022 155` | `0.285 0.035 155` |
| warning-text | `0.49 0.095 75` | `0.81 0.12 85` |
| warning-surface | `0.96 0.034 85` | `0.30 0.04 75` |
| danger-text | `0.48 0.16 25` | `0.76 0.135 25` |
| danger-surface | `0.956 0.018 25` | `0.30 0.05 25` |

`border-subtle` is decorative; controls use `border-control`. Primary actions use action fill. Destructive controls use soft danger in ordinary screens and filled danger only in the final confirmation dialog. Status always includes text or icon in addition to color.

## Async and error states

Every data surface implements initial pending, pagination pending, true empty, filtered empty, recoverable error, unauthorized, forbidden, and data states. Initial skeletons preserve layout. Pagination retains existing results. Filtered empty states name active constraints and offer targeted removal. Unexpected errors show a correlation id and retry action without provider details.

Route error boundaries distinguish missing content, authorization, validation, network loss, and unexpected defects. Admin route existence is not advertised to unauthorized users.

## Accessibility and interaction

- Target WCAG 2.2 AA.
- Full keyboard operation, visible focus, explicit label/error association, and live announcements for result and mutation state.
- Prefer 44×44 px touch targets and at least 40×40 px dense desktop targets.
- Apply tabular numerals to counts, durations, and timestamps that update.
- Use `text-wrap: balance` for headings and `text-wrap: pretty` for body copy where supported.
- Motion is limited to short interruptible opacity/transform transitions and is removed or reduced under `prefers-reduced-motion`.
- Server pagination remains authoritative. Virtualization starts only after hydration and only after measured row-count/DOM thresholds.

## Testing

- Vitest tests URL parser/serializer, Relay environment creation, component states, and mutations.
- DOM tests cover keyboard interactions and accessible names.
- Automated axe checks run on the component catalog and primary routes.
- Playwright covers SSR HTML, hydration without duplicate fetch, filter URL persistence, pagination, enrollment/login/recovery with virtual authenticator, and administrator workflows.
- Visual snapshots cover light/dark themes at mobile and desktop widths for the component catalog and main routes.
- A two-user SSR concurrency test proves no Relay record or authenticated viewer leakage between requests.
