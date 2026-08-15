## Why

The vetting chain (GW_0037–GW_0043) gates approval fail-closed, but its only
escape hatch is a **blanket override**: one free-text `overrideReason` that
clears an entire snapshot, covers every finding at once, is scoped to nothing,
and never expires. A reviewer who wants to accept one documented dummy key in
`fixtures/` has to accept the whole run — including the critical finding nobody
looked at — and the acceptance is permanent.

That is the wrong shape for an accepted risk. Real risk acceptance is narrow
("this rule, on this content"), attributed, justified, and **temporary**. This
change replaces the blanket override with scoped, expiring waivers, so that the
gate stays closed around everything a waiver does not name, and every acceptance
comes back for review when it expires.

## What Changes

- **New**: a **waiver** — an accepted-risk exception for one finding rule id, on
  one marketplace, scoped either to a single snapshot commit SHA or to a path
  within the marketplace, carrying a mandatory justification, the approving
  identity, and a mandatory expiry. Unlimited waivers cannot be expressed.
- **New**: an **effective vetting outcome**, computed at evaluation time from
  the recorded run plus the waivers active *at that instant*. A waived finding
  no longer contributes to the verdict it belongs to; a run whose blocking
  findings are all waived aggregates to `CLEAR_WITH_WAIVERS`, which is
  deliberately distinguishable from `CLEAR` so a reviewer or auditor never
  mistakes an accepted risk for a clean chain.
- **New**: waiver management REST surface — create a waiver from a finding on a
  snapshot, list a marketplace's waivers (active and expired), revoke one.
- **New**: waiver surfacing everywhere findings are already surfaced — the
  snapshot vetting response carries the effective outcome, the applicable
  waivers, and which findings they currently suppress; the portal review dialog
  badges waived findings, lists waivers with justification/approver/expiry, and
  offers "waive this finding" per finding.
- **New**: waiver lifecycle in the append-only ledger — creation, revocation,
  observed expiry, and each waiver *use* at the moment it lets an approval
  through.
- **New**: a cheap scheduled sweep that flags newly-expired waivers into the
  ledger once. Expiry itself needs no scheduler — it is evaluated at decision
  time — so the sweep is a ledger convenience, off by default in neither
  direction: it is unconditional but idempotent.
- **BREAKING**: `POST /api/snapshots/{id}/approve` no longer accepts an
  `overrideReason`, and the blanket override is removed. Approving a snapshot
  whose effective outcome is blocked now requires waivers covering **every**
  blocking finding; without them the request is refused with `409` naming the
  blocking connectors *and* the uncovered findings.
- **BREAKING**: `vetting_runs.override_by / override_at / override_reason` are
  dropped; their audit role is taken over by the waiver tables and the ledger,
  which record strictly more (what was accepted, on what scope, until when).

## Capabilities

### New Capabilities
- `vetting-waivers`: scoped, expiring accepted-risk exceptions against vetting
  findings — the waiver model and its scoping, the effective-outcome
  computation, expiry semantics, the management surface, the portal surface,
  and the ledger record (GW_0044–GW_0049).

### Modified Capabilities
- `snapshot-vetting`: GW_0041 (the approval gate) changes from "refuse unless
  the request carries an override reason" to "refuse unless every blocking
  finding is covered by an active waiver". The requirement's rationale is
  unchanged — accepting a blocked snapshot must be a deliberate, attributed,
  reviewable act — but the mechanism that satisfies it is replaced.

## Impact

- **Schema** (`V1__init.sql`): new `vetting_waivers` table; `vetting_runs` loses
  its three `override_*` columns; `vetting_runs.outcome` keeps its two-valued
  check, because the recorded outcome stays the raw, waiver-free evidence.
- **API**: `POST /api/snapshots/{id}/approve` (body removed, 409 payload
  extended); `GET /api/snapshots/{id}/vetting` (effective outcome, waivers,
  suppressions); new `POST /api/snapshots/{id}/waivers`,
  `GET /api/marketplaces/{name}/waivers`, `DELETE /api/waivers/{id}`.
- **Code**: `vetting` package gains `Waiver`, `WaiverScope`, `WaiverRepository`,
  `WaiverService`, `WaiverEvaluation` (pure), `WaiverController`,
  `WaiverExpirySweep`; `VettingChain.Outcome` gains `CLEAR_WITH_WAIVERS`;
  `ApprovalService` and `VettingBlockedException` lose the override path;
  `AdminController.ApproveRequest` is removed.
- **Portal**: `vetting-report.tsx` and the approve dialog in `marketplaces.tsx`;
  regenerated `types.gen.ts`.
- **Docs**: `concepts/vetting.md`, a new `guides/waiving-findings.md`,
  `reference/api/marketplaces.md`, `reference/portal.md`,
  `reference/configuration.md`.
- **Traceability**: new GW_0044–GW_0049 with SVC_GW_0044–SVC_GW_0049; GW_0041,
  SVC_GW_0041, SVC_GW_0042 and SVC_GW_0043 revised in place to the new
  mechanism, keeping their verification intent.
