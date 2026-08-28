# Design: four-eyes-approval

## Context

`ApprovalService.approve(long snapshotId, String reviewer)` is the only
publisher; the reviewer is `authentication.getName()` passed by
`AdminController`. Today the approver is recorded (`snapshots.decided_by`),
but the identities on the *supply* side are ledger side effects only:

- Marketplace registration writes a `marketplace-registered` ledger row; the
  `marketplaces` table has no registrant column.
- Ingestion writes a `snapshot-ingested` ledger row (human) or uses constant
  actors `scheduler` / `webhook` (`SyncService`); `vetting_runs` records a
  trigger kind but no actor, and `snapshots` has no ingest actor.
- Waivers record their author (`vetting_waivers.approved_by`) — the one place
  authorship is already first-class.

So a rule "the approver must differ from the supplier" has nothing reliable to
compare against, and deriving actors from `fetch_log` would make an
append-only observability surface load-bearing for authorization.

## Goals / Non-Goals

**Goals**

- Detect, at approval time, that the reviewer is (a) the snapshot's ingestion
  actor, (b) the marketplace's registrant, or (c) the author of any waiver the
  approval relies on.
- `warn` mode (default): approval proceeds, conflict lands on the ledger.
  `enforce` mode: approval refused fail-closed, snapshot stays held and
  unpublished.
- Conflicts are always visible: there is no `off`.

**Non-Goals**

- No second-approval workflow (two recorded approvals per snapshot) — this
  change refuses conflicted approvals; it does not add a multi-step queue.
- No retroactive backfill: existing rows have `NULL` actors and never
  conflict.
- Rejections are not gated — refusing content is never the risky direction.
- No role-model changes: `RoleService` still decides *may this principal
  approve here*; four-eyes decides *may this particular principal approve this
  particular snapshot*.

## Decisions

1. **Record supply-side actors as columns, not ledger lookups.**
   `marketplaces.registered_by TEXT` and `snapshots.ingested_by TEXT`
   (both nullable), new Flyway migration `V2__four_eyes_actors.sql`.
   *Alternative rejected:* querying `fetch_log` — the ledger is append-only
   evidence, not an authorization source, and event/detail shapes may evolve.

2. **Conflict check lives in `ApprovalService.doApprove`, before
   `snapshotRepository.decide(...)`.** Server-side truth; the portal
   pre-check is convenience only. The check runs after waiver evaluation so
   the exact set of applied waivers (and their authors) is known.
   *Alternative rejected:* a check in `AdminController` — every future caller
   of `ApprovalService` must inherit the rule (it is the only publisher).

3. **Config mirrors the `revet` precedent:**
   `record FourEyes(FourEyesMode mode)` under a new
   `record Approval(FourEyes fourEyes)` in `SkillsGatewayProperties`,
   `enum FourEyesMode { WARN, ENFORCE }`, default `WARN`, convenience
   `enforcing()`. Property: `skills-gateway.approval.four-eyes.mode`.
   *Alternative rejected:* an `enabled` flag with default `false` — an
   invisible control is what the internal security review criticizes; `warn`
   keeps existing single-admin deployments working while making every conflict
   auditable.

4. **Refusal is a domain exception → RFC 9457 problem, HTTP 409.**
   New `FourEyesConflictException` carrying `List<Conflict>` records
   (`role`: `ingested-by` | `registered-by` | `waiver-author`, plus the
   identity and, for waivers, the waiver id). Handler in `AdminController`
   next to `vettingBlocked` (also 409): title
   "Four-eyes rule refused this approval", extra property `conflicts`.
   409 (state conflict), not 403: the principal is *authorized* to approve in
   this marketplace; this snapshot's provenance is what refuses them.

5. **Warn-mode conflicts surface as a ledger event, recorded by the
   controller.** `doApprove` returns detected conflicts inside `Approved`
   (extending the existing record); `AdminController` writes
   `auditLogger.record(principal, marketplace, "four-eyes-conflict", sha,
   detail)` alongside the existing `snapshot-approved` row. This keeps
   ledger writes in the caller, as today.

6. **Non-human actors never conflict.** `SyncService.SCHEDULER_ACTOR` and
   `WEBHOOK_ACTOR` are written into `snapshots.ingested_by` (attribution
   stays honest) but are excluded from comparison by constant set, so
   scheduled/webhook-ingested snapshots are approvable by anyone with the
   role. `NULL` actors never conflict.

