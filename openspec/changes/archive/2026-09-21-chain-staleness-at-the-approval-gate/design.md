## Context

See `proposal.md` — Why, and the decision it takes. What shapes the approach is
how little is missing:

- `VettingService.chainIdentity(long marketplaceId)` already returns the identity
  of the chain a marketplace runs right now — `vetter@version` in chain order,
  then `;mode=…` (GW_VETTING_0033.2 — The chain identity carries the mode and the
  order).
- Every run is stamped with it: `vetting_runs.chain`, surfaced as
  `VettingRepository.Run.chain`.
- `VettingController.vetting(id)` already holds the snapshot, its marketplace id
  and the latest run in the same method.

The identity alone turned out **not** to be enough, and the reason is a
deliberate decision made elsewhere — see the first decision below. Nothing is
computed that was not already recorded, and no column is added.

## Goals / Non-Goals

**Goals:**

- A reviewer at the approval gate can see that the evidence in front of them was
  produced by a different chain than the one in force, and which one.
- The ledger answers "was this approved on stale evidence?" after the fact,
  without a reconstruction.
- A reviewer told their evidence is stale can refresh it.

**Non-Goals:**

- **Not a block.** Approval is not refused, and no configuration decides whether
  it is. See the proposal.
- **Not a sweep.** No configuration write triggers work over the held set; a
  chain change remains a setting the next run reads.
- Not a claim about *what* changed between two chains. The gateway names both
  identities; whether the difference matters is the reviewer's call, and the
  identity string is already legible enough to see a vetter appear in it.

## Decisions

### The chain identity alone does not answer the question

`chainIdentity` looked sufficient, and is not. A vetter an administrator
switches off **stays in the chain** and is recorded as a `disabled` verdict, so
the disablement is part of the run's evidence rather than a silently shorter
chain (GW_VETTING_0029.2 — A disabled vetter is recorded on the run). The
identity therefore does not move when a vetter is toggled — which is precisely
the change this comparison exists to catch. A first implementation compared
identities alone and reported a toggled chain as current; the end-to-end test
caught it.

So both sides are described the same way and compared whole: the identity, plus
which vetters are switched off. The description is **composed** on both sides by
the same code rather than parsed back out of the stored string, so the two
cannot drift apart and no format is depended on.

A vetter the chain never *reached* is deliberately not part of the description:
that is an outcome of one run under stop-after-fail, not a difference in the
chain, and including it would report a stopped run as stale against itself.

`VettingService.chainStaleness(snapshot)` is the single place this is decided.
Two callers need it — the read and the approval gate — and two copies of a
comparison this subtle would diverge.

### Staleness is derived on read, never stored

A stored flag would be wrong the moment the chain changed again, and would need
invalidating from every path that can change a chain — the two settings services,
and the vetter registry. Deriving it on read makes it correct by construction and
costs one string comparison against a value already loaded.

This mirrors how the effective outcome is already handled in the same method:
evaluated per request, so that an expired waiver stops suppressing without
anything having run in the background.

### A run with no chain recorded is not stale

Rows written before the identity was stamped carry a null or empty `chain`.
Reporting those as stale would flood every estate that predates the stamp with
a warning nobody can act on, and the honest answer is "unknown", not "stale".
Such a run reports staleness as unknown, distinctly from both true and false.

The same applies to a snapshot with **no run at all**: it is already blocked by
the outcome rule, and calling its absent evidence stale adds nothing.

### The approval record states it; the approval proceeds

`ApprovalService` computes the same comparison at the moment of approval and
writes it into the ledger detail of the approval event. Deliberately at approval
time rather than copied from the read: a reviewer can leave the page open, and
what the ledger must record is the chain in force when the decision landed, not
when the page rendered.

This is the same shape as the four-eyes conflict record (GW_APPROVAL_0011 —
Four-eyes mode configuration and ledger visibility), which records a detected
conflict in both modes and lets the approval proceed in one of them. Here there
is one mode, and it records.

### Re-running a held snapshot's chain is not re-vetting

`RevetService.revet` does more than run the chain: it classifies, records a
retroactive violation naming every identity that already fetched the content,
and in enforce mode unpublishes. Every one of those is about content **in the
field**. A held snapshot has no fetchers, nothing to retract, and no violation
in the retroactive sense — a blocking verdict on held content is just the
approval gate doing its job.

So this is a separate, smaller path: run the chain, record the run, append a
ledger entry saying the evidence was refreshed. It reuses `VettingService.run`,
which is what ingestion uses, with the manual trigger. It emits no
revet-violation event and can unpublish nothing, because there is nothing
published.

*Alternative: relax `RevetService`'s state check and branch inside `revet`.*
Rejected — it would put "is this published?" conditionals through a method whose
ordering is a stated safety property, to serve a case that shares only its first
line.

### Where the marking lives in the portal

On the vetting report, beside the verdicts it qualifies, rather than as a banner
on the page or a badge on the snapshot row. The claim is about *this evidence*,
so it belongs against the evidence; a reviewer who has not opened the report has
not yet reached the decision it informs.

## Risks / Trade-offs

- **A reviewer may approve on stale evidence anyway.** → That is the decision
  this change deliberately leaves with them, and it is now recorded against
  their name rather than invisible. If the owner wants it refused, the fact this
  change computes is exactly what a refusal would gate on.
- **The chain identity is a string, and a cosmetic change to it would read as
  staleness.** → It is built from vetter names, versions and the mode, all of
  which are semantic. A vetter version bump *should* read as stale: it is new
  code looking at the content.
- **Re-running a held snapshot's chain can turn a clear snapshot into a blocked
  one.** → That is the point, and it is the state the approval gate already
  handles. It is initiated by a person, never by a sweep or a setting.
- **Two paths now run the chain outside ingestion.** → They are distinguished by
  what they do with the answer, not by how they produce it; both call
  `VettingService.run`, and the trigger recorded on the run says which.
