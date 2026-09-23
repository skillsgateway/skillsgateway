# Proposal: corpus-aware-vetting

## Why

T5 — typosquatting and lookalikes — is the only row of the threat model in
`docs/manual/architecture.md` with no mitigation at all. Covering it needs a rule
that can ask a question of the **approved estate**, not only of the snapshot in
front of it, and the gateway has nowhere to ask one: plugin names exist only
inside git trees, and the only service that extracts them —
`SnapshotFactsService` — is called at approval time and discards its answer when
the call returns.

`CatalogService.mergePlugin` is not that rule. Its key is the
*marketplace-prefixed* name, so the same plugin name in two marketplaces never
collides; what it catches is the prefix ambiguity, and it runs after approval,
when the content is already published.

[ADR 0015 — Corpus questions are approval-gate preconditions, not vetting
connectors](../../../docs/decisions/0015-corpus-questions-are-approval-gate-preconditions.md)
decides where such a question may be asked, and the owner's decisions of
2026-09-23 recorded there settle the rest: checked at the approval request and
never in the chain, waivable, full UTS #39 skeleton with exact matching, re-run
on restore, only plugin names new to the marketplace, never skill names, first
come wins. This change builds it. Issue
[#253](https://github.com/skillsgateway/skillsgateway/issues/253).

## What Changes

- **Snapshot facts become recorded state** — `GW_INGEST_0036 — Snapshot facts
  are recorded once, at ingestion`. What `SnapshotFactsService` builds from a
  pinned commit is written once per snapshot when it is ingested, beside an index
  of the plugin names its manifest declares and where. The one field that is not a
  function of the commit — the snapshot's state — is not stored and is supplied
  at read time. `PolicyGate` and the rule playground read the recorded facts
  instead of rebuilding them; what a policy rule sees does not change, so
  `GW_APPROVAL_0007 — Fail-closed CEL policy gate at approval` is untouched.
- **A name-collision precondition on the approval gate** — `GW_APPROVAL_0019 —
  Approval is refused on a normalised plugin-name collision with the approved
  estate`, with four parts:
  `GW_APPROVAL_0019.1 — Plugin names are compared by exact match of
  normalised keys`,
  `GW_APPROVAL_0019.2 — Only plugin names new to the marketplace are checked, and
  the incumbent is never touched`,
  `GW_APPROVAL_0019.3 — Concurrent approvals cannot both admit a colliding name`,
  and `GW_APPROVAL_0019.4 — Restoring a revoked snapshot re-runs the check`.
- **Acceptance is the existing waiver** — `GW_APPROVAL_0020 — A name-collision
  refusal is lifted only by a scoped, expiring waiver`. The waiver machinery is
  unchanged; the gate consults it. The administrative override of a blocked
  vetting outcome does not lift this refusal.
- **Visible before and after the decision** — `GW_APPROVAL_0021 — Name
  collisions are shown to the reviewer and recorded on the ledger`: a read
  endpoint the approve dialog uses, the refusal on the ledger, and an accepted
  collision recorded as the waiver use it is.
- **The chain's purity becomes a stated invariant** — `GW_VETTING_0039 — Corpus
  state is never an input to a vetting chain run`, so that a later change handing
  a vetter an estate query is caught rather than reviewed as an improvement.
- **One configuration leaf**: `skills-gateway.approval.name-collision.enabled`,
  default `true`.

**No vetter's verdict changes, and `SnapshotUnderVetting` is untouched.**

### What the rule matches

A plugin name is normalised — NFKC, invisible format characters removed, the
UTS #39 confusable skeleton and case folding applied until stable, then `-`,
`_`, `.` and whitespace removed — once with the skeleton first and once with
folding first, and two names collide when they share a key **exactly**. `Claude-Skills`,
`claude_skills`, `claudeskills` and `cIaude-skills` reduce to one key. **No edit
distance.** Only plugin names the marketplace has not carried in an earlier
approved snapshot are checked, only against approved, non-deleted snapshots of
**other** marketplaces, and skill names never are.

## Capabilities

### New Capabilities

- `snapshot-facts`: what a snapshot's pinned commit says about itself, recorded
  once at ingestion as queryable state.

### Modified Capabilities

- `snapshot-approval`: the approval gate gains the name-collision precondition,
  its waiver acceptance, its reviewer-facing read, and its ledger entries.
- `snapshot-vetting`: the chain's inputs are fixed as (pinned content, chain
  identity) by a stated invariant.

## Why the surface grows (the stop rule)

- **One configuration leaf.** `docs/manual/capability-map.md` cannot absorb an
  on/off switch for a new refusal, and the switch is not optional for this
  repository's own test suite: every suite in the shared test context ingests the
  same fixture plugin (`hello`) into its own marketplace and approves it, in one
  shared database, so an enforcing gate there refuses the second approval of the
  whole run. The shared context therefore turns the gate off, which is what makes
  the leaf necessary rather than convenient. For an operator it is the answer to
  an estate of deliberate forks — the documented false-positive shape — that
  would rather have no control than one waived on sight. `ConfigSurfaceBudgetTests`
  rises by one.
- **One Spring test context.** The gate's enforcing posture — the default — cannot
  be exercised in the shared context for the reason above, so its suites share one
  context of their own. `ContextBudgetTests` rises by one.
- **No new estate object type, grantable role, backend package or scheduled
  sweep.** The facts are derived, the rule is code, and nothing sweeps: first
  come wins by construction.

## Impact

- **DB** (edit to `V1__init.sql`, pre-1.0): `snapshot_facts` and
  `snapshot_plugin_names`.
- **Backend**: `SnapshotFactsService` (state excluded, record/load),
  `SnapshotFactsRepository` (new), `NameNormalizer` and `NameCollisionGate` (new,
  `approval` package), `ApprovalService.doApprove` (one gate, and the transition
  moved inside a guarded transaction), `IngestionService` (records facts),
  `PolicyGate` and `PolicyController` (read recorded facts),
  `SkillsGatewayProperties.Approval` (one component), `AdminController` (read
  endpoint and 409 handler). A vendored `confusables.txt` (Unicode 18.0.0).
- **API**: additive. `GET /api/v1/snapshots/{id}/name-collisions`; the approve
  endpoint's 409 gains a problem shape carrying `collisions`.
- **Trust boundary**: crossed — `ApprovalService`. Old-coder discipline and
  adversarial tests, including the concurrent-approval race.
- **Frontend**: the approve dialog lists name collisions with the existing
  waiver form, snapshot scope only.
- **Docs** (same PR): `concepts/vetting.md`, `guides/approving-snapshots.md`,
  `guides/waiving-findings.md`, `reference/configuration.md`,
  `reference/api/snapshots.md`, `architecture.md` (T5 row).

## Deliberately not in this slice

- **A search index.** `snapshot_facts` is a gating input; the search half of
  #153 stays closed.
- **Edit distance**, for the reason ADR 0015 gives.
- **The registration-time near-miss warning** for marketplace names (ADR 0015's
  Option D) — out of scope by the owner's decision.
- **The `mergePlugin` shadowing primitive** ADR 0015 describes — its own change.
- **Anything corpus-aware inside the vetting chain.**
