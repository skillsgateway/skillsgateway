# Proposal: administrative-revocation

## Why

A supply-chain compromise is discovered in content the gateway has already
approved and is serving. An administrator has no way to withdraw it.

`SnapshotRepository.revoke` exists and works, but its only caller is
`RevetService`: a snapshot becomes revoked solely as a *side effect* of a
re-vetting run whose chain now blocks it, and only when
`skills-gateway.revet.mode` is `ENFORCE` — which is not the default. Nothing
else in the system can revoke. So the path available today is to write a vetter
that fails the specific SHA, switch the estate into enforce mode, and re-vet —
a code or configuration change made under incident pressure.

The other levers do not reach:

- **Policy deny rules cannot retract.** `PolicyGate.enforce` is called from
  exactly one place, `ApprovalService`, at approval time. A new rule matching the
  bad content does nothing to content already approved.
- **Retention cannot touch it.** An approved snapshot is an absolute guard,
  never eligible by policy or by hand.

The machinery for the withdrawal itself is almost all present — the ledger
event, the webhook, `GitStorage.unpublish`, mirror reconciliation. What is
missing is a door, a decision, and durability.

## What Changes

### 1. A direct administrative revoke

`POST /api/snapshots/{id}/revoke`, admin-only, with a mandatory non-empty stated
reason. It revokes an approved snapshot **whatever the vetting chain currently
says**, because the reason is human knowledge the chain does not have: a CVE
disclosure, a maintainer compromise, a dependency found to be malicious. It does
not require `revet.mode=ENFORCE`; the mode governs what *automated* re-vetting
does with its own findings and has no bearing on an administrator's decision.

The decision is recorded on the append-only ledger with the acting identity and
the reason, `marketplace.snapshot.revoked` is emitted, and the content is
unpublished through the same storage seam approval publishes through, so the
facade stops serving it and mirror reconciliation withdraws it from the forge.

**No four-eyes.** Approval has one because publishing is the direction that can
do harm; revocation only ever withdraws, so a second identity buys no safety and
costs time during a live incident. The mandatory reason and the ledger entry are
the accountability. Stated here because it is a deliberate asymmetry with
`GW_APPROVAL_0002`, not an oversight.

### 2. What is served afterwards is a stated choice

Revoking the snapshot a marketplace is currently serving otherwise takes that
marketplace off the air as a side effect nobody chose. The request must say what
happens next, and **a request that does not is refused**:

- **roll back** to the previous approved snapshot of that marketplace, or
- **serve nothing**.

Neither is a safe default. Rolling back silently republishes older content that
may carry the same compromise — a poisoned transitive dependency is usually in
the previous snapshot too. Serving nothing takes a marketplace down, which for a
marketplace with an obviously clean predecessor is an unnecessary outage. The
caller knows which; the gateway does not.

A rollback **is a publication** and is recorded as one, distinctly from the
revocation, so the ledger shows both acts rather than one act with a hidden
second effect. Asking to roll back when there is no earlier approved snapshot is
refused before anything is revoked, rather than silently degrading to serving
nothing.

### 3. A revoked commit stays refused

Revoking a SHA means nothing if tomorrow's scheduled sync re-ingests the same
upstream commit and a reviewer approves it again in good faith. A revoked
commit is refused **at ingestion**, so it does not quietly reappear as held, and
**at approval**, so it cannot be approved even if a snapshot for it exists from
before the revocation.

This is **derived from the revoked snapshot row**, not a new denylist table:
the question "is this (marketplace, sha) revoked?" is already answerable from
`snapshots`. A derived answer cannot drift from the revocation it describes, and
it adds no estate object type — which the stop rule requires a change to argue
for, and this one does not need.

Both refusals **name the revocation**: its reason, who made it, and when. A
reviewer who hits this must understand why, not meet an opaque wall.

## Capabilities

### New Capabilities

None. Revocation belongs to the existing approval and ingestion capabilities.

### Modified Capabilities

- `snapshot-approval`: gains administrative revocation as a first-class act
  (`GW_APPROVAL_0015`), the served-content choice that accompanies it
  (`GW_APPROVAL_0016`), and the approval-side refusal of a revoked commit
  (`GW_APPROVAL_0017`).
- `marketplace-ingestion`: gains the ingestion-side refusal of a revoked commit
  (`GW_INGEST_0032`).

## Impact

- **API**: one new endpoint. Additive within the major, per
  `docs/manual/reference/compatibility.md`.
- **Backend**: a revocation service beside `ApprovalService` reusing its storage
  seam and repair discipline; a revoked-commit check at the ingestion and
  approval gates; `SnapshotRepository` gains the lookup the check needs.
- **Schema**: none expected. The `snapshots` row already carries `state`,
  `revoked_at`, `revoked_by` and the violation.
- **Trust boundary.** This writes to the served set and adds a gate at approval.
  `.claude/skills/old-coder` discipline applies: adversarial and negative tests,
  not happy-path coverage.
- **Declarative estate (#65) does not extend.** A revocation is an *act*, not
  converge-able state — the same line `ADR 0014 — Estate export` drew for
  exports. `skills-gateway.estate.*` gains nothing, and reconciling a converged
  estate must not re-revoke anything.
- **Docs**: the REST API reference, and the approving-snapshots guide gains
  the withdrawal half of the lifecycle.
- **Out of scope**: telling clients that already hold the content
  ([#427](https://github.com/skillsgateway/skillsgateway/issues/427)). This
  change makes the gateway stop serving it; making a client notice is a separate
  problem with its own design.
