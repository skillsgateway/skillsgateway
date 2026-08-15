# Tasks: add-vetting-waivers

## 1. Traceability (SSOT first)

- [x] 1.1 Revise GW_0041, GW_0042, GW_0043 in `docs/reqstool/requirements.yml` to the
      waiver-based gate (revision 0.2.0); add GW_0044–GW_0048.
- [x] 1.2 Revise SVC_GW_0041–SVC_GW_0043 to the new mechanism, keeping their verification
      intent; add SVC_GW_0044–SVC_GW_0048.

## 2. Schema

- [x] 2.1 `V1__init.sql`: add `vetting_waivers` (marketplace, rule id, scope kind/value,
      justification, approver, `expires_at NOT NULL`, revocation, `expired_recorded_at`) with
      the lookup index; drop `vetting_runs.override_by/at/reason`.

## 3. Domain and evaluation

- [x] 3.1 `Waiver` record and `WaiverScope` enum with the scope-matching rule
      (path-prefix on segment boundary, SHA equality, `Finding.location` → path).
- [x] 3.2 `WaiverEvaluation` — pure static `evaluate(run, waivers, sha, now) → Effect` with
      `Effect(outcome, suppressions, blockingFindings, blockingConnectors)`.
- [x] 3.3 `VettingChain.Outcome` gains `CLEAR_WITH_WAIVERS`; `aggregate` unchanged.
- [x] 3.4 `WaiverRepository` (create, list by marketplace, revoke, expiry sweep queries).
- [x] 3.5 `WaiverService`: creation validation (justification, future expiry, scope), ledger
      entries, and `evaluate(snapshotId)` used by the gate and the API.
- [x] 3.6 `WaiverExpirySweep` scheduled component (`waiver-expired` ledger entry, idempotent).

## 4. Gate and API

- [x] 4.1 `ApprovalService.approve` loses `overrideReason`; gate on the effective outcome.
- [x] 4.2 `VettingBlockedException` carries the uncovered findings; `AdminController` handler
      adds `uncoveredFindings` to the problem document; `ApproveRequest` removed; approve
      emits `waiver-applied` per suppression in force.
- [x] 4.3 `WaiverController`: `POST /api/snapshots/{id}/waivers`,
      `GET /api/marketplaces/{name}/waivers`, `DELETE /api/waivers/{id}`, fully annotated.
- [x] 4.4 `VettingController.VettingView` gains effective `outcome`, `recordedOutcome`,
      `waivers`, `suppressed`.
- [x] 4.5 `VettingService` / `VettingRepository`: drop the override write path.

## 5. Portal

- [x] 5.1 Regenerate `src/api/types.gen.ts` from the new `openapi.json`.
- [x] 5.2 `queries.ts`: drop `overrideReason`; add waiver create/list/revoke hooks.
- [x] 5.3 `vetting-report.tsx`: waived-finding badge, "Waive…" control per finding, active and
      expired waiver list with justification/approver/expiry, `clear with waivers` outcome badge.
- [x] 5.4 Waiver creation dialog (rule prefilled, scope choice defaulting to this snapshot,
      justification, expiry date) with accessible names on every control.
- [x] 5.5 `marketplaces.tsx` approve dialog: no reason field; approve disabled while blocked,
      with the uncovered findings shown.

## 6. Tests

- [x] 6.1 Adapt `VettingTests` SVC_GW_0041/0043 to the waiver mechanism without weakening them.
- [x] 6.2 `WaiverTests`: SVC_GW_0044 (mandatory fields), SVC_GW_0045 (scope mismatch, all four
      cases), SVC_GW_0046 (expired and revoked), SVC_GW_0048 (ledger lifecycle).
- [x] 6.3 Pure exhaustive test of `WaiverEvaluation` over verdict states × waiver presence.
- [x] 6.4 Portal unit tests and Playwright `SVC_GW_0042` (adapted) and `SVC_GW_0047` (new).

## 7. Documentation

- [x] 7.1 `concepts/vetting.md`: replace the override section with waivers; update the flowchart
      and the ledger section.
- [x] 7.2 New `guides/waiving-findings.md`; add to `mkdocs.yml` nav and cross-link.
- [x] 7.3 `reference/api/marketplaces.md`: approve body removed, 409 payload, waiver endpoints.
- [x] 7.4 `reference/portal.md`: the waiver surface.
- [x] 7.5 `reference/configuration.md`: the sweep interval property.
- [x] 7.6 `guides/approving-snapshots.md`: the override curl becomes the waiver flow.

## 8. Gates

- [x] 8.1 `./mvnw clean verify`
- [x] 8.2 `(cd src/main/frontend && pnpm e2e)`
- [x] 8.3 `reqstool status local -p docs/reqstool` → PASS
- [x] 8.4 `openspec validate --all --strict`
- [x] 8.5 `mkdocs build --strict`
- [x] 8.6 Record all five in `evidence.md`.
