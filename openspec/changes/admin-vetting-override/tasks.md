# Tasks: admin-vetting-override

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0148 (admin override of a blocked vetting outcome: admin-only,
      reason-required, distinct ledger event, fail-loud marker, lifts only the
      vetting gate) and GW_0149 (admin connector enable/disable, global or per
      marketplace; disabled verdict recorded non-blocking; positive evidence
      still required; audited, admin-only) to `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0148 and SVC_GW_0149 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Failing tests first (old-coder: prove they fail)

- [x] 2.1 `VettingChainDisabledTests` (pure function, `@SVCs({"SVC_GW_0149"})`):
      a disabled verdict does not block when something clears; disabling every
      connector blocks; a disabled verdict never rescues a failing one;
      pre-existing rules unchanged; `DISABLED` neither clears nor blocks
- [x] 2.2 `ConnectorToggleTests` (extends `AbstractGatewayTest`,
      `@SVCs({"SVC_GW_0149"})`): non-admin refused the toggle and the read; admin
      disables secret-scan for one marketplace; the toggle is on the ledger; the
      re-ingested snapshot records a disabled verdict and clears while a second
      marketplace still blocks; disabling every connector for a marketplace blocks
- [x] 2.3 `VettingOverrideTests` (extends `AbstractGatewayTest`,
      `@SVCs({"SVC_GW_0148"})`): a marketplace approver is refused the override
      (403) and a plain approval is refused for the block (409); an admin override
      with no reason is refused (422); an admin override with a reason approves,
      publishes, writes the distinct `snapshot-approved-over-vetting-failure`
      ledger event, and the vetting surface reports the override

## 3. Persistence

- [x] 3.1 `V1__init.sql`: add `'disabled'` to `vetting_verdict_state`; add
      `connector_toggles` (UNIQUE NULLS NOT DISTINCT connector, marketplace_id)
      and `snapshot_vetting_overrides` (one row per snapshot)
- [x] 3.2 `ConnectorToggleRepository` (upsert, find, findGlobal, list);
      `VettingOverrideRepository` (record upsert, findBySnapshot)

## 4. Vetting chain

- [x] 4.1 `VerdictState`: `DISABLED` + `blocking()`; `Verdict.disabled(...)`
- [x] 4.2 `VettingChain.aggregate`: discount `DISABLED`, still require a clearing
      verdict; `@Requirements({"GW_0038", "GW_0149"})`
- [x] 4.3 `ConnectorToggleService` (resolution rule, validated audited `set`,
      `list`); `VettingService.run` consults it per connector, records a disabled
      verdict when off; `@Requirements({"GW_0149"})`
- [x] 4.4 `ConnectorToggleController`: admin-only `PUT
      /api/vetting/connectors/{name}/toggle` and `GET
      /api/vetting/connector-toggles`

## 5. Approval override

- [x] 5.1 `ApprovalService.ApprovalOverride`; override path in `doApprove`
      (lifts only vetting, requires a reason, records the marker after publish);
      `MissingOverrideReasonException`; `@Requirements({"GW_0148"})`
- [x] 5.2 `AdminController.approve`: optional `ApproveRequest`; `requireAdmin`
      for an override; distinct `snapshot-approved-over-vetting-failure` ledger
      event; 422 handler
- [x] 5.3 `VettingController`: surface the override on the vetting view

## 6. Role-enforcement classification

- [x] 6.1 `RoleEnforcementTests`: classify `PUT
      /api/vetting/connectors/{name}/toggle` (admin mutation) and `GET
      /api/vetting/connector-toggles` (admin-only read)

## 7. Documentation (same PR)

- [x] 7.1 ADR 0009 + `reference/decisions.md` index entry
- [x] 7.2 `reference/api/vetting.md`, `reference/api/snapshots.md`,
      `concepts/trust-boundaries.md`, `reference/portal.md`

## 8. Gates and evidence (old-coder gauntlet)

- [x] 8.1 `openspec validate --all --strict`
- [~] 8.2 `./mvnw clean verify`, portal e2e, `reqstool status`,
      `mkdocs build --strict` — see `evidence.md`; the Spring integration suites
      are blocked locally by the Floci dev-service container and are deferred to
      CI, where they run
- [x] 8.3 `evidence.md`: what was run locally vs deferred, with the reason
