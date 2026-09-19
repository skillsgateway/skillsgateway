# Design: administrative-revocation

## Context

See `proposal.md` — Why. The constraint that shapes the implementation is that
`RevetService.quarantine` already does most of this correctly, and the right move
is to reuse its discipline rather than write a second revocation path that
drifts from it.

What that method already gets right, and what this change must preserve:

- **The database transition comes first and is conditional on the snapshot still
  being approved.** Two concurrent passes cannot both revoke and cannot both
  unpublish; only the one that won the transition touches git.
- **Quarantine is untouched.** The content stays pinned at
  `refs/snapshots/<sha>` in the quarantine repository, which is what makes the
  decision reviewable and reversible by a person.
- **A failure to unpublish after a successful revoke is the loudest possible
  error**, because it is the one case where the record and the wire disagree.

The new surface differs in exactly two ways: the trigger is a person rather than
a chain verdict, and the caller states what is served afterwards.

## Goals / Non-Goals

**Goals:**

- Revocation reachable as an administrative act, independent of vetting.
- The served set after a revocation never changes by accident.
- A withdrawal that outlives the incident, and lifts only by a second
  administrator's recorded decision.

**Non-Goals:**

- A separate un-revoke endpoint. Reversal is not its own act: it is an approval
  carrying an acknowledgement, on the request field
  `GW_VETTING_0028 — Administrative override of a blocked vetting outcome`
  already established. A second endpoint would be a second path into the served
  set.
