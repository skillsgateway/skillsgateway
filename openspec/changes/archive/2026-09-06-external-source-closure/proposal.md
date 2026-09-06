# Proposal: external-source-closure

## Why

Issue [#17](https://github.com/skillsgateway/skillsgateway/issues/17) is staged by
ADR 0011. Increment 1 ([#244](https://github.com/skillsgateway/skillsgateway/pull/244))
shipped admission; increment 2 ([#257](https://github.com/skillsgateway/skillsgateway/pull/257))
shipped resolution of the `github` type into a deterministic composite commit,
with the address, redirect and resource policy that fetch needs. The reopening
comment on #17 fixes what remains and its order: **the closure domain object and
its migration** is next. This change is that increment.

Today the composite commit's provenance lives in exactly one place: its commit
message (`Upstream-Commit`, one `Resolved-Source` line per plugin,
`Transformer-Version`). That is enough for one reviewer reading one snapshot and
useless for the question the security review put first — *which approved
snapshots include repository X, at any commit?* — because git storage cannot be
queried across an estate. It also leaves `ApprovalService` publishing a composite
on the strength of the ingestion code having been correct: nothing at the
approval boundary checks that what the snapshot's provenance says was resolved is
what the pinned commit holds.

The security-review revision of #17 asked for two things here, and both are
blocking in its wording: the closure as a first-class, immutable domain object
persisted at ingestion, and a **closure-completeness assertion** in
`ApprovalService.doApprove` ahead of the existing gates, with a negative test.

## What Changes

- **Two requirements**. GW_0162 was the highest id on `main` and nothing under
  `openspec/changes/` reserved above it, so this change first claimed GW_0163 and
  GW_0164 — and then renumbered to **GW_0164 and GW_0165**, because
  [#283](https://github.com/skillsgateway/skillsgateway/pull/283) had claimed
  GW_0163 in a worktree neither branch could see and was opened first. Checking
  `main` and the in-flight changes is not sufficient when two unpushed branches
  allocate at once; the first PR opened keeps the id.
  - **GW_0164 — The resolved closure is recorded as an immutable domain object of
    the snapshot.** `snapshots.upstream_sha`, a `snapshot_closures` row per
    composite snapshot with a digest, and `snapshot_closure_members` rows that are
    value copies of what was declared and what it resolved to, written in the
    snapshot's transaction. The closure surfaces in provenance, in the policy
    gate's facts, and through a blast-radius query.
  - **GW_0165 — Approval requires a complete closure.** `doApprove` refuses, with a
    dedicated `ClosureIncompleteException` and a ledger entry, any snapshot whose
    served manifest, recorded closure and pinned tree do not agree, on every
    approval path.
- **Schema.** Folded into `V1__init.sql`, which is this project's standing
  convention (`code-conventions`: single V1 until the owner says otherwise). The
  "migration" for a snapshot that predates this change is the column semantics:
  `upstream_sha` is the snapshot's own commit and the absence of a closure row is
  the empty closure. See `design.md`.
- **API, additive.** `Provenance` gains `sha` (the served commit) and `closure`;
  its existing `upstreamSha` now carries the *upstream* commit for a composite
  snapshot, which is what its schema description always said. `Snapshot` gains
  `upstreamSha`. The approve endpoint gains a 409 outcome. OpenAPI regenerates.
- **Portal, minimal.** The provenance dialog lists the served commit and the
  closure members.
- **Not in this change:** SSRF hardening beyond what #257 shipped (the egress
  proxy, connect-time address pinning) — that is #17's third item and stays
  separate; closure-aware re-vetting wired into `RevetService` — the query it
  needs exists after this change, the wiring is the fast-follow the ADR's
  increment table names; `git` and `git-subdir`; declared `ref`/`sha` pinning
  (the member columns for them exist and are `NULL` until it lands).

## Capabilities touched

- `marketplace-ingestion` — GW_0164 added.
- `snapshot-approval` — GW_0165 added.

## Estate

Closures are derived, immutable runtime state produced by ingestion — a record of
what one snapshot resolved to. They are not something an operator declares, so
they are not an estate object type, and `skills-gateway.estate.*` is unaffected.
No new role, no group-mapping consequence.

## Risk

Touches `ApprovalService` (a trust boundary) and `IngestionService`. Old-coder
discipline at **Tier 3**: failure model in `design.md`, negative tests
constructed by tampering with persisted state, manual mutation run, evidence
report.
