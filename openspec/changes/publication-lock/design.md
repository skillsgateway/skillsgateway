# Design: publication-lock

## Context

See proposal.md, Why. `decide` is a compare-and-set
(`GW_APPROVAL_0031 — A decision lands only on the snapshot state it was made
about`), and `revoke` is one too. What is missing is a span covering each
transition together with its reference write.

## Decisions

### D1 — A transaction-scoped advisory lock per marketplace

`pg_advisory_xact_lock(namespace, marketplaceId)`, taken inside a
`TransactionTemplate` that also runs the record writes. The lock is released at
commit or rollback, so no failure path can leak it, and the record and the
lock end together. The two-key form keeps it apart from the single-key lock
that `NameCollisionGate` takes.

Alternative: a session-level lock. Rejected, because it needs the same pooled
connection for lock and unlock, and leaks on a path that forgets the unlock.

### D2 — The copy outside, the transition inside

`GitStorage.stage` (copy plus staging ref) is split out of `publish`, so an
approval holds the lock only for the decision and `commitPublication`. A
rollback and a repair still call `publish` under the lock. Both are rare, and
both must re-read their target there.

### D3 — A refused publication rolls the decision back

Decision and publication now share one transaction, so a refused
`commitPublication` rolls the `approved` row back with it. The separate
`repair` step that restored the row afterwards (`GW_APPROVAL_0012 — An approval
reports success only when the publication happened`) is no longer needed.
`SnapshotRepository.undecide` stays: it has another caller. The name-collision
refusal is recorded outside the lock, so the rollback does not take its ledger
entry with it.

### D4 — Lock order

Approval takes the publication lock, then `NameCollisionGate`'s global lock,
then the marketplace row `FOR SHARE`. Nothing takes them in another order.
Marketplace removal takes the row lock and unpublishes without the publication
lock, so it cannot deadlock with an approval: the approval's `decide` waits for
the removal's commit and is then refused.

## Risks / Trade-offs

- [The global name-collision lock is now held until the reference transition
  commits] → That is a single reference update after staging, so the wait is
  short.
- [A commit that fails after the references moved] → The references serve a row
  that rolled back. The reconciler reports it as served without an approval,
  as it would any such drift.
