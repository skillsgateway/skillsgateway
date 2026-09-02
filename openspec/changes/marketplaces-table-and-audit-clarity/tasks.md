# Tasks: marketplaces-table-and-audit-clarity

This is a portal presentation change over existing capabilities; it adds no
requirements and crosses no trust boundary, so the `old-coder` discipline is not
engaged. The new code carries `@Requirements` annotations against the existing
GW_0018 / GW_0022 / GW_0030, whose SVCs already pass.

## 1. Dependency

- [x] 1.1 Add `@tanstack/react-table@8.21.3` to `src/main/frontend/package.json`
  and install (lockfile updated).

## 2. Shared status derivation (covers GW_0030)

- [x] 2.1 `src/lib/audit-status.ts` — derive `clear | warn | blocked | neutral`
  from a ledger row's `event` and, for `vetting-completed`, its `outcome=` detail.
  `@Requirements GW_0030`.
- [x] 2.2 `src/components/audit-status.tsx` — `AuditStatusBadge` and
  `auditRowClass`, in the theme's existing verdict language (no new colour token).
  `@Requirements GW_0030`.

## 3. Marketplaces page (covers GW_0018)

- [x] 3.1 Replace the per-marketplace cards with a `@tanstack/react-table` table:
  name (link to detail), source, latest snapshot state + vetting outcome,
  upstream-updated, count; sortable.
- [x] 3.2 Row expands in place to the existing snapshot review sub-table with
  Ingest / Approve / Reject / Provenance unchanged.
- [x] 3.3 `@Requirements GW_0018` on the page.

## 4. Duplicate-URL warning (covers GW_0018)

- [x] 4.1 `normalizeCloneUrl` in `src/lib/form-rules.ts` (client-side aid only).
- [x] 4.2 Register dialog warns on a duplicate and gates Register behind an
  acknowledgement checkbox; still allows proceeding (server accepts it).

## 5. Marketplace detail (covers GW_0022, GW_0018)

- [x] 5.1 Add the marketplace's audit slice (ledger filtered to this marketplace)
  with the same verdict colouring. `@Requirements GW_0022, GW_0018`.

## 6. Audit page (covers GW_0022, GW_0030)

- [x] 6.1 Replace the static ledger table with a `@tanstack/react-table` table:
  per-row status colour, marketplace column linking to `/marketplaces/:name`,
  per-column sort/filter (event, principal, marketplace, sha), client pagination.
- [x] 6.2 Keep the NDJSON export and sinks sections unchanged.
  `@Requirements GW_0022, GW_0030`.

## 7. Tests

- [x] 7.1 `marketplaces.test.tsx` — expand-then-act for the snapshot actions;
  new duplicate-URL warning test.
- [x] 7.2 `audit.test.tsx` — a blocked `vetting-completed` row is flagged
  `blocked` and its marketplace links to `/marketplaces/:name`.
- [x] 7.3 Full portal unit suite green (`pnpm test`); `pnpm lint`; `tsc`.

## 8. Docs (same PR)

- [x] 8.1 `docs/manual/reference/portal.md` — the marketplaces table and the
  audit-log colouring/links.

## 9. Gates and evidence

- [ ] 9.1 Run the gate set after the last edit and record tails + commit SHA in
  `evidence.md` (note any gate not runnable in this environment and why).
