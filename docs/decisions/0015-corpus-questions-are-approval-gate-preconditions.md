# ADR 0015 — Corpus questions are approval-gate preconditions, not vetting connectors

*Proposed, 2026-09-08.*

## Context

T5 — typosquatting and lookalikes — is the one row of the threat model in
[`architecture.md` §2](../manual/architecture.md) with no mitigation at all. Issue
[#253](https://github.com/skillsgateway/skillsgateway/issues/253) asks for the
first one: a rule that can ask a question of the **approved estate** rather than
only of the snapshot in front of it.

Every control the gateway has today answers a question about one pinned commit.
`SecretScanConnector` asks "does this content contain a credential"; `LicenseScanConnector`
asks "what licence does this content declare"; the CEL policy rules of
[ADR 0006](0006-embedded-cel-for-policy-rules.md) ask "does this content match a
deny expression". None of them can ask "is there already something called this".

That is the question T5 needs, and asking it changes the shape of the system,
because a chain run is currently a **pure function of (pinned content, chain
identity)** and nothing else. Those two inputs are exactly what `vetting_runs`
records — `snapshot_id`, and `chain` as `connector@version` in chain order — and
the schema comment says why in as many words: so that *"the same content was
vetted again and the answer changed" can be told apart from "a different chain
looked at it" without re-deriving anything*.

That property is not decoration. It is the entire answer to
`GW_0049 — Continuous re-vetting of approved snapshots`. A re-vetting sweep is
worth running only because a changed verdict over unchanged content means the
chain learned something. Add a third input that nobody recorded and a changed
verdict means nothing in particular.

[ADR 0009](0009-external-vetting-connector-contract.md) already decided one case
of this, and decided it hard: external connectors are **configuration, not
API-managed state**, precisely so that *"cleared last month, blocks today —
content or chain?"* stays answerable. Corpus state is the same question asked of
something that cannot be frozen into a deployment.

### What actually exists today

Three details matter and two of them are not what the issue assumed.

**`SnapshotFactsService` is not reachable from vetting.** It builds a
`Map<String, Object>` of `snapshot` / `files` / `plugins` / `skills` — including
every plugin's name — by walking the pinned commit in quarantine, and it is called
from exactly two places: `PolicyGate.enforce` at approval time, and the read-only
rule playground. `PolicyGate` short-circuits before building it when no rule is
enabled, and the map is discarded when the call returns. **Nothing is persisted.**
Plugin names exist nowhere in the database — `snapshot_closure_members.plugin_name`
is about external plugin sources, not the catalog. So there is no corpus to query,
and vetting and policy evaluation are two content-reading subsystems that share
nothing.

**`CatalogService.mergePlugin` does not detect what it looks like it detects.**
Its collision key is the *marketplace-prefixed* name, `<marketplace>-<plugin>`.
The same plugin name in two different marketplaces therefore never collides — the
prefix makes them distinct — and what the log line actually catches is the
concatenation ambiguity: marketplace `zed` with plugin `tools-pro` and marketplace
`zed-tools` with plugin `pro` both prefix to `zed-tools-pro`. The rule is
"first in marketplace-name order wins, the rest are dropped and logged", which
makes it a **name-shadowing primitive**: registering a marketplace whose name
sorts earlier and whose plugin prefixes to an incumbent's name silently removes
the incumbent from the virtual catalog. That is a live T5 variant nobody has
written down, and it is not the exact-name collision the issue described.

**The SPI forbids the query on purpose.** `SnapshotUnderVetting`'s contract says
a connector *"must not be able to write to quarantine, move a ref, or reach
another marketplace's content"*, and that the same abstraction must be handable to
an out-of-process connector unchanged. A corpus-querying connector breaks the
third clause of the first sentence directly.

### The precedent this decision follows

The vetting concept document already draws the line this ADR is about, for the
minimum release age:

> It is deliberately not a connector. A verdict is evidence about content at the
> moment it was gathered; "too young" is a fact about now. Recorded as a failing
> verdict it would keep blocking after the age had passed, until some later
> re-vetting run happened to replace it. Checked at the approval request instead,
> it clears itself and leaves nothing behind.

"Is there already something called this" is the same kind of proposition as
"too young". It is a fact about now, and about the rest of the estate, not
evidence about these bytes.

## Options considered

### Option A — A corpus-querying connector, with a recorded corpus watermark

Give connectors a `CorpusQuery` handle and stamp the estate state each run saw —
a monotonic sequence number, or the maximum `decided_at` over approved
snapshots — into a new `vetting_runs` column beside `chain`. A re-run is then
reproducible *against that watermark*, and a divergence is attributable.

This is the option the issue leans toward, and it is the one worth killing
carefully, because it looks like it solves the problem.

**It records a watermark the system cannot resolve.** Reproducing a run means
reconstructing the approved estate as it stood at that watermark. `snapshots`
holds current state with `decided_at` / `revoked_at` overwritten in place, and
the state machine permits `revoked → approved`, so a snapshot that cycled has
lost its earlier transitions. Retention purges rows outright. The estate is not a
temporal table and nothing reconstructs it. What survives is the append-only
ledger — and `GW_0096 — Four-eyes separation of duties on approval` already
refused exactly this move for a structurally identical reason, recorded in its
rationale: deriving state from the ledger *"would make an append-only
observability surface load-bearing for an authorization decision"*, so the
supply-side identities were put on the objects instead. Making a gating decision
reproducible via the ledger is that same mistake with a different consumer.

`GW_0034 — Scheduled hard-delete compaction` makes it worse than "not stored":
purging a snapshot cascades its `vetting_runs`, verdicts and findings away with
it, so a run recorded against a watermark can outlive neither the estate it saw
nor, in the general case, its own evidence. Only `fetch_log` is never pruned.

So the watermark would say *which* estate you would need without giving you any
way to obtain it. That is bookkeeping shaped like reproducibility, and it is
worse than an honest gap, because it will be cited as if the question were
answered. Making it genuinely resolvable means adding append-only estate history
as load-bearing state — a much larger change than a vetting rule, and one that
should be decided on its own merits if it is ever wanted.

**It makes the re-vetting sweep able to empty the estate.** `RevetService` selects
oldest-first bounded batches and, under `GW_0050 — Auto-quarantine of a violating
approved snapshot` in enforcing mode, revokes inline inside the per-snapshot loop
rather than in a second pass. A corpus rule turns that into two failure modes at
once. Within a sweep, the estate a snapshot is judged against depends on its
position in the batch. Worse, the rule is symmetric while the threat is not: when
the rule is first enabled over an estate that already contains collisions, *both*
sides of every collision produce a `FAIL` verdict — and `RevetVerdict.classify`
sends a `FAIL` to `VIOLATION`, which is the branch that revokes, unlike `ERROR`,
`PENDING` and `DISABLED` which it deliberately routes to `INCONCLUSIVE`. Both
sides are therefore withdrawn from the facade. A control whose first sweep can
empty the estate in pairs is not one to enable by default.

The asymmetry is the crux. Typosquatting has an incumbent and a newcomer. A
vetting connector has no notion of either — it is handed one snapshot and asked
about its content. First-come-wins cannot be expressed in the chain without
inventing an ordering the chain does not have.

**It forks the connector SPI.** An in-process connector could hold a `CorpusQuery`;
`ExternalVettingConnector` POSTs a bounded bundle to an operator-configured URL
and structurally cannot. Closing the gap means shipping estate extracts to that
endpoint — and ADR 0009's own disclosure reasoning, plus
`GW_0157 — Lifecycle event payloads carry no snapshot content`, both say that what
the gateway pushes outward to a URL-authorised target is disclosed to whoever
holds the URL. Leaving the gap means two classes of connector, permanently.

*Rejected*, chiefly on the first point.

### Option B — A separate, explicitly non-reproducible class of check inside the chain

Mark corpus-aware connectors `estate-dependent`; record their verdicts, and have
re-vetting either skip them or treat them as informational.

This is at least honest about what it gives up. But `VettingChain`'s aggregation
has exactly one rule — clear iff at least one verdict exists and every verdict is
`PASS` or `WARN` — and ADR 0010 extended it once already, carefully, to admit a
`disabled` verdict without opening a free pass. A second tier means the effective
outcome is no longer computable from the run: you would also need to know *when*
it ran and against what. The ledger loses the property that a run's verdicts are
all the same kind of evidence, which is what makes a run readable years later.

And it fixes neither the order-dependence nor the SPI fork. It only stops
pretending they are absent.

*Rejected.*

### Option C — Corpus questions are approval-gate preconditions (chosen)

Persist the facts as a corpus, and ask the corpus question at the approval
request, alongside the minimum release age, not in the chain.

### Option D — Cover T5 only at registration and in the catalog

Extend the `GW_0166 — Duplicate upstream URL is reported as a registration
warning` precedent: warn at registration when a marketplace *name* is a near-miss
of an existing one, and turn `CatalogService.mergePlugin`'s log line into a
refusal.

Cheap, introduces no new concept, and it lands on the half of T5 the architecture
document's own example actually describes — `owner/claude-skills` versus
`0wner/claude-skills` is a *registration* act, not a plugin name. But it does not
gate the plugin-name half at admission, and making `mergePlugin` refuse
post-approval produces an approved-but-unmergeable snapshot, which is a worse
state than a refused approval.

*Adopted in part, as a complement rather than an alternative* — see Consequences.

### Option E — Do nothing; leave T5 uncovered

The honest option, and not absurd. Near-miss matching has a bad false-positive
posture, and a noisy control that reviewers learn to waive without reading is
worse than no control — the same argument the waiver design already makes about
line numbers in waiver scopes. Rejected, but it is the reason the first rule
below is conservative rather than clever.

## Decision

**A corpus question is a precondition of the approval decision, evaluated at the
approval request against the estate as it stands at that instant. It is not a
vetting connector, and the vetting chain's inputs stay (pinned content, chain
identity).**

