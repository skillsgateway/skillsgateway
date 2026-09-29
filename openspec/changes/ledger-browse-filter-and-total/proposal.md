# Proposal

## Why

The portal reads one page of the ledger (the newest 1,000 entries) and treats it as the whole
ledger (#540, finding 10 of the 2026-09-29 review). The overview prints that page's row count as
"recorded fetches", a quiet marketplace's Activity tab reads "Nothing recorded" once 1,000 newer
entries belong to others and renders what it does find oldest first, and the audit page has no way
to reach an older entry except the download. GW_AUDIT_0008 — The ledger browse read is bounded,
typed and stably paged — is what the server already does; the portal does not use it, and the read
lacks the two things the portal needs to stop guessing: a per-marketplace filter and a count.

## What Changes

- `GET /api/v1/audit` takes an optional `marketplace` query parameter that narrows the page to the
  entries recorded against that marketplace name. The cursor pages within the filter. Additive: a
  new optional parameter, the same response shape.
- The page response gains two additive fields: `total`, how many entries the ledger holds, and
  `totalIsEstimate`, whether that number is the planner's estimate rather than an exact count. The
  estimate is used only from 100,000 entries up; below that (and before the table has ever been
  analysed) the count is exact.
- A `fetch_log (marketplace, id DESC)` index in `V1__init.sql`, so the filtered page stays a bounded
  index read rather than a backwards scan of the ledger for a quiet marketplace.
- Portal, audit page: a **Load older entries** control that passes `nextBefore` back, so every entry
  is reachable in the page.
- Portal, overview: the ledger card reads **N ledger entries** (or **about N ledger entries** when the
  number is an estimate) from `total`, never "recorded fetches".
- Portal, marketplace Activity: asks the server with the `marketplace` filter, renders newest first
  (the `reverse()` goes), and can load older entries too.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `audit-export`: GW_AUDIT_0008 — The ledger browse read is bounded, typed and stably paged — gains
  the marketplace filter and the ledger total.

## Impact

- API: `GET /api/v1/audit` (additive parameter and fields); `openapi.json` and the generated
  portal types regenerate.
- Code: `AuditController.browse`, `FetchLogRepository` (filtered page, total), `V1__init.sql`
  (one index); portal `queries.ts`, `audit.tsx`, `overview.tsx`, `marketplace-detail.tsx`, the MSW
  ledger mock and the audit tests.
- Docs: the audit API reference and the portal's audit, overview and Activity pages in
  `docs/manual/`.
- No configuration leaf, Spring context, role or scheduled job is added.
