# Proposal: marketplaces-table-and-audit-clarity

## Why

Two portal surfaces have outgrown their first shape as the estate grows.

The **Marketplaces** page renders one oversized card per marketplace, each
carrying its whole snapshot table inline. A handful of marketplaces already fill
the viewport, and there is no way to scan names, sources, latest state or vetting
outcome at a glance, nor to sort them — the page is a stack, not a table.

The **Audit log** is worse, and it is the subject of issue #221. It is a static
shadcn table whose columns are `Object.keys(rows[0])`, in raw ledger order, with
no sort, filter, or pagination and — the core complaint — **no colour**. A
`vetting-completed` row whose detail says `outcome=BLOCKED` is drawn identically
to a clean one: the same verdict that paints a marketplace card red is invisible
in the ledger. The marketplace column is plain text, so an operator who sees an
event against `ri-2` cannot click through to that marketplace's details.

This change is the **frontend half of #221** (tracked under the umbrella issue
#224). The backend items #221 also names — the lossy free-text verdict `detail`,
and the `human` actor-type stamped on automated `vetting` principals — stay in
#221 and are **not** touched here; this change only makes the existing ledger
legible and navigable.

## What Changes

- **Marketplaces page → a sortable table.** One compact row per marketplace —
  name (a link to its detail page), source (forge or host + clone URL), the
  latest snapshot's state and vetting outcome, when upstream last moved, and a
  snapshot count. A row expands in place to reveal that marketplace's snapshot
  review sub-table with its existing Ingest / Approve / Reject / Provenance
  actions unchanged. Built on `@tanstack/react-table` (added to the portal) over
  the existing shadcn table primitives.
- **Marketplace detail gains an audit slice.** The `/marketplaces/:name` page
  now lists the ledger entries recorded against that marketplace, with the same
  verdict colouring, so "what has happened to this marketplace" is answerable
  without scanning the whole ledger.
- **Audit log → legible and navigable.** Every row carries a status derived from
  its `event` and, for a `vetting-completed` row, the `outcome=` in its detail:
  a blocked verdict reads red (the portal's `destructive` token, the same red the
  marketplace card uses), a clear one reads in the purple accent, a warn one
  muted. The marketplace column links to `/marketplaces/:name`. The table sorts
  and filters per column (event, principal, marketplace, sha) and paginates.
- **Duplicate-URL warning on Register.** Registering an upstream whose clone URL
  already matches an existing marketplace now warns before submit and holds
  Register shut until the operator ticks "register anyway". It is a warning, not
  a block: the same URL under two names is a legitimate test setup, so the server
  still accepts it — the portal only makes the collision deliberate.

## Capabilities

### New Capabilities

_None._ The behaviour belongs to capabilities that already exist. This is a
presentation and navigation change over the portal's existing marketplace
administration (GW_0018), administrative audit logging (GW_0022) and portal audit
surface (GW_0030); it introduces no new requirement semantics, so no new GW
requirement or SVC is created. The new UI code carries `@Requirements`
annotations against those existing requirements, whose SVCs already pass.

### Modified Capabilities

- `admin-portal`: GW_0018's portal marketplace administration is re-presented as
  a sortable, expandable table with a per-row link to the marketplace detail page,
  and the register form gains a client-side duplicate-URL warning. No change to
  what the portal is allowed to do or to GW_0018's verification.
- `audit-export`: GW_0030's portal audit surface gains per-row verdict colouring,
  marketplace links, and per-column sort/filter/pagination over the same ledger
  the NDJSON export already serves. No change to the export contract or to
  GW_0030's verification.

## Impact

- **Portal (only)**: `src/main/frontend/`.
  - New: `src/lib/audit-status.ts` (derive a status from a ledger row),
    `src/components/audit-status.tsx` (the badge + row tint).
  - Changed: `src/pages/marketplaces.tsx` (cards → react-table, duplicate-URL
    warning), `src/pages/marketplace-detail.tsx` (marketplace audit slice),
    `src/pages/audit.tsx` (react-table ledger with colour/links/sort/filter/
    pagination), `src/lib/form-rules.ts` (`normalizeCloneUrl`), `package.json`
    (add `@tanstack/react-table`).
  - Tests: `marketplaces.test.tsx` (expand-then-act for the snapshot actions,
    now that they live in the expanded row; new duplicate-URL test),
    `audit.test.tsx` (a blocked vetting row is flagged and its marketplace links).
- **API / backend**: none. No endpoint, request or response shape changes; no
  `openapi.json` / `types.gen.ts` regeneration; the API-contract gate is not
  engaged.
- **Requirements**: none added or revised. `docs/reqstool/` is untouched; the new
  TypeScript carries `@Requirements` JSDoc tags against existing GW_0018 /
  GW_0022 / GW_0030.
- **Docs** (same PR): `docs/manual/reference/portal.md` — the marketplaces table
  and the audit-log colouring/links.
- **Trust boundary**: none crossed. This is UI craft over read surfaces; the
  approval gate, facade auth and registration allowlist are untouched, so the
  `old-coder` discipline is not triggered.
- **Declarative estate**: no new API-managed runtime state, so
  `skills-gateway.estate.*` needs no extension.