Four parts.

**1. The facts become a corpus.** `SnapshotFactsService`'s output is persisted per
snapshot at ingestion, written once and never rewritten, because it is a pure
function of the pinned commit. That is what turns "the approved estate" into
something queryable in SQL instead of a fan-out of JGit walks, and it is the step
that connects vetting and policy evaluation — `PolicyGate` reads the stored facts
rather than rebuilding them. This half has no reproducibility consequence at all.

**2. The corpus question is asked at the gate.** In `ApprovalService.doApprove`,
with the closure-completeness, vetting, policy, release-age and four-eyes gates,
before any state transition. This is what makes it *gate*: the issue's complaint
about `mergePlugin` is that it notices post-approval, when the content is already
published.

**3. First-come-wins, by construction.** The incumbent estate is never
re-evaluated by this rule; only the snapshot seeking admission is. There is no
sweep, no revocation path, and no way for the check to withdraw content that is
already served. Asymmetry is the property that makes the control safe to enable
on an estate that already contains collisions, and it is also the correct
semantics: in a typosquat the incumbent is the victim.

**4. Acceptance goes through the existing waiver mechanism.** The refusal names a
stable rule id, and `vetting_waivers` — one rule, one marketplace, one scope, one
expiry, a mandatory justification and approver — is the acceptance act. Nothing
about the waiver machinery has to change to make this possible: `rule_id` is an
opaque `TEXT` column, `WaiverService.create` takes the rule id as an argument and
derives the marketplace and commit SHA from the snapshot, and `Waiver.covers`
matches on rule id plus scope. What this decision adds is **one more evaluation
site that consults waivers**, not a second acceptance concept. The finding a
corpus refusal reports therefore has to carry a `path:line`-shaped location like
any other, or `PATH` scope silently matches nothing — the collision should be
located at the manifest entry that declares the offending name. The
[ADR 0010](0010-admin-override-of-vetting-automation.md) administrative override
does **not** lift it, exactly as it does not lift the policy, release-age or
four-eyes gates: that override is scoped to the vetting block.