- Reaching clients that already hold the content
  ([#427](https://github.com/skillsgateway/skillsgateway/issues/427)).
- Pre-emptively blocking a commit never ingested. That is a denylist, which is a
  different object with a different lifecycle — see Decisions.
- Blocking re-ingestion. `IngestionService` already returns the existing row for
  a `(marketplace, sha)` in any state, so a revoked commit does not reappear as
  held. Nothing to build.

## Decisions

### A `RevocationService`, not a method on `ApprovalService`

`ApprovalService` is already the largest trust-boundary class in the tree and
its `doApprove` carries the four-eyes gate, the vetting gate, the policy gate
and the publish-then-repair dance. Revocation shares its *storage seam* and its
*repair discipline* but none of its gates, and folding an act with opposite
preconditions into it would mean a reader can no longer tell which checks guard
which direction.

A sibling service, with `GitStorage` and the audit logger injected the same way,
keeps both readable. `RevetService` stays the automated caller and gains nothing.

*Alternative rejected:* extending `RevetService`. Its whole shape is "a chain
verdict decided this", and an administrative act has no verdict. The ledger
detail would have to lie about where the decision came from.

### The revoke is one transaction boundary plus one storage act, in that order

Exactly the order `RevetService.quarantine` uses, for the reason it uses it:

1. Conditional state transition to `revoked` — returns empty if the snapshot is
   no longer approved, which is also how a double-submit is made harmless.
2. Ledger entry for the revocation.
3. Unpublish, then ledger entry for what the marketplace now serves.
4. If a rollback was asked for, publish the earlier snapshot, then ledger it as
   a publication in its own right.

Step 4 is deliberately after the unpublish rather than a single atomic swap of
the served reference. A swap would be nicer, but the storage seam has no
compare-and-swap across two SHAs, and inventing one for this is the kind of
capability the stop rule exists to refuse. The window in which a marketplace
serves nothing is bounded by two local git operations, and a client that fetches
inside it sees the same thing it would see if the rollback had not been
requested — which is a state the system must handle anyway.

**A failure at step 3 or 4 is reported loudly and not retried**, inheriting
`RevetService`'s reasoning: the snapshot is already revoked in the record, no
later pass will pick it up, and the record and the wire disagreeing needs a
person rather than a retry loop.

### The served-content choice is a required request field with no default

An enum on the request body — roll back, or serve nothing — with **no default
value**, so a client that omits it gets a validation refusal rather than a
behaviour somebody did not choose. This is the whole point of part 2 and it is
worth a slightly less convenient API.

The "roll back with no earlier approved snapshot" case is validated **before the
state transition**, so a request that cannot be honoured changes nothing. The
alternative — revoke, then discover there is nothing to roll back to, then serve
nothing — would deliver an outcome the caller explicitly did not ask for.

### Two kinds of revocation, one state, one column

`revoked` stays one state; a `revoked_kind` column records whether the chain or
an administrator decided it. The kinds need distinguishing because **they lift
differently**, and that is the only reason:

- A **re-vetting** revocation is a machine verdict against a finding. It lifts
  when the finding is cleared — a waiver, or an upstream fix the next run sees.
  `GW_VETTING_0013` requires this and two SVC tests enforce it; neither changes.
- An **administrative** revocation has no finding to clear. The chain already
  clears the content; the objection exists only in a person's knowledge. So the
  only thing that can lift it is another person.

A separate state was rejected: every place that reasons about snapshot state —
retention, the catalog, the facade, the portal — would have to learn it, for a
distinction that matters in exactly two places.

A denylist table was rejected: it adds an estate object type the stop rule
requires a proposal to argue for, and it can drift, since a row saying a SHA is
blocked alongside a snapshot row saying it is not revoked is a contradiction the
derived form cannot express.

### The block lives at approval and at purge, not at ingestion

**Approval.** `doApprove` refuses an administratively revoked snapshot unless
the request carries a reversal. It goes *after* the closure check —
`GW_APPROVAL_0013` mandates that one first — and before everything else.

**Purge.** The refusal is derived from the row, which makes it exactly as
durable as the row, and retention may currently delete a revoked row outright
(`revoked` is in `DELETABLE_STATES`; `purge` is a hard `DELETE`). Without a
guard the withdrawal expires on a timer nobody connected to it. Soft delete
stays allowed — the content is the part worth reclaiming — and the hard delete
is refused for the administrative kind only, naming why.

**Not ingestion**, because `IngestionService` already returns the existing row
for a `(marketplace, sha)` in any state. An earlier draft of this design
specified an ingestion gate on the premise that a revoked commit would reappear
as held on the next sync. That premise was false.

### Reversal is an approval carrying an acknowledgement

The shape already exists. `ApprovalOverride` carries the vetting-failure
override: admin-only, mandatory reason, every other gate still running, a
distinct ledger event, and a marker row so the override is never invisible.
Reversal is a second kind on the same field, with the same properties and one
more:

**The reverser must not be the revoker, unconditionally.** Not through
`FourEyesGate` — that gate compares the reviewer against the registrant, the
ingester and waiver authors, never the revoker, and its default mode records a
conflict and proceeds. Routing reversal through it would make single-handed
reversal the default, which is the opposite of the intent.

A marker row is needed rather than reading `revoked_by`, because
`SnapshotRepository.decide` clears `revoked_at`, `revoked_by` and `violation` on
the way to approved. Without a marker, a snapshot served over a reversed
withdrawal is indistinguishable from one nobody ever withdrew.

### No four-eyes, and the asymmetry is stated in the code

`GW_APPROVAL_0010 — Separation of duties on snapshot approval` requires two
identities to approve. Revocation deliberately requires one, while *reversing*
one requires two. The reason — publishing can do harm, withdrawing cannot, and a
second reviewer during a live incident is latency with no corresponding safety —
goes in a comment at the service, because the next reader will otherwise assume
it was forgotten and "fix" it.

## Risks / Trade-offs

- **An administrator revokes the wrong snapshot** → a second administrator
  reverses it on a stated reason. The revoker cannot do this alone, which is the
  cost of the mistake and is deliberate.
- **Reversal becomes the quiet way back in** → it cannot be reached by waiting,
  by a waiver or by an ordinary approval; it needs a second identity, it names
  the revocation on the ledger, and the marker keeps the episode visible
  wherever the snapshot's vetting is shown.
- **Revocation as denial of service.** A compromised admin credential can take
  every marketplace off the air. This is already true of the existing admin
  surface (deregistration, toggles) and the ledger is the control; noted rather
  than mitigated further.
- **The brief window where a rolling-back marketplace serves nothing** → bounded
  by two local git operations, and indistinguishable to a client from the
  serve-nothing outcome it must already handle.
- **An administrator determined to serve the content routes around all of this**
  → true, and it always was: a commit on top of the withdrawn one is a new SHA
  the gate never sees. What this buys is a record, not prevention, and the design
  should not pretend otherwise.

## Migration Plan

One column on `snapshots`, folded into `V1__init.sql` per the pre-1.0
convention, defaulting to the re-vetting kind so every existing revoked row
reads as what it is. `RevetService` keeps revoking exactly as it did and gains
only the kind it passes. The new endpoint is additive within the major.

**Rollback** is `git revert`; snapshots revoked while it was deployed stay
revoked, which is correct — reverting the code must not un-withdraw content
somebody withdrew.

## Testing discipline

This touches a trust boundary, so `.claude/skills/old-coder` applies and
happy-path coverage is not sufficient. The tests that carry the change are the
negative ones:

- A non-admin, a missing reason and an empty reason are each refused.
- A snapshot that is not approved is refused.
- A revocation takes effect with the vetting chain clearing the snapshot, and
  with the re-vetting mode set to record-only.
- Two concurrent revocations of the same snapshot: one succeeds, one is refused,
  and the content is unpublished exactly once.
- Rollback with no earlier approved snapshot is refused **and nothing is
  revoked** — asserted on the snapshot's state afterwards, not only on the
  status code.
- A revoked commit re-ingested by the scheduled sweep produces no held snapshot
  and one ledger entry.
- A pre-existing held snapshot for a revoked commit cannot be approved, and the
  refusal names the revocation.
- Revoking a snapshot that is not the served one leaves the served set untouched.
