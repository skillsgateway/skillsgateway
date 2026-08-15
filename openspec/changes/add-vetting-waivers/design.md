# Design: add-vetting-waivers

## Context

`add-vetting-chain` shipped the gate and, deliberately, the crudest possible way past it: a
single free-text `overrideReason` on the approve request, recorded against the run. Its own
design called that "the minimum auditable escape hatch" and named its replacement. This is the
replacement.

The thing being replaced is not the *existence* of an escape hatch — a fail-closed gate with no
exception path gets disabled, not obeyed — but its **shape**. A blanket override is a single act
that accepts an unbounded, unnamed, permanent set of risks. A waiver is many small acts that each
accept exactly one named risk, on named content, until a named date.

Everything below is resolved in the fail-closed direction: the question "does this waiver apply?"
is answered no whenever it is not unambiguously yes.

## Goals / Non-Goals

**Goals**

- A waiver that cannot be written without a justification, an approver and a future expiry.
- Scope narrow enough that waiving one finding cannot silently accept another.
- Expiry that works with no scheduler running, because the gate recomputes it.
- An outcome vocabulary in which "clean" and "accepted risk" are different words.
- A ledger from which an auditor can reconstruct the accepted risk without the database.

**Non-Goals**

- Re-vetting automation (issue #24). An expired waiver re-blocks the *gate*; it does not
  un-publish already-served content or trigger a new chain run. Stated plainly in the docs.
- Approval workflow for waivers themselves (four-eyes, request/approve separation). The acting
  reviewer is the approver in v1; the ledger makes that reviewable after the fact.
- Waiver inheritance between marketplaces, or org-wide waivers. A waiver belongs to exactly one
  marketplace, which is the unit of trust the gateway already governs.
- Severity-based auto-waiving, or waivers with a maximum lifetime policy. Both are policy knobs
  worth having, neither is needed for the mechanism to be correct.

## Decisions

### D1 — A waiver is (marketplace, rule id, scope), and scope has exactly two kinds

```
vetting_waivers
  marketplace_id  → the unit of trust; a waiver never crosses one
  rule_id         → Finding.id, the stable rule identifier ('aws-access-key-id')
  scope_kind      → 'snapshot' | 'path'
  scope_value     → the commit SHA, or a repository-relative path prefix
  justification   NOT NULL
  approved_by     NOT NULL
  expires_at      NOT NULL     ← the "no unlimited waivers" rule, in the schema
  revoked_at/by   nullable
```

The finding model gives exactly two stable handles: `Finding.id` (the rule) and
`Finding.location` (normally `path:line`). The line number is *not* stable — inserting a line
above moves it — so scope is matched on the **path part only**, and the two scope kinds are the
two honest readings of "this content":

| Scope kind | `scope_value` | Matches a finding when |
| --- | --- | --- |
| `snapshot` | the commit SHA | the finding is in a run of a snapshot with that SHA |
| `path` | a repo-relative path | the finding's path equals it, or lies under it as a directory prefix |

A `snapshot` waiver is the tightest thing expressible: it dies with the SHA, so the next
ingestion re-blocks. A `path` waiver is the one that survives re-ingestion — the case the issue
calls "this skill/path within the marketplace" — at the cost of covering content that does not
exist yet under that path. That trade is the reason expiry is mandatory rather than merely
recommended.

Path matching is prefix-on-segment-boundary (`plugins/a` matches `plugins/a` and
`plugins/a/x.md`, never `plugins/ab.md`) and never contains `..`; both are enforced at creation
and re-checked at match time. There is no glob syntax: a pattern language is a place for a
matcher bug to become a trust-boundary bug.

**Rejected: waiving by `(rule, path, line)` or by a hash of the finding.** Both are so tight that
they expire on the next edit anywhere in the file, which trains reviewers to re-waive without
reading. Tight enough to be honest beats tight enough to be useless.

**Rejected: a `marketplace`-wide scope with no path.** It is a blanket override wearing a
justification, which is the thing being removed. Whole-marketplace acceptance is expressible as
`path` scope with value `""`… so `scope_value` is `NOT NULL` and rejected when blank.

### D2 — The recorded run is raw; the outcome that gates is derived

`vetting_runs.outcome` keeps its two-valued check (`clear` | `blocked`) and keeps meaning "what
the connectors said". Waivers never rewrite it. What the gate consults is the **effective
outcome**, computed on read:

```java
WaiverEvaluation.evaluate(Run run, List<Waiver> waivers, String sha, Instant now) → Effect
```

pure, static, no repository, no clock injection beyond `now` — so it is exhaustively testable the
same way `VettingChain.aggregate` is.

Per verdict:

- a **clearing** verdict (`PASS` / `WARN`) stays clearing;
- a **non-clearing** verdict with **no findings** stays non-clearing — `PENDING` and any future
  stateful verdict can never be waived away, because there is nothing to name;
- a **non-clearing** verdict **with** findings is re-derived from its residual findings by the
  same `Verdict.of` rule the connector's own state came from. If every `HIGH`/`CRITICAL` finding
  is waived, the residual derives to `WARN` or `PASS` and the verdict clears.

Reusing `Verdict.of` rather than writing a second severity rule is deliberate: there is exactly
one place where severity becomes a state, before and after waivers.

Then the outcome:

| Effective states | Any waiver suppressed something? | Outcome |
| --- | --- | --- |
| all clearing, run non-empty | no | `CLEAR` |
| all clearing, run non-empty | yes | `CLEAR_WITH_WAIVERS` |
| anything else, or empty run | — | `BLOCKED` |

`CLEAR_WITH_WAIVERS` is reported even when the suppressed finding was on an already-warning
verdict, i.e. when the waiver did not change the gate. That is honest: a reviewer looking at the
badge should learn "someone has accepted something here" without having to reason about whether
it mattered.

`Outcome.blocked()` stays `this == BLOCKED`, so every existing caller keeps working, and the
empty-run case still falls out of the same "no positive evidence" rule.

**Rejected: stamping the effective outcome onto the run at approval time.** It reads well until a
waiver expires, at which point the stored value is a lie. Deriving is what makes D3 free.

### D3 — Expiry is a `now` comparison at evaluation time, not a job

A waiver is *active* iff `revoked_at IS NULL AND expires_at > now`. `now` is read at the moment
the effective outcome is computed — on the approve request, on the vetting API read, on the
portal poll. So:

- an expired waiver stops suppressing on the very next evaluation, with no scheduler involved;
- the snapshot's effective outcome reverts to `BLOCKED` and approval is refused again;
- there is no window in which a stale computed value is trusted.

The **sweep** (`WaiverExpirySweep`, hourly) exists only so that expiry becomes a *ledger event*
rather than a silent state change: it selects waivers past their expiry whose
`expired_recorded_at` is null, appends `waiver-expired`, and stamps the column. It is idempotent,
carries no gate authority, and the gate is correct whether or not it ever runs — which is exactly
what makes it safe to include. Turning it off cannot open a hole; it can only make the ledger
quieter.

### D4 — Approval requires coverage of every blocking finding

`ApprovalService.approve(snapshotId, reviewer)` loses its `overrideReason` parameter entirely, and
`AdminController.ApproveRequest` is deleted (the endpoint takes no body). The gate becomes:

```
effect = waiverService.evaluate(snapshotId)
if snapshot is HELD and effect.outcome().blocked():
    throw VettingBlockedException(id, effect.blockingConnectors(), effect.blockingFindings())
```

`VettingBlockedException` gains the uncovered findings, and the `409` problem document gains an
`uncoveredFindings` array of `{ruleId, location, severity, connector}` beside the existing
`blockingConnectors`. That array is the reviewer's worklist: it is precisely the set of waivers
that must exist for the approval to succeed, which is the property that makes "cover every
blocking finding" actionable instead of a guessing game.

On success, every suppression that was in force is appended to the ledger as `waiver-applied` —
the "use" half of the lifecycle. A snapshot approved with a `CLEAR` outcome writes none.

**Migration**: there is no compatibility shim for `overrideReason`. Accepting it and ignoring it
would silently downgrade a caller that believes it overrode the gate; accepting it and honouring
it would keep the hole open. An unknown JSON field on a body-less endpoint is simply ignored by
the framework, so an old caller's request now succeeds only if the snapshot genuinely clears —
fail-closed, which is the correct direction for a caller to be surprised in.

### D5 — Where waivers surface

`GET /api/snapshots/{id}/vetting` grows three fields and keeps everything it had:

- `outcome` — now the **effective** outcome (this is the field the portal already renders, and
  the field that gates, so it must be the effective one);
- `recordedOutcome` — the raw run aggregate, so the evidence is still readable;
- `waivers` — the marketplace's waivers whose rule id appears in this run, active or not, each
  with `active`;
- `suppressed` — `{connector, ruleId, location, waiverId}` per finding currently suppressed.

The portal keys findings on `connector|ruleId|location` to badge them. Putting the suppression
list beside the findings rather than mutating `Finding` keeps the SPI record — which connectors
construct — free of gateway-side concepts.

Management endpoints:

| Endpoint | Purpose |
| --- | --- |
| `POST /api/snapshots/{id}/waivers` | create from a finding: marketplace and SHA come from the snapshot, so the caller cannot mis-scope it |
| `GET /api/marketplaces/{name}/waivers` | list, active and expired |
| `DELETE /api/waivers/{id}` | revoke |

Creation is snapshot-anchored rather than marketplace-anchored on purpose: the reviewer is looking
at a finding, and the endpoint derives `marketplace_id` and (for `snapshot` scope) `scope_value`
from the snapshot row. There is no request in which a caller names a marketplace and a SHA that
do not belong together.

### D6 — `connector-error` is waivable, and that is a documented sharp edge

An `ERROR` verdict carries a `CRITICAL` `connector-error` finding, so the uniform rule in D2 makes
it waivable. That is a real operational need (an external connector down for a day) and it is
strictly better than the alternative the blanket override offered, which waived it *plus*
everything else. It is called out with a `!!! warning` in the docs, because "we accepted that the
scanner did not run" is the single most consequential thing a reviewer can write here.

## Risks / Trade-offs

| Risk | Mitigation |
| --- | --- |
| A `path` waiver covers content added later under that path | Mandatory expiry bounds it; the docs push `snapshot` scope as the default and the portal pre-selects it |
| Waiving `connector-error` accepts "the scanner never looked" | Documented warning; the ledger entry names the rule, so it is greppable |
| Long expiries recreate the permanent hole | Out of scope to enforce a maximum; the marketplace waiver list shows expiry per waiver so a long one is visible next to the content |
| Approved-and-published content whose waiver later expires stays published | Explicitly documented as the boundary between this change and re-vetting (#24); the gate re-closes, the facade does not retract |
| Effective outcome computed per read costs a query per snapshot row in the portal table | The waiver list is per marketplace and small; the existing per-row vetting fetch already dominates |

## Migration Plan

Schema lands in `V1__init.sql` per repo convention (Testcontainers recreate it every run, and the
owner has not cut a released schema): `vetting_waivers` added, `vetting_runs.override_*` removed.

Behavioural migration for anyone running the branch: an approval that used to pass an
`overrideReason` now fails with `409` until waivers exist. The `409` body names exactly which
ones. The portal does that work for the reviewer in one dialog.

## Open Questions

None blocking. Two deferred by choice: a configurable maximum waiver lifetime, and separating the
waiver requester from the waiver approver (four-eyes). Both are additive to this model — a policy
check at creation and a second identity column — and neither changes the evaluation rule.
