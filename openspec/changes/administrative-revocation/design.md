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
- A revoked commit that cannot come back through the front door.

**Non-Goals:**

- Un-revoking. A revoked snapshot stays revoked; the way to serve that content
  again is a fresh ingestion of a fresh upstream commit that has not been
  revoked. Adding an "unrevoke" would make the durability in part 3 a lie.
- Reaching clients that already hold the content
  ([#427](https://github.com/skillsgateway/skillsgateway/issues/427)).
- Pre-emptively blocking a commit never ingested. That is a denylist, which is a
  different object with a different lifecycle — see Decisions.

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

### The revoked-commit check is derived, and lives at both gates

The question is "does a snapshot exist for this `(marketplace, sha)` in state
`revoked`?", which `snapshots` already answers. One repository method, called
from two places: the ingestion path before a snapshot row is created, and
`ApprovalService.doApprove` beside the existing gates.

*Alternative rejected:* a `revoked_commits` denylist table. It would add an
estate object type — which the stop rule requires a proposal to argue for — and
it can drift: a row saying a SHA is blocked, and a snapshot row saying it is not
revoked, is a contradiction the derived form cannot express. The one capability
the table would add and the derived form cannot — blocking a commit that has
never been ingested — is not a need anyone has stated, and `policy-rules`
already covers "refuse content matching this shape" at approval.

**Where in `doApprove` the gate goes matters.** It goes with the other gates,
before any state transition and before publication, so a refused approval leaves
the snapshot exactly as it was. It is cheap — one indexed lookup — so ordering
against the vetting and policy gates is a readability question, not a
performance one; it reads best first, because "this commit was withdrawn" is a
more fundamental objection than anything the chain or the rules might say.

### No four-eyes, and the asymmetry is stated in the code

`GW_APPROVAL_0002` requires two identities to approve. Revocation deliberately
requires one. The reason — publishing can do harm, withdrawing cannot, and a
second reviewer during a live incident is latency with no corresponding safety —
goes in a comment at the service, because the next reader will otherwise assume
it was forgotten and "fix" it.

## Risks / Trade-offs

- **An administrator revokes the wrong snapshot** → there is no un-revoke by
  design, so the recovery is a fresh ingestion. Mitigated by the mandatory
  reason and by the rollback option keeping the marketplace serving. Accepted:
  the alternative is an unrevoke path that makes part 3 unenforceable.
- **Revocation as denial of service.** A compromised admin credential can take
  every marketplace off the air. This is already true of the existing admin
  surface (deregistration, toggles) and the ledger is the control; noted rather
  than mitigated further.
- **The brief window where a rolling-back marketplace serves nothing** → bounded
  by two local git operations, and indistinguishable to a client from the
  serve-nothing outcome it must already handle.
- **Refusing re-ingestion of a revoked commit hides a moved upstream** → no: the
  refusal is per `(marketplace, sha)`, so a new upstream commit ingests
  normally. Only the exact withdrawn commit is refused, and the refusal is on
  the ledger rather than silent.
- **A marketplace pinned to a revoked commit stops ingesting entirely** → true
  and intended; the ledger entry says why on every sweep, which is the signal an
  operator needs to move the pin.

## Migration Plan

None. No schema change, no existing behaviour altered — `RevetService` keeps
revoking exactly as it did, and approval keeps its existing gates with one more
in front. The new endpoint is additive within the major.

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