7. **Portal**: snapshot view gains `ingestedBy`; marketplace view gains
   `registeredBy`. `ApproveDialog` compares against `/api/me`'s `username`:
   enforce mode disables Approve with explanatory copy (same pattern as the
   existing `blocked` flag); warn mode shows a non-blocking warning line.
   Server 409 falls back to `toast.error` regardless.

## Risks / Trade-offs

- [Single-admin deployments locked out under `enforce`] → default is `warn`;
  docs state plainly that `enforce` requires at least two principals with
  approval rights per marketplace.
- [Rule bypass via waivers] → waiver authors are part of the conflict set
  precisely because a self-authored waiver + approval is the trivial bypass.
- [Identity string equality is the whole comparison] → same OIDC subject with
  different casing/format would evade it; identities all originate from
  `authentication.getName()` on the same IdP, so formats are uniform.
  Documented as an assumption.
- [Ledger-only history for pre-existing rows] → accepted; NULL never
  conflicts, so the rule tightens only from deployment forward.

## Migration Plan

Flyway `V2__four_eyes_actors.sql` adds two nullable columns — additive, no
backfill, instant on PostgreSQL. Rollback = revert the deploy; columns are
ignored by the old code. No data migration.

## Open Questions

None — mode default (`warn`) and conflict set (ingest actor, registrant,
waiver authors) were settled in the issue discussion.

## Implementation notes — where the design met the code

Recorded during implementation; the design above is otherwise unchanged.

1. **No `V2` migration.** The repository's convention (`.claude/skills/code-conventions`,
   and commit `43cc722` "chore(db): single V1 migration") is a single
   `V1__init.sql` until the owner says otherwise, with schema changes folded
   into it — Testcontainers recreate the schema every run. Both columns were
   added to `V1__init.sql` instead of `V2__four_eyes_actors.sql`. The migration
   plan is otherwise as described: two nullable columns, no backfill.

2. **The rule lives in `FourEyesGate`, called from `ApprovalService.doApprove`.**
   Decision 2 stands — the check runs inside the only publisher, after waiver
   evaluation, before `decide(...)` — but the rule itself was extracted into a
   component beside `ReleaseAgeGate` rather than written inline. It is pure over
   its inputs (snapshot, marketplace, applied waivers, reviewer), which is what
   lets the negative cases be exercised without arranging a publication.

3. **The portal asks the server, not `/api/me`.** Decision 7 had the browser
   compare identities itself. It cannot honestly do that for the waiver clause:
   which waivers an approval "relies on" is the set `WaiverEvaluation` actually
   suppresses findings with, and reimplementing that in TypeScript would be a
   second rule that can disagree with the deciding one. Added
   `GET /api/snapshots/{id}/four-eyes` instead — mode, conflicts, and whether
   this caller would be refused — computed by the same gate the approval uses.
   The dialog's copy behaves exactly as designed on top of it.

4. **`IngestionService.ingest` takes the actor as a required argument.** Three
   call sites supply it: the admin endpoint (the principal), `SyncService` (the
   two constants), and `HostedPushHook` — a path the design did not enumerate.
   A push into a hosted marketplace *is* an act of supply, so the pushing
   principal is recorded as the ingestion actor and conflicts like any other.
   Making the parameter required rather than optional is deliberate: a future
   ingestion path cannot be added without deciding what identity it acts as.

5. **A waiver conflicts only when the approval leans on it.** The comparison is
   against the applied suppressions, not the marketplace's whole waiver set, and
   one conflict is reported per waiver rather than per suppressed finding.
   Comparing against every waiver would lock a reviewer out of unrelated
   snapshots for an expired acceptance they once wrote — a separation-of-duties
   rule that over-matches is its own failure mode.

6. **The e2e covers warn, not enforce.** The acceptance deployment is one
   gateway process with one mock-IdP identity, so `enforce` would make every
   other e2e approval impossible and there is no second identity to approve
   with. The real-browser test asserts the warn-mode notice in the approve
   dialog and that the approval still goes through — which is the default
   behaviour and the one a single-administrator deployment meets. The enforced
   refusal is verified over HTTP (MockMvc) against a gateway configured to
   `enforce`, in `FourEyesEnforceTests`.
