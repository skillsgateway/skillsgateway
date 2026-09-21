# Proposal: bounded-typed-audit-read

## Why

PR E of the 2026-09-20 1.0.0 readiness run — finding **3**.

`GET /api/audit` was `SELECT * FROM fetch_log ORDER BY id` with no limit and no
cursor, returning `List<Map<String,Object>>` — rows keyed by database column name.
Three problems, and they are different in kind:

- **It loads the whole of `fetch_log`.** The change already in flight to bound that
  table's growth calls it "the table that ends the deployment". This read makes the
  surface that exists for investigating an incident the one that cannot be opened
  during one.
- **Every column name is a published API field.** `actor_type`, `token_id` and
  `credential_kind` reached clients as JSON keys, so renaming a column was a contract
  change.
- **Its own documentation was false.** "Portal actions are not in this ledger" while
  `AdminAuditLogger` writes `source = 'admin'` rows into exactly that table.

## What Changes

### The read becomes a bounded page of typed entries

`{entries, nextBefore}`, newest first, `before` and `limit`.

**`AuditEntry` already existed** — the NDJSON export has published a typed, annotated
row shape all along. The browse read reuses it rather than minting a second shape for
the same rows, which is also why this change adds no schema: the portal reads `ts`,
`event`, `principal`, `marketplace` and `sha`, all of which the typed record carries.

### It pages by sequence, newest first — the opposite of the export

Two decisions worth stating rather than discovering:

- **Newest first**, where the export ascends. An export consumer resumes forward from
  where it stopped; a person opening the audit page wants what just happened.
- **Cursored on `id`, not offset.** The ledger only grows at the newest end, so an
  offset page shifts under a reader between requests while `id < before` cannot.

### It moves to `AuditController`

Off the catch-all `AdminController` and next to `/audit/export` and the sinks. The path
is unchanged; `AuditController` already maps `/api/v1/audit` and already holds the
properties the page bound comes from, so the move also avoids giving `AdminController`
a dependency it had no other use for.

### The bound is the export's, not a new setting

`skills-gateway.audit-export.default-page-size` and `max-page-size` already bound reads
of this table. A second pair would be two settings that have to agree about one table.
**`ConfigSurfaceBudgetTests` does not move.**

## Capabilities

### Modified Capabilities

- `audit-export`: gains `GW_AUDIT_0008 — The ledger browse read is bounded, typed and
  stably paged`. The browse read had no requirement at all: `GW_AUDIT_0001` governs
  *recording*, `GW_AUDIT_0003` the streaming export, and `GW_AUDIT_0006` the portal's
  download control and sink listing. The read the portal's table is built on was
  unspecified, which is how it stayed unbounded.

## Impact

- **API — BREAKING.** A bare array becomes an envelope. Free at 1.0.0, and the same
  release already moves every path.
- **Backend**: one repository method, the endpoint moved and rewritten, `list()` kept
  for tests with its non-request-path status documented on it.
- **Frontend**: `useAudit` unwraps `entries`. The table's sorting, filtering and
  client-side paging are untouched — it now sorts one page instead of the table.
- **Docs**: `reference/api/audit.md` — the envelope, the parameters, and why the
  direction differs from the export.
- **Schema**: none.
- **Out of scope**: a "load older" control in the portal. The API can page; the UI
  shows the newest page, which is what it showed before. Adding the control is a
  portal change with its own design, and #448's file-explorer work is the better place
  to consider the audit page's navigation as a whole.