### What the first rule matches

Deliberately narrow, and not fuzzy:

- **Normalise, then compare exactly.** Unicode NFKC, casefold, apply the UTS #39
  confusable skeleton, then collapse `-`, `_`, `.` and whitespace. `Claude-Skills`,
  `claude_skills`, `claudeskills` and `cIaude-skills` all reduce to one key.
- **Block on an exact match of that key** against a plugin name in an
  already-approved, non-deleted snapshot of a *different* marketplace. A snapshot
  never collides with its own marketplace's history.
- **Nothing else.** In particular **no edit distance in the first slice.**

Edit distance is where this becomes dangerous rather than merely hard. Its
false-positive rate scales with the size of the estate — the bigger the corpus,
the more legitimate names fall within one edit of something — so the control gets
noisier exactly as it gets more useful, which is the shape that trains reviewers
to waive on sight. The evidence that would settle it is a measurement nobody here
has: the fraction of names within Levenshtein 1 of another name in a real
multi-thousand-plugin estate, and how many of those pairs a reviewer judges
distinct. Until that number exists, edit distance stays out. Normalisation and
confusables need no such measurement, because the pairs they match are the same
name in any practical namespace.

### The known false-positive shape, and its blast radius

The real one is a legitimate fork or vendored copy: two marketplaces genuinely
carrying `code-review`. The waiver is the answer, and its cost should be stated
plainly. A waiver keys on `(rule id, marketplace, scope)`, not on the *pair*, so
waiving the collision rule at `SNAPSHOT` scope accepts every collision that
snapshot has — including one against a plugin that enters the estate later. That
is a genuine widening. It is bounded by `SNAPSHOT` scope dying with the commit
SHA, so the acceptance is re-made deliberately at the next ingestion, and that is
judged an acceptable trade for not inventing a second waiver dimension before
anyone has hit the case. A finer waiver key is a follow-on if evidence shows it
matters.

