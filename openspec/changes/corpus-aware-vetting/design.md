# Design: corpus-aware-vetting

## Context

ADR 0015 decides *where* a corpus question may be asked. This document decides
how to build the corpus and how the gate behaves, and it records the four places
where the obvious implementation is wrong.

Three existing properties constrain the shape:

- **A chain run is a pure function of (pinned content, chain identity).** Those
  are precisely the columns `vetting_runs` carries — `snapshot_id` and `chain` as
  `connector@version` in chain order — and nothing in this change may add a
  third. `GW_0198 — Corpus state is never an input to a vetting chain run` states
  it so a later change cannot reintroduce it quietly.
- **`ApprovalService` is a named trust boundary**, and its concurrency
  correctness lives in guarded single-statement updates, not transactions: ADR
  0013 counts twenty `UPDATE … RETURNING *` statements against three
  `@Transactional` annotations, zero `@Version` and zero `SELECT … FOR UPDATE`.
  A gate that needs atomicity with the state transition is a change of *kind*,
  not of degree.
- **Absence of evidence is not evidence of safety.** The chain already blocks a
  snapshot with no run at all. A corpus check over an incompletely-indexed estate
  has the same property and must behave the same way.

## Goals / Non-Goals

**Goals**

- The approved estate's plugin names are queryable without a fan-out of JGit
  walks.
- A snapshot that would introduce a normalised plugin-name collision cannot be
  approved, and the refusal is acceptable only through the existing waiver.
- The incumbent is never touched. This check can never withdraw served content.
- No change to `SnapshotUnderVetting`, to any connector, or to what a run
  records.

**Non-Goals**

- Not a search index. Not full-text search over content. Not a catalog table.
- Not edit-distance matching (see the proposal for what evidence would open it).
- Not a fix for the `mergePlugin` shadowing primitive, which is separate work.

## Decisions

### 1. The persisted facts exclude the snapshot's state

`SnapshotFactsService.build` puts `state` into the `snapshot` map. It is the one
field in the whole structure that is **not** a function of the pinned commit —
`held → approved → revoked → approved` all mutate it. Persisting it would freeze a
value that changes and hand `PolicyGate` a stale answer to the one question a
deny rule is most likely to ask.

So `snapshot_facts.facts` stores everything except `state`, and the read path
re-injects the current value from the `snapshots` row before handing the map to
CEL. The CEL variable namespace is byte-for-byte what it is today; only where the
value comes from changes.

*Alternative rejected:* store `state` and refresh the row on every transition.
That makes an immutable record mutable to carry one field, and every future
transition acquires an obligation to remember it.

### 2. A failure to build facts is recorded, and both readers fail closed

`SnapshotFactsService` throws `PolicyEvaluationException` rather than truncating
when a snapshot exceeds `MAX_FILES` (20 000) or a `SKILL.md` exceeds 256 KiB.
That is correct and stays. At ingestion the throw is caught and recorded as
`snapshot_facts.unavailable_reason` with a null `facts`, so the failure is a
durable fact rather than a log line.

Both readers then treat it as blocking:

- `PolicyGate` behaves exactly as it does today when the build throws — every
  enabled rule is recorded as a denial with `error: <reason>`. No behaviour
  change; it just reads the reason instead of re-deriving it.
- The collision gate refuses the approval. A snapshot whose plugin inventory
  could not be read cannot be shown not to collide.

### 3. Facts are built at ingestion, after closure resolution, before the chain

`SnapshotFactsService` reads `snapshot_closure_members` for each plugin's
`origin` / `upstreamUrl` / `resolvedSha`, so it must run after external-source
resolution has written the closure. It must run before `vettingService.vet`
because the chain is the long pole and a facts failure should be visible without
waiting for it. The chain does not read the facts and never will (`GW_0198`), so
the ordering is about diagnosis, not correctness.

### 4. Plugin names are compared in memory; no normalised key is stored

The tempting shape is a `normalized_key` column with an index, and a join. It is
the wrong shape here for one reason: **the key is derived from a vendored Unicode
confusables table**, and the moment that table is updated every stored key is
silently stale — a collision the gateway would now detect goes on not being
detected, with no signal. Fixing that means a `normalizer_version` column, a
recompute pass, and a startup check, all to avoid a scan.

The scan is not worth avoiding. The estate is thousands of plugin names, and the
gate runs once per approval, at human speed, on a path that already opens a git
repository and runs four connectors. So `snapshot_plugin_names` stores the plugin
name and its manifest location; the gate loads the approved estate's names,
normalises both sides in memory, and compares. Normalisation is then a pure
function applied at decision time and a table upgrade takes effect immediately.

*Alternative kept in reserve:* the stored key plus index, if measurement ever
shows the scan matters. It is an optimisation with a correctness obligation
attached, and it should be bought only when it is needed.

The vendored table follows the `skill-conformance` precedent —
`src/main/resources/vetting/agentskills-2026-08-04.json` — as a dated resource
file, not a runtime download.

