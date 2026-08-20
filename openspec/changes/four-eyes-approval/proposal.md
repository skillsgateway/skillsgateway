# Proposal: four-eyes-approval

## Why

Nothing prevents the identity that brought content into the gateway from also
approving it: a marketplace's registrant, the person who triggered an
ingestion, or the author of the waivers that unblock a snapshot can be the
same principal that clicks Approve. For a control point whose whole purpose is
that published content passed independent review, that is a separation-of-
duties gap — flagged by internal review RB-REV-2026-08 (mandatory second
reviewer as a Phase-1 governance prerequisite) and tracked as issue
[#83](https://github.com/skillsgateway/skillsgateway/issues/83).

## What Changes

- Record the acting identity where it is currently only a ledger side effect:
  who registered a marketplace and who triggered each snapshot's ingestion
  become first-class columns.
- At approval time, detect a **four-eyes conflict**: the reviewer is the
  snapshot's ingestion actor, the marketplace's registrant, or the author of
  any waiver the approval would rely on.
- New configuration `skills-gateway.approval.four-eyes` with `warn` / `enforce`
  modes following the `vetting.revet.mode` precedent, **default `warn`** — a
  conflict is recorded on the audit ledger but the approval proceeds; in
  `enforce` mode the approval is refused fail-closed and the snapshot stays
  held. Off by an `enabled` flag is deliberately not offered; `warn` is the
  floor so conflicts are always at least visible.
- Portal: the approve dialog warns (warn mode) or explains the refusal
  (enforce mode); the API returns a structured 409 problem naming the
  conflicting role(s).
- Non-human sync actors (`scheduler`, `webhook`) never conflict.
- Requirements GW_0096 (conflict detection and enforcement) and GW_0097
  (mode configuration and ledger visibility) with SVC_GW_0096 / SVC_GW_0097.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `snapshot-approval`: approval gains a separation-of-duties rule — conflict
  detection (ingestion actor, registrant, waiver authors), a warn/enforce
  mode, fail-closed refusal in enforce mode, and ledger-recorded conflicts.

## Impact

- **DB**: new Flyway migration — `marketplaces.registered_by`,
  `snapshots.ingested_by` (nullable; pre-existing rows have no actor and never
  conflict).
- **Backend**: `ApprovalService` (conflict check before `decide`),
  `AdminController` (actor capture on register/ingest, 409 handler, ledger
  event `four-eyes-conflict`), `SyncService` (constant actors already exist),
  `SkillsGatewayProperties` (new `Approval.FourEyes` config),
  `SnapshotRepository` / marketplace persistence (new columns).
- **API**: snapshot/marketplace views expose the recorded actors; approval
  endpoint can return 409 with `conflicts` problem properties.
- **Portal**: approve dialog pre-check against `/api/me`, refusal/warning copy.
- **Docs** (same PR): `guides/approving-snapshots.md`,
  `reference/configuration.md`, `concepts/trust-boundaries.md`,
  `reference/api/marketplaces.md`.
- **Trust boundary**: touches `ApprovalService` → old-coder discipline,
  adversarial/negative tests required.
