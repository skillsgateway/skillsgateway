# Tasks: add-snapshot-retention

## 1. Requirements SSOT

- [x] 1.1 Add GW_0031 (retention policy evaluation), GW_0032 (soft delete with restore window), GW_0033 (approved snapshots never eligible), GW_0034 (hard-delete compaction including git storage), GW_0035 (retention actions are audit-logged), GW_0036 (portal delete/restore surface) to docs/reqstool/requirements.yml
- [x] 1.2 Add SVC_GW_0031–SVC_GW_0036 (automated-test) to docs/reqstool/software_verification_cases.yml

## 2. Persistence

- [x] 2.1 Fold `deleted_at`, `deleted_reason`, `purge_after` (+ a partial index on the purge queue) into `snapshots` in `V1__init.sql`
- [x] 2.2 `Snapshot` record: deletion marks with `@Schema`, plus a `deleted()` accessor
- [x] 2.3 `SnapshotRepository`: `softDelete`, `restore`, `purge`, `duePurge`, and the eligibility queries for held-too-long and superseded — every one carrying `state <> 'approved'` and the `min-idle` ledger veto

## 3. Retention service

- [x] 3.1 `skills-gateway.retention.*` on `SkillsGatewayProperties`: `enabled` (default false), intervals, batch size, `defaults` policy and `marketplaces.<name>` overrides resolved by `policyFor(name)`
- [x] 3.2 `RetentionService.candidates()` / `evaluate(actor)`: resolve the policy per marketplace, select by criteria, soft-delete with reason and `purge_after` — `@Requirements({"GW_0031","GW_0032","GW_0033"})`
- [x] 3.3 `RetentionService.softDelete/restore` for the manual path, refusing an approved snapshot — `@Requirements({"GW_0032","GW_0033"})`
- [x] 3.4 `RetentionService.compact(actor)`: delete the quarantine ref with JGit, clear a matching `refs/quarantine/incoming`, `Git.gc()` expiring as of now, once per affected marketplace, delete the row — `@Requirements({"GW_0034"})`
- [x] 3.5 Ledger entries for evaluation outcome, soft delete, restore and purge, with actor and reason — `@Requirements({"GW_0035"})`
- [x] 3.6 `WebhookEvent.SNAPSHOT_SOFT_DELETED` / `SNAPSHOT_RESTORED` in `ALL`, emitted through `WebhookService`
- [x] 3.7 `RetentionScheduler`: `@Scheduled` evaluation and compaction passes, both no-ops while disabled

## 4. API

- [x] 4.1 `RetentionController`: `DELETE /api/snapshots/{id}` (soft delete), `POST /api/snapshots/{id}/restore`, `GET /api/retention/candidates`, `POST /api/retention/evaluate`, `POST /api/retention/compact`
- [x] 4.2 `@Tag`/`@Operation`/`@ApiResponse` on every endpoint, `@Schema` on the DTOs, 409 on an approved snapshot and on restoring a snapshot that is not deleted

## 5. Portal (GW_0036)

- [x] 5.1 Regenerate `ui/src/api/types.gen.ts`; add queries (`useSoftDeleteSnapshot`, `useRestoreSnapshot`) and MSW handlers
- [x] 5.2 `ui/src/pages/marketplace-detail.tsx`: deleted badge with the restore deadline, delete and restore controls — JSDoc `@Requirements GW_0036`

## 6. Tests

- [x] 6.1 Criteria select the held-too-long and superseded snapshots and nothing else — `@SVCs({"SVC_GW_0031"})`
- [x] 6.2 Soft delete marks and hides nothing else; restore inside the window clears the marks — `@SVCs({"SVC_GW_0032"})`
- [x] 6.3 An approved snapshot is never selected and the manual delete is refused; the published repository still serves it — `@SVCs({"SVC_GW_0033"})`
- [x] 6.4 Compaction after the window removes the row and the quarantine ref, and leaves a non-due soft-deleted snapshot alone — `@SVCs({"SVC_GW_0034"})`
- [x] 6.5 Ledger carries the retention decision, soft delete, restore and purge with actor — `@SVCs({"SVC_GW_0035"})`
- [x] 6.6 Vitest component test for the marketplace detail page; Playwright e2e `snapshot_soft_delete_and_restore_in_the_portal` — `@SVCs SVC_GW_0036`

## 7. Verification

- [x] 7.1 `./mvnw clean verify`
- [x] 7.2 `(cd ui && pnpm e2e)`
- [x] 7.3 `reqstool status local -p docs/reqstool` ends PASS
- [x] 7.4 `openspec validate --all --strict`