### 5. The estate must be fully indexed, or the gate refuses

Facts are written at ingestion, so every snapshot approved before this change has
no row. A collision query over a partially-indexed estate silently misses the
incumbents it has not indexed, which is the failure mode a security control must
not have.

- An idempotent **backfill** runs once at startup as a bounded, logged
  `ApplicationRunner`, building facts for every approved, non-deleted snapshot
  that has no row.
- Until it completes, the collision gate refuses with a **distinct** reason
  naming how many approved snapshots are unindexed — not the collision reason,
  so an operator is never told a collision exists when the truth is that the
  corpus is incomplete.

*Alternative rejected:* lazy build-on-read. It indexes the snapshot being
approved, not the incumbents it must be compared against, so it produces exactly
the silent miss this decision exists to prevent.

### 6. The race between the check and the transition is closed by the existing repair path

The gate reads the estate, then `snapshotRepository.decide` transitions the row.
Two approvals of colliding snapshots issued within that window both pass.

The shape that fits this codebase: **re-run the collision query after `decide`
succeeds and before `storage.publish`.** If it now finds a collision, call
`snapshotRepository.undecide(before)` and refuse — which is precisely the
`ApprovalService.repair` path that already exists, is already tested, and already
handles a failed publication. Nothing published, nothing left half-decided.

Both racing approvals can lose this way, and neither is approved. That is a
fail-closed outcome of a rare event, and it is stated here rather than discovered
later.

*Alternatives, both real:*

- `pg_advisory_xact_lock(hashtext(key))` around the check and the transition.
  Cheapest correct answer, no schema change — but `doApprove` is not
  `@Transactional`, so it would need to become so, and that changes when every
  write in the method becomes visible at a trust boundary. Not a change to make
  as a side effect.
- `SELECT … FOR UPDATE` on the incumbent rows. The codebase has none, and ADR
  0013 records that as deliberate.

**This is the sharpest risk in the change.** It is where the adversarial tests
go, and if the repair-path approach does not survive them the advisory lock
should be proposed on its own merits rather than smuggled in.

### 7. The finding's location is the manifest entry, and the waiver widens

A waiver matches on rule id plus scope, and `PATH` scope is matched against the
path part of the finding's location. A collision has no line in any file; its
natural location is `.claude-plugin/marketplace.json`, the manifest entry that
declares the name.

Two consequences, both stated rather than hidden:

- `SNAPSHOT` scope — the portal's default — accepts every collision that commit
  has, including one against a plugin that enters the estate later. It is bounded
  by dying with the SHA, so the acceptance is re-made at the next ingestion.
- `PATH` scope on the manifest path is effectively marketplace-wide for this
  rule, and survives re-ingestion. The portal should not offer it for this rule
  id in the first slice.

A finer waiver key — one that names the *pair* rather than the rule — is the
principled answer and is deliberately not built before anyone has hit the case.

### 8. Default mode is `warn`, not `enforce`

`skills-gateway.approval.collision.mode` takes `off | warn | enforce`, default
**`warn`**: the refusal is computed, recorded on the ledger and shown to the
reviewer, but does not refuse the approval.

This follows `RevetMode` and the four-eyes gate, which both ship warn-first for
the same reason: the false-positive posture of this rule is **unmeasured**. A
control that blocks approvals on day one on the strength of an untested matcher
is how an operator learns to set it to `off`. Warn mode is also how the evidence
for `enforce` gets collected, and how the edit-distance question eventually gets
a number attached to it.

## Open questions

- **Whether a collision refusal should be waivable at all** (ADR 0015, decision
  to confirm 2). The release-age model — a refusal with no acceptance act — is
  stricter and simpler, and it means a legitimate fork can never be approved.
- **How aggressive the confusable fold should be.** UTS #39 skeleton maps
  `0`→`o` and also `rn`→`m`. The second is a real typosquat vector and a real
  false positive.
- **Whether `revoked → approved` re-runs the precondition.** It is a permitted
  transition and it re-admits content past a gate that ran when the estate looked
  different. Re-running is the honest answer, and it means a snapshot can become
  un-restorable through no act of its own.

## Risks

- **`SkillsGatewayProperties` gains a component.** That record has broken tests
  on other branches before, where a test constructs it positionally. Any
  concurrent branch touching it conflicts semantically, not textually.
- **`ApprovalService.doApprove` gains a gate.** Trust boundary; `old-coder`
  discipline and adversarial tests, not happy-path coverage.
- **`V2__snapshot_facts.sql` is the first migration after `V1__init.sql`.** Any
  concurrent branch adding a migration collides on the version number.
- **Declarative estate (#65).** The collision mode is configuration, not
  API-managed runtime state, so the `skills-gateway.estate.*` obligation does not
  apply — for the same reason ADR 0009 gave for external connectors. Waivers,
  which *are* API-managed, are unchanged by this proposal.
