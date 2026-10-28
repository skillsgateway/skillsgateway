# Design: decide-compare-and-set

## Context

See proposal.md, Why. Revocation is already a compare-and-set
(`WHERE state = 'approved'`), and the decision was the half that was not.

## Decisions

### D1 — Compare on state and `revoked_at`

Every revocation stamps a new `revoked_at`, and a decision clears it. So state
plus `revoked_at` distinguishes every revoked row from every other: a
withdrawal by re-vetting, then a re-approval, then an administrative
withdrawal leaves the state `revoked` again but with a different
`revoked_at`. Comparing `revoked_kind` as well would add nothing.

Alternative: a version column. Rejected, because it needs a schema change to
express what two existing columns already say.

### D2 — Refuse, do not retry

A changed row means the gates judged something that no longer exists, so the
reviewer has to look again. A `409` with the reason is the existing answer for
"this snapshot cannot be decided".

### Not in this change

Serialising the publication that follows a decision against a concurrent
withdrawal of the published commit is the per-marketplace publication lock, a
separate change. This change makes the row transition itself atomic.

## Risks / Trade-offs

- [A reviewer occasionally sees a `409` when two people act at once] → That is
  the intended outcome, and it says to reload.
