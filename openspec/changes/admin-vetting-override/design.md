# Design: admin-vetting-override

## Context

Two deliberate stances are reversed narrowly (see ADR 0009). The relevant code:

- `VettingService.run` iterates the connectors (Spring beans) in order, runs each
  guarded, records a verdict, and aggregates fail-closed via
  `VettingChain.aggregate` (GW_0038: empty-is-blocked, any non-clearing blocks).
- `ApprovalService.doApprove` gates approval: `waiverService.evaluate` →
  `VettingBlockedException` if the effective outcome is blocked, then the policy
  gate, the release-age gate, and the four-eyes gate, all before the state
  transition and publication.

## Goals / Non-Goals

**Goals**

- An administrator can turn a built-in connector off/on, global or per
  marketplace, audited; a disabled connector is recorded, not hidden.
- An administrator can approve over a blocked outcome, with a reason, audited
  distinctly and marked on the snapshot.
- Both are admin-only; neither weakens the gate for anyone else, and neither can
  become a silent blanket approval.

**Non-Goals**

- No change to who may approve a *clean* snapshot (the role model is unchanged).
- The override does not bypass the policy, release-age or four-eyes gates.
- No portal work beyond what the backend must expose (portal is #224).
- Connector toggles are not yet folded into the declarative estate (see below).

## Decisions

1. **A disabled connector is a `DISABLED` verdict, not an omission.** Adding
   `DISABLED` to `VerdictState` (and `'disabled'` to the `vetting_verdict_state`
   enum) keeps the disablement in the run's evidence — fail-loud — instead of a
   silently shorter chain. `VerdictState.blocking()` is introduced: everything
   non-clearing blocks *except* `DISABLED`.
   *Alternative rejected:* skipping the connector entirely — a reviewer and an
   auditor would see a shorter chain with no explanation for the missing control.

2. **The aggregation keeps positive evidence mandatory.** `VettingChain.aggregate`
   now clears only if at least one verdict is clearing and none blocks; `DISABLED`
   is discounted from the block decision but does not count as clearing. So a run
   of nothing but `DISABLED` verdicts blocks — disabling every connector can
   never be a blanket approval by omission. This is the one change that could
   have loosened GW_0038, so it is covered exhaustively by a pure-function test.

3. **Toggle resolution is per-marketplace-then-global-then-enabled.**
   `ConnectorToggleService.enabled(connector, marketplaceId)` reads the
   per-marketplace row, else the global row, else true. `VettingService.run`
   consults it per connector using `snapshot.marketplaceId()`. Stored in
   `connector_toggles` with `UNIQUE NULLS NOT DISTINCT (connector,
   marketplace_id)` (null marketplace = global), upserted in place; the ledger,
   not the table, carries history.

4. **Toggles are admin-only to change *and* to read.** Both endpoints call
   `roleService.requireAdmin`. The switch that governs the chain, and even the
   visibility of its settings, must not be reachable by a marketplace-scoped
   approver — that would let the owner of content turn off the control governing
   it. An unknown connector name or marketplace is refused (422/404), never
   stored silently.

5. **The override lives in `ApprovalService`, lifts only vetting, requires a
   reason.** A new `ApprovalOverride(vettingFailure, reason)` is threaded into
   `approve`. When the effective outcome is blocked and an override is requested,
   the `VettingBlockedException` is not thrown; a blank reason instead raises
   `MissingOverrideReasonException` (422). The policy, release-age and four-eyes
   gates still run afterwards. The blocking connectors and uncovered findings are
   captured from the effective outcome before the transition, written to
   `snapshot_vetting_overrides` after publication succeeds, and returned to the
   controller.
   *Alternative rejected:* a blanket "force" flag with no reason and no distinct
   record — that is precisely the silent bypass the "no blanket override" stance
   protected against.

6. **Admin-only override is enforced at the controller, next to the existing
   approver check.** `AdminController.approve` reads an optional
   `ApproveRequest(overrideVetting, reason)`. With `overrideVetting`, it calls
   `roleService.requireAdmin` (stricter than the ordinary
   `requireApproverOfSnapshot`); without it, the endpoint behaves exactly as
   before (no body, or a body that does not request an override, still runs the
   approver gate). This matches where the release-age and four-eyes refusals are
   already handled, and keeps the override off the machine-API surface entirely.

7. **The override is its own ledger event and its own standing marker.**
   `snapshot-approved-over-vetting-failure` is written beside `snapshot-approved`
   (both facts kept), and `snapshot_vetting_overrides` holds one row per snapshot,
   surfaced as `override` on `GET /api/snapshots/{id}/vetting`. A disabled
   connector surfaces through the run's `disabled` verdict on the same view.
   *Alternative rejected:* reusing the waiver machinery — a waiver is a scoped,
   expiring, per-finding acceptance any reviewer may write; an override is a
   one-off, whole-outcome, admin-only act, and conflating them would make the
   override read as an ordinary accepted risk.

## Risks / Trade-offs

- **[Disabling a connector lowers assurance for a marketplace]** → the toggle is
  admin-only, audited on every change, and the disablement is recorded on every
  run; disabling everything still blocks, so it can never silently clear content.
- **[An override ships blocked content]** → admin-only, reason-required, its own
  loud ledger event with the failing verdicts, and a standing marker on the
  snapshot; the other three gates still apply.
- **[Identity/marketplace typos in a toggle]** → refused at the point of the
  toggle (422 unknown connector, 404 unknown marketplace) rather than matching
  nothing.

## Estate integration (deliberate, temporary API-only)

Connector toggles are API-managed runtime state, and CLAUDE.md's estate
obligation asks that such state extend `skills-gateway.estate.*` in the same PR
**or** state why it is deliberately API-only (as PATs are). For this change they
are **API-only**: folding them into the declarative estate means adding a sixth
field to the `Estate` record, which — because Spring `@ConfigurationProperties`
records bind through a single canonical constructor — forces every `new Estate(…)`
call site (the config default plus ~10 SVC tests) to change. That is orthogonal
churn against unrelated tests, and doing it in its own change keeps this
trust-boundary change reviewable. The reconciler pattern to add later is the
standard one (a `DeclaredConnectorToggle`, a `reconcileConnectorToggle` reading
the stored setting for idempotency, and the same audited `ConnectorToggleService`
path the API uses). Tracked as a follow-up.

## Migration Plan

Single `V1__init.sql` per repository convention: add `'disabled'` to the
`vetting_verdict_state` enum and two new tables (`connector_toggles`,
`snapshot_vetting_overrides`). Testcontainers recreate the schema every run; no
data migration. An empty `connector_toggles` table is exactly today's behaviour
(every connector runs), and an absent override row is a snapshot approved
normally.

## Open Questions (Decisions to confirm)

- **Toggle granularity**: global + per-marketplace is implemented. Per-repo or
  per-plugin scoping is deliberately not in scope.
- **Four-eyes on override**: should an override additionally require a *second*
  administrator (four-eyes for the override itself)? Currently one admin + reason.
- **Reason requiredness**: the reason is required (a reasonless override is 422).
  Confirm this is the intended strictness rather than a warn.
- **Event names**: `snapshot-approved-over-vetting-failure`,
  `connector-disabled`, `connector-enabled` — confirm these spellings before the
  ledger schema is depended on by SIEM consumers.
