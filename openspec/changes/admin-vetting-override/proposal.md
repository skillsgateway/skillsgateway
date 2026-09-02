# Proposal: admin-vetting-override

## Why

The vetting chain is automation, and the codebase deliberately gave it no
human-in-command escape hatch: `SkillsGatewayProperties.Vetting` states there is
"deliberately no enable/disable switch", and `ApprovalService.approve` states
there is "no blanket override" — the only way past a block is a scoped, expiring
waiver per finding. Issue
[#223](https://github.com/skillsgateway/skillsgateway/issues/223) asks for the
airline-cockpit model: the captain can disconnect the autopilot **deliberately
and audibly**. An administrator must be able to override the vetting automation,
but every override is admin-only and duly noted in the audit ledger. The admin
role model was hardened to unconditional enforcement in
[#210](https://github.com/skillsgateway/skillsgateway/issues/210), which is what
makes "admin-only" load-bearing here. This reverses two deliberate design
decisions, so it ships with an ADR
([0010](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0010-admin-override-of-vetting-automation.md)).

## What Changes

- **Connector enable/disable (GW_0149).** An administrator can disable or
  re-enable a built-in connector (secret-scan, prompt-injection, license-scan),
  globally or per marketplace, via `PUT /api/vetting/connectors/{name}/toggle`
  (admin-only), with the settings readable at `GET
  /api/vetting/connector-toggles` (admin-only). A disabled connector is not run
  at ingestion or re-vetting; the chain records a distinct `disabled` verdict in
  its place. The fail-closed aggregation (GW_0038) is extended so a `disabled`
  verdict neither clears nor blocks — but a run still needs at least one clearing
  verdict to clear, so disabling every connector leaves a run **blocked**. Every
  toggle is an audited, admin-only action (`connector-disabled` /
  `connector-enabled`).
- **Override of a blocked outcome (GW_0148).** An administrator — and only an
  administrator — can approve a held or revoked snapshot whose effective outcome
  is blocked by setting `overrideVetting` with a reason on the approve endpoint.
  The override lifts only the vetting gate; the policy, release-age and
  four-eyes gates still run. It is refused without the admin role and without a
  reason, writes a distinct `snapshot-approved-over-vetting-failure` ledger event
  naming the administrator, the reason and the blocking verdicts, and records a
  standing marker (`snapshot_vetting_overrides`) surfaced on the vetting read
  surface so it is never indistinguishable from a clean approval.
- Requirements GW_0148 (override) and GW_0149 (connector toggle), with
  SVC_GW_0148 / SVC_GW_0149.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `snapshot-approval`: approval gains an administrative override of a blocked
  vetting outcome — admin-only, reason-required, distinctly audited, fail-loud,
  and lifting only the vetting gate.
- `snapshot-vetting`: the chain gains an administrative connector enable/disable,
  globally or per marketplace; a disabled connector becomes a recorded `disabled`
  verdict, and the fail-closed aggregation is extended to keep positive clearing
  evidence mandatory.

## Impact

- **DB**: single `V1__init.sql` — add `disabled` to the `vetting_verdict_state`
  enum, a `connector_toggles` table, and a `snapshot_vetting_overrides` table.
- **Backend**: `VerdictState` (+`DISABLED`, `blocking()`), `VettingChain`
  (aggregation), `Verdict` (`disabled(...)`), new `ConnectorToggle*`
  (record/repository/service/controller), `VettingService` (consults toggles),
  `ApprovalService` (+`ApprovalOverride`, override path), new
  `VettingOverrideRecord`/`Repository` + `MissingOverrideReasonException`,
  `AdminController` (approve body, override ledger event, 422 handler),
  `VettingController` (surface the override).
- **API**: `PUT /api/vetting/connectors/{name}/toggle`, `GET
  /api/vetting/connector-toggles`; the approve endpoint gains an optional body;
  the vetting view gains an `override` field.
- **Trust boundary**: touches `ApprovalService` and the vetting chain →
  old-coder discipline, adversarial/negative tests required.
- **Docs** (same PR): `reference/api/vetting.md`, `reference/api/snapshots.md`,
  `concepts/trust-boundaries.md`, `reference/portal.md`.
