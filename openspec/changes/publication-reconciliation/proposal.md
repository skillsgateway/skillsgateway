# Proposal: publication-reconciliation

## Why

Approval records the decision and then publishes. When publication fails the
decision is put back — and when *that* fails too, the row says `approved` while
nothing is served.

That double failure is specified and handled as far as reporting goes:
GW_APPROVAL_0012 — An approval reports success only when the publication
happened requires it be reported rather than discarded, and it is, at error and
attached to the propagating exception. What was missing is anything that
reconciled it *afterwards*.

And the code said otherwise. `ApprovalService.repair()`'s javadoc read:

> that is the case the startup check comparing the served estate against the
> database exists to catch

**No such startup check existed.** Verified against every startup-time component
in the application — the estate bootstrap, the role bootstrap guard, the storage
migration runner, the SBOM endpoint, the object-store probe. None compares
served refs to approved rows. A comment naming a control that was never built is
worse than no comment, because it retires the concern in the reader's mind — and
it did: the finding that produced this change was written by someone who read
that sentence and went looking.

## What Changes

- **`PublicationReconciler`, at startup.** `SmartInitializingSingleton`, so
  after Flyway has migrated and **before the web server starts**. That ordering
  is load-bearing rather than incidental: the repair moves `refs/heads/main`, and
  doing it before anything can be fetched means no client observes the
  intermediate state.
- **Approved but not served is repaired.** Publication is idempotent by SHA, so
  republishing is additive and cannot destroy anything. After any repair the
  newest approved snapshot is republished last, because republishing an older one
  moves the served tip onto it.
- **Served but not approved is reported and left alone.** The asymmetry is the
  design. Deleting served refs on the strength of a database comparison is the
  direction that fails catastrophically and silently when the *database* is what
  is wrong — a migration mid-flight, a restore from a stale dump. Serving
  unapproved content is the graver condition, and precisely because it is,
  retracting it is a judgement for a person holding the report.
- **Serving nothing is repaired; unreadable is skipped.** These are easy to
  conflate and the difference decides a first publication's fate: a marketplace
  serving nothing is exactly what a double failure on its first approval leaves,
  and must be repaired, while treating an unreadable storage as empty would
  republish an entire estate on the strength of a failed read.
- **The javadoc now names something that exists.**

## Capabilities

### New Capabilities

**snapshot-approval.** GW_APPROVAL_0014 — A recorded approval that is not served
is repaired before the gateway serves again.

### Modified Capabilities

_None._ GW_APPROVAL_0012 is unchanged, and deliberately so — see below.

## What this does not do

**It does not reverse the publish/record order**, which is the other half of the
finding that prompted it. That reversal makes the failure mode "served but not
recorded", which a reconciliation can repair, instead of "recorded but not
served", which nothing could. The finding itself says the reconciliation "makes
the reordering below optional", and the reasons to take that option are
concrete:

- Three clauses of GW_APPROVAL_0012 — return the record to its prior state,
  leave a record it cannot return alone, report a repair it could not perform —
  **presume the current order**. Reversing means amending the requirement that
  the change is meant to uphold.
- `GW_FACADE_0019 — Abandoned publication staging references are swept from
  published repositories` rests in its rationale on "approval writes the approved
  row before it publishes". Reversing makes that sentence false, and a later
  reader simplifying the sweep to match it would delete a staging ref out from
  under an in-flight publication.
- It introduces a window where content is genuinely on the wire while the ledger
  has no approval for it. Short, but it is a weaker claim than the product makes
  today, and this change should not weaken one while closing another.
- `undecide()` becomes unreachable while still annotated and covered.

**No scheduled sweep and no admin endpoint.** Recovery is a restart. A new
`@Scheduled` sweep would add to the uncoordinated-singleton problem the
deployment documentation already describes, and a third reconcile endpoint is
surface for a failure that requires two simultaneous faults to reach. If the
double failure turns out to be less rare than it looks, a sweep is the obvious
follow-on and this class is already shaped for one.

## Impact

Startup does slightly more work: per marketplace, one query for its live approved
snapshots and one ref listing. On the object-store backend a published
repository's refs are a single manifest object, so it is one read per
marketplace. An estate with nothing wrong writes nothing and logs nothing.
