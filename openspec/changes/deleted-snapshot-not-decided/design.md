# Design: deleted-snapshot-not-decided

## Context

See proposal.md, Why. `decide` is the one place a snapshot's state moves to
approved or rejected, behind the compare-and-set from `GW_APPROVAL_0031 — A
decision never overwrites a state that changed after the caller read it`.

## Decisions

### D1 — Refuse in `decide`, check again in the statement

The check on the row the caller read gives the reason ("restore it before
deciding it"). `AND deleted_at IS NULL` in the UPDATE covers a soft delete
that commits between that read and the write. That case falls into the
existing "changed while it was being decided" refusal.

### D2 — Restore is the way back

Retention already has `POST /snapshots/{id}/restore`. Requiring it keeps one
route back into the live estate, and the restore is on the ledger.

## Risks / Trade-offs

- [An approver who sees a deleted snapshot in the portal cannot approve it
  directly] → The 409 names the step: restore, then decide.
