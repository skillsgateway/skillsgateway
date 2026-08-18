# Minimum release age

GitHub issue #98. A cooling-off window between a commit landing in quarantine
and the moment it becomes approvable.

## Why

A snapshot can be approved the instant it lands. That leaves no window for the
world — or for upstream's own community — to notice a compromised release
before we adopt it, which is exactly the push-then-quickly-revert attack
ARCHITECTURE §5 names when it conditions any future auto-promotion on "a
cooling-off window". This change is that window, applied first to manual
approval, where the gate already lives.

The control is only worth having if its clock is out of the attacker's reach.
Committer dates are attacker-controlled, so the age is measured from the
instant **the gateway** first ingested the commit — never from the commit
itself.

## What Changes

- A global `skills-gateway.vetting.minimum-release-age` (duration, default
  `0` = off, so an upgrade changes nothing).
- The approval gate refuses a snapshot whose commit the gateway first ingested
  less than that long ago: **409** naming the setting, the snapshot's current
  age and the time remaining, in the same shape as the existing
  `uncoveredFindings` refusal.
- **Clock:** the snapshot's first-ingestion instant. Re-ingesting the same
  commit returns the existing snapshot row untouched, so the clock cannot be
  reset by pushing again — verified, not assumed.
- Evaluated **at the instant of the approve request**, like waiver expiry: no
  scheduler, no stored eligibility, self-clearing when the age is met.
- Deliberately **not** a vetting connector: verdicts are point-in-time
  evidence, and a "too young" FAIL would keep blocking after the age had
  passed until the next re-vet happened to run.
- **No exemptions and no per-approval override in v1**, including for the
  first snapshot of a newly registered marketplace: a "first ever" exemption is
  a special case an attacker can reason about. Break-glass is a configuration
  change, which is itself deployed and reviewable.
- `GET /api/snapshots/{id}/release-age` reports eligibility and the time
  remaining, so the portal can disable the approve control and say *when* it
  will open rather than only that it is shut.
- Ledger: every allowed approval records the snapshot's age at approval; every
  refusal for age is its own ledger entry.

## Capabilities

### Modified Capabilities

- `snapshot-approval`: the gate gains an age precondition (GW_0073).

## Impact

- **Backend**: `SkillsGatewayProperties.Vetting.minimumReleaseAge`; new
  `approval/ReleaseAgeGate` and `approval/SnapshotTooYoungException`;
  `ApprovalService.approve` gains the precondition and returns the age it
  allowed; `AdminController` gains the problem-detail handler, the eligibility
  endpoint, and the age on the `snapshot-approved` ledger entry.
- **No schema change**: `snapshots.created_at` already is the first-sighting
  instant, and re-ingestion never rewrites it.
- **API**: one new read endpoint; OpenAPI snapshot and generated TS types
  regenerate.
- **Portal**: the approve control is disabled with "eligible in 2d 4h" on the
  snapshot row and in the approve dialog.
- **Docs**: `reference/configuration.md`, `guides/approving-snapshots.md`,
  `concepts/vetting.md` cross-reference, ARCHITECTURE §5 note.
- **Traceability**: GW_0073 + SVC_GW_0073.
- **Deferred**: per-marketplace and per-tier ages ride on #12 (CEL); an
  audited per-approval override is a separate change; auto-promotion (the
  eventual consumer of this window) stays out of scope.
