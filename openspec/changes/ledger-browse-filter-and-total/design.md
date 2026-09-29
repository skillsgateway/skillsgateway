# Design

## Context

See proposal.md — Why. The browse read (`GET /api/v1/audit`) already pages newest first by ledger
sequence; `fetch_log` is the table that grows with every client poll, so any addition to the read
has to stay bounded. The observability gauge already reads the planner's row estimate for the same
reason (`approximateDepth`), and that estimate is `-1` until the table's first analyse — which is
every fresh database, including the demo and every e2e run.

## Goals / Non-Goals

**Goals:** the portal stops presenting one page as the ledger; the new parts of the read cost no
more than the page itself on a large ledger.

**Non-Goals:** a count per marketplace or per filter; server-side filters other than the
marketplace; changing the audit page's client-side column filters, which still apply to the entries
loaded so far.

## Decisions

- **Total: estimate above 100,000, exact below.** `reltuples` alone reads 0 (or -1) on a small or
  never-analysed table while the page beside it shows rows — the same wrong picture mirrored. A
  blind `COUNT(*)` is the sequential scan of the one large table. Below 100,000 rows the exact
  count is cheap and is what the reader expects; above it, "about N" is honest. The threshold is a
  constant, not a configuration leaf. `totalIsEstimate` tells the portal which it got.
- **`total` is the whole ledger, filter or not.** A per-marketplace count is a scan over that
  marketplace's rows, which for a busy marketplace is the cost this read avoids; no surface needs
  it. The field's description says so.
- **Filter by name, not by marketplace id.** The Activity tab addresses a marketplace by name, as the
  ledger's `marketplace` column and the rest of the portal do. Entries of an earlier marketplace
  that held the same name appear under it, as they already did; the entries still carry
  `marketplaceId` (GW_AUDIT_0009 — Ledger entries identify the marketplace they concern by id) for a
  reader who needs to tell them apart.
- **An index on `(marketplace, id DESC)`.** Without it a filtered page for a quiet marketplace walks
  the primary key backwards across the whole ledger to fill its page, which is the unbounded read
  GW_AUDIT_0008 exists to prevent. Edited into `V1__init.sql` (pre-1.0: one migration).
- **Portal pages through `useInfiniteQuery` over `nextBefore`**, the shape the diff and tree reads
  already use. The audit page keeps its client-side sort, filters and pagination over the loaded
  entries and adds a **Load older entries** control below; Activity renders the pages in the order
  the server returns (newest first) and offers the same control. The overview asks for a one-entry
  page and reads only `total`.

## Risks / Trade-offs

- [The estimate can lag between autovacuum analyses] → it is used only where the table is large and
  labelled "about"; the exact path covers every small deployment.
- [Two parallel branches edit `V1__init.sql`] → an added index line; conflicts are trivial.
