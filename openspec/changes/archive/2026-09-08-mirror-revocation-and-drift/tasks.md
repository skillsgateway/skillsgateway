# Tasks: mirror-revocation-and-drift

## 1. Requirements (SSOT first)

- [ ] 1.1 Add GW_0190 (bounded mirror staleness), GW_0191 (mirror divergence is
      observable without being asked), GW_0192 (an administrator can reconcile
      the mirror on demand) and GW_0193 (a mirror repair is never silent) to
      `docs/reqstool/requirements.yml`
- [ ] 1.2 Add SVC_GW_0190, SVC_GW_0191, SVC_GW_0192 and SVC_GW_0193
      (GIVEN/WHEN/THEN) to `docs/reqstool/software_verification_cases.yml`

## 2. Configuration

- [ ] 2.1 `SkillsGatewayProperties.Mirror` gains `sweepEnabled` (default true),
      `sweepInterval` (default 15m) and `sweepInitialDelay` (default 1m), with
      the positional construction in the outer record updated (GW_0190)
- [ ] 2.2 `MirrorTarget` carries `sweepEnabled` (GW_0190)

## 3. The reconciliation itself

- [ ] 3.1 `ForgeMirrorService.Reconciliation` — what one reconciliation changed:
      the served tip, the references pushed, the references deleted (GW_0193)
- [ ] 3.2 `reconcileWithRetries` records `mirror-updated` for a
      publication-triggered change and `mirror-drift-repaired` for a
      sweep- or administrator-triggered one, names the references in the detail
      (capped), and records nothing when nothing changed (GW_0193)
- [ ] 3.3 `ForgeMirrorService` keeps drift statistics — stale, missing,
      reachable, last success — updated by every reconciliation and by
      `report()` (GW_0191)
- [ ] 3.4 `ForgeMirrorService.reconcileNow(reason, limit)`: enqueue, wait
      bounded, return the fresh report (GW_0192)
- [ ] 3.5 `requireCredible`: refuse the whole reconciliation — no push, no
      deletion — when published storage answers with no served tip while the
      database still holds live approved snapshots for that marketplace;
      `mirror-reconciliation-refused` on the ledger and `refused` on the report,
      not retried (GW_0190)

## 4. The sweep

- [ ] 4.1 `MirrorReconciliationSweep` — `@Scheduled` on the configured interval,
      a no-op while the mirror or the sweep is disabled, throwing nothing
      (GW_0190)

## 5. Telemetry

- [ ] 5.1 `MirrorMetrics implements MeterBinder` in `observability`, following
      `ObjectStoreMetrics`: `stale_refs`, `missing_refs`, `reachable`,
      `seconds_since_success`, `reconciliations{outcome}` — no marketplace, SHA
      or principal tag anywhere (GW_0191)
- [ ] 5.2 Registered as a bean from `MirrorConfiguration`

## 6. The endpoint

- [ ] 6.1 `MirrorController` — `POST /api/mirror/reconcile`,
      `roleService.requireAdmin`, `@Tag`/`@Operation`/`@ApiResponse` (GW_0192)
- [ ] 6.2 Classify the route on `MachineApiRegistry`'s unreachable list, and add
      it to `RoleEnforcementTests`' privileged walk
- [ ] 6.3 Regenerate `src/main/frontend/openapi.json` and `src/api/types.gen.ts`

## 7. Tests

- [ ] 7.1 `ForgeMirrorSweepTests` (`@SVCs({"SVC_GW_0190", "SVC_GW_0191",
      "SVC_GW_0192", "SVC_GW_0193"})`): against a real bare repository over
      `file://` — the mirror made unreachable *before* the revocation so the
      revocation's own push exhausts its retries, the forge restored, and only
      the sweep allowed to run; **and** the published repository left answering
      short while the database still says approved, to prove the sweep refuses
      rather than empties the mirror; then the no-change case writing nothing, a
      sweep against a still-unreachable forge, and the on-demand endpoint
- [ ] 7.2 `ForgeMirrorDisabledTests` gains the sweep and the endpoint against a
      gateway with no mirror configured (`SVC_GW_0190`, `SVC_GW_0192`)
- [ ] 7.3 `ForgeMirrorTests` and `ForgeMirrorFailureTests` set
      `skills-gateway.mirror.sweep-enabled=false`, with the reason written where
      they set it — they assert on drift they seed themselves, and a background
      actor repairing it mid-assertion would race. No assertion changes.
- [ ] 7.4 `MachineApiRegistryTests` / `RoleEnforcementTests` cover the new route

## 8. Documentation (same PR)

- [ ] 8.1 `docs/manual/guides/read-only-forge-mirror.md` — the sweep, what an
      operator does about drift, and what the metrics mean
- [ ] 8.2 `docs/manual/reference/configuration.md` — the new `mirror.sweep-*`
      keys
- [ ] 8.3 `docs/manual/reference/api/mirror.md` — the reconcile endpoint
- [ ] 8.4 `docs/manual/reference/observability.md` — the mirror metrics table

## 9. Gates and evidence

- [ ] 9.1 One fresh run of every gate after the last code edit, pasted into
      `openspec/changes/mirror-revocation-and-drift/evidence.md` with the commit
      SHA
- [ ] 9.2 Archive the change as the final commit of the PR
