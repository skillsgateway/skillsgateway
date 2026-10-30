# Tasks: role-aware-portal-controls

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_AUTH_0064–GW_AUTH_0066 and SVC_GW_AUTH_0072–SVC_GW_AUTH_0077 in `docs/reqstool/`.

## 2. Role mirror and session refresh

- [x] 2.1 `src/api/access.ts`: `accessOf`, `useAccess`; `useIsAdmin` delegates (`@Requirements GW_AUTH_0064`). Unit-test each persona.
- [x] 2.2 `src/api/query-client.ts`: `createQueryClient` with 403 → invalidate `me`, no retry on 403; `main.tsx` uses it (`@Requirements GW_AUTH_0066`). Test: a revoked role's controls disappear after a refused call (`@SVCs SVC_GW_AUTH_0076`). Watch it fail first.

## 3. Controls (GW_AUTH_0064)

- [x] 3.1 Marketplace rights context in `MarketplaceLayout`; Ingest, decision row, retention, re-vet, fetchers, diff-tab file changes and delta line gated.
- [x] 3.2 Vetting report: waive, bulk selection, revoke waiver and re-run gated on the same context.
- [x] 3.3 Register marketplace for administrators only.
- [x] 3.4 Tests: no role and auditor (`SVC_GW_AUTH_0072`); approver of A on B and on A, admin (`SVC_GW_AUTH_0073`). The MSW default session becomes an administrator; no-role tests set theirs.

## 4. Gated pages (GW_AUTH_0065)

- [x] 4.1 `RoleRequired` + `RoleGate` components with stories (page, section, dark).
- [x] 4.2 Audit log, adoption, webhooks, audit sinks gated on auditor; marketplace Activity gated; `VettingRefusal` uses `RoleRequired` with corrected copy (`SVC_GW_AUTH_0077`).
- [x] 4.3 Overview ledger card only for a ledger reader.
- [x] 4.4 Sidebar leaves out Oversight and Integrations for a non-auditor, and Activity in a marketplace's sections (`SVC_GW_AUTH_0075`; `SVC_GW_AUTH_0048` re-pointed at an auditor, unchanged in what it asserts).
- [x] 4.5 Tests: no role on each gated page, auditor reads them (`SVC_GW_AUTH_0074`).

## 5. Copy

- [x] 5.1 No-role user menu wording (`GW_AUTH_0044`).

## 6. Documentation

- [x] 6.1 `docs/manual/` roles/portal pages: what each role sees. Verify with `mkdocs build --strict`.

## 7. Gates and evidence

- [ ] 7.1 Screenshots (local only), `/impeccable audit` + `harden` on the affected surfaces, every AGENTS.md gate fresh, `evidence.md`.
