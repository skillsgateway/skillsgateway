# Tasks

## 1. Requirements (reqstool)

- [x] 1.1 Revise GW_AUDIT_0008 — The ledger browse read is bounded, typed and stably paged — (description, rationale; revision 0.2.0) for the marketplace filter and the ledger total, and extend SVC_GW_AUDIT_0008 (revision 0.2.0) with the filter and total clauses; verify `openspec validate --all --strict`

## 2. Server

- [x] 2.1 `V1__init.sql`: `idx_fetch_log_marketplace ON fetch_log (marketplace, id DESC)`; verify the schema tests stay green
- [x] 2.2 `FetchLogRepository`: filtered `entriesBefore` and a ledger total (exact below 100,000 or when never analysed, estimate otherwise), `@Requirements GW_AUDIT_0008`; verify with `AuditBrowseTests`
- [x] 2.3 `AuditController.browse`: optional `marketplace` parameter, `total` and `totalIsEstimate` on `AuditPage` with `@Schema` descriptions; verify with `AuditBrowseTests`
- [x] 2.4 `AuditBrowseTests`: the filtered page carries only that marketplace's entries and its cursor pages within the filter; the total is exact and matches a count on a small ledger (@SVCs SVC_GW_AUDIT_0008); verify the class passes
- [x] 2.5 Regenerate `openapi.json` and `types.gen.ts` (`OpenApiDocsTests` then `pnpm gen:api-types`); verify `OpenApiContractTests` passes and the contract gate reads the change as additive

## 3. Portal

- [x] 3.1 `queries.ts`: `useAuditPages({ marketplace? })` as `useInfiniteQuery` over `nextBefore`, and `useLedgerTotal()` reading a one-entry page; remove the single-page `useAudit`; verify `pnpm typecheck`
- [x] 3.2 Audit page: rows from every loaded page, a **Load older entries** control while `nextBefore` is set; verify with `audit.test.tsx`
- [x] 3.3 Overview: "N ledger entries" / "about N ledger entries" from `total`; verify with an overview test
- [x] 3.4 Marketplace Activity: the `marketplace` filter, newest first (no `reverse()`), Load older; verify with `marketplace-detail.test.tsx`
- [x] 3.5 MSW ledger mock answers newest first, honours `before`, `limit` and `marketplace`, and carries `total`; fix the stale comment in `audit.test.tsx`; verify `pnpm verify`

## 4. Docs

- [x] 4.1 `docs/manual/`: the audit API reference (the `marketplace` parameter, `total`, `totalIsEstimate`) and the portal pages for the audit log, the overview and a marketplace's Activity; verify `mkdocs build --strict`

## 5. Integration

- [x] 5.1 Run all gates fresh after the last code edit and record them in `evidence.md`; capture before/after screenshots (overview card, Activity order, Load older) to the local screenshots directory