## Consequences

- **`GW_0049 — Continuous re-vetting of approved snapshots` keeps its meaning
  intact.** No run consumes corpus state, so no watermark is needed and a changed
  verdict over unchanged content still means the chain learned something. This is
  the whole payoff of the option chosen, and the reason to prefer it over a
  watermark that would be recorded but not resolvable.
- **The order-dependence problem does not arise.** The sweep never evaluates a
  corpus rule. The gate evaluates one snapshot, at a human's request, at one
  instant.
- **`SnapshotUnderVetting` is unchanged and external connectors stay
  first-class.** No SPI fork, and no estate data is shipped to an
  operator-configured endpoint.
- **Approval acquires a second failure mode that depends on something other than
  the snapshot.** Two approvals racing could each see an estate without the other.
  The check must therefore be evaluated inside the same guarded transition that
  performs the approval, or be backstopped by a uniqueness constraint on the
  normalised key. This is the sharpest implementation risk the decision creates
  and it belongs in the change's adversarial tests, not in a note.
- **A collision introduced by an estate change that is not an approval is not
  caught.** Un-revoking a snapshot is the concrete case: `revoked → approved` is a
  permitted transition and it re-admits content past a gate that ran when the
  estate looked different. The change must route un-revocation through the same
  precondition or state why not.
- **T5 is not closed by this.** It covers plugin-name collision at admission. The
  marketplace-name half — Option D's registration-time near-miss warning — and the
  `mergePlugin` shadowing primitive described in Context are separate, cheaper
  pieces of work that this ADR endorses and deliberately does not bundle.
- **The corpus invites a search index and is not one.** `snapshot_facts` is a
  gating input. The search and discovery half of
  [#153](https://github.com/skillsgateway/skillsgateway/issues/153) was closed as
  not-now for want of a corpus; a corpus existing is not by itself a reason to
  reopen it.

## Decisions to confirm with the owner

1. **The reproducibility answer.** This ADR says corpus-aware verdicts do not
   belong in the chain, rather than making them reproducible against a recorded
   watermark. If a corpus-aware *connector* is wanted for a reason this document
   does not capture — an operator's existing scanner that expects estate context,
   say — then Option A's cost has to be paid in full, starting with append-only
   estate history, and this ADR should be superseded rather than worked around.
2. **Whether a corpus refusal is waivable at all.** The alternative is the
   release-age model: a refusal with no acceptance act, which clears only when the
   estate changes. That is stricter and simpler; it also means a legitimate fork
   can never be approved, which is why the waiver is proposed.
3. **Confusable folding.** UTS #39 skeleton is assumed. It matches `0wner`/`owner`
   and also `rn`/`m`; if the second is judged too aggressive for a blocking rule,
   the fold narrows to a decided character set.
4. **Un-revocation.** Whether `revoked → approved` re-runs the precondition. It
   is the honest answer and it means a snapshot can become un-restorable through
   no act of its own.

Supersedes nothing. Extends the vetting-connector model of
[ADR 0002](0002-toolchain-and-product-decisions.md) and constrains
[ADR 0009](0009-external-vetting-connector-contract.md) by ruling out a corpus
capability in the connector contract it defines.
