# Design: add-continuous-revetting

## Context

`add-vetting-chain` built the gate and `add-vetting-waivers` built the one sanctioned way past it.
Both act at exactly one instant: the approval. After that the gateway forgets. An estate whose
approvals are never re-examined is a set of assertions nobody has checked since the day each was
made — and the waivers change made that concrete by shipping an expiry that closes the *gate* and
provably does not retract the content approved under it.

`ARCHITECTURE.md` §5 already specified the destination: *"marking a snapshot revoked (a) removes
it from every virtual marketplace, (b) makes the façade refuse its mirror refs, (c) produces the
blast-radius report from the ledger, and (d) optionally pushes a fleet-managed settings change to
force uninstall."* This change implements (a)–(c) and drives them from re-vetting rather than only
from a person pressing a button. (d) stays Phase 3.

## Goals / Non-Goals

**Goals**

- Fresh evidence about approved content, on a schedule whose cost is proportional and bounded.
- A retraction path that a git client can observe, not merely a database column.
- A default that cannot surprise an operator into an outage.
- A retraction that is answerable: why, who is affected, and how to undo it.

**Non-Goals**

- Webhook-triggered scanner/advisory feed integration. The built-in connectors have no external
  feed; this is stated plainly in the docs rather than implied by a "feeds" abstraction with one
  implementation and no source.
- Per-team notification channels. The gateway knows principals, not teams; mapping the two is the
  identity provider's knowledge. v1 emits the lifecycle event and reports the principals.
- Fleet force-uninstall (ARCHITECTURE.md §5(d), Phase 3).
- Auto-promotion of clean re-vets, cooling-off windows, and multi-ref publication.

## Decisions

### D1. Re-vetting produces a new run; it never edits the previous one, and the snapshot's state is untouched by the run itself

`vetting_runs` was already append-only with a `trigger` column reserved for exactly this. A
re-vetting pass inserts a run with `revet-scheduled` or `revet-manual`; a snapshot's vetting
history is the list of its runs. Only the *judgement* about a run moves the snapshot, and only
`RevetService` makes it — so `VettingService` stays the one place the chain executes, with no
knowledge of what its answers imply for served content.

The run also records `chain` — `connector@version` for each connector in order. It is the cheapest
possible answer to the question continuous re-vetting invents: when a snapshot that cleared last
month is blocked today, did the content change, or did the connector?

### D2. Oldest-run-first, bounded batches, and a cadence — not "everything, every tick"

Three knobs (`interval`, `cadence`, `batch-size`) rather than one, because they answer different
questions: how often the sweep wakes, how stale evidence has to be before it is worth redoing, and
how much work one pass may do. Ordering by the snapshot's newest run ascending (nulls first) makes
a bounded batch *fair*: every approved snapshot is reached in rotation, so a large estate delays
each snapshot rather than starving an arbitrary subset of them.

Only live `approved` snapshots are eligible. Nothing else is being served, so nothing else could be
retroactively quarantined, and re-vetting soft-deleted content would spend the budget on snapshots
on their way out.

### D3. The *effective* outcome decides, so an accepted risk cannot quarantine content

`WaiverEvaluation.evaluate` is reused unchanged. If a waiver were ignored here, an operator could
clear the approval gate with a written, expiring acceptance and have the very next sweep revoke the
snapshot anyway — the waiver system would be decorative. As a direct consequence, an expired waiver
*is* a violation on the next re-vet, which is the "expired waivers re-trigger the violation path"
behaviour issue #28 deferred to this change.

### D4. Warn is the default; enforce is opt-in

`mode: warn` records and announces the violation in full — ledger entries with the objecting
connectors, the rules and one entry per affected identity, the `snapshot.revet_violation` event,
the portal — and changes nothing about publication. `mode: enforce` additionally revokes.

The default follows the same principle as `retention.enabled: false`: **the gateway never destroys
or retracts its own served content because of an upgrade.** Auto-quarantine pulls skills out from
under every team that fetched them, and the person who deployed the upgrade is not the person who
chose that policy. Warn mode is also the measurement instrument — the `revet-violation` entries
name exactly the identities `enforce` would have cut off, so the blast radius is knowable before it
is caused.

The sweep itself defaults to **enabled**, because producing evidence is not the dangerous half.
Re-running read-only scanners over pinned content writes a run and touches nothing else.

### D5. A connector error never revokes, and this does not weaken fail-closed

`RevetVerdict.classify` splits a blocked run into VIOLATION (a connector objects to the content)
and INCONCLUSIVE (it blocks only because a connector errored, timed out, has not answered, or —
the misconfiguration case — no connector objected to the content at all).

Approval and re-vetting ask different questions of the same evidence. Approval asks *may this be
published?*; failing closed on everything costs one delayed review, paid by one reviewer, with
nothing yet being served. Re-vetting asks *must this be retracted?*; failing closed on everything
costs every consumer of the content. An ERROR is evidence about the **scanner**, not the content —
nothing in the snapshot changed — and a shared connector outage would revoke an entire estate
simultaneously, converting a scanner bug into an outage of exactly the content the gateway exists
to serve. That is a worse security outcome, not a stricter one: an operator whose fleet goes dark
from a scanner bug disables the sweep, and then nothing is re-vetted at all.

Nothing that *publishes* is loosened. The recorded run stays blocked, so an inconclusive answer
still refuses approval, re-approval and publication of that snapshot. The only thing declined is
retracting already-approved content on evidence that says nothing about that content. The
inconclusive run is recorded and announced rather than swallowed.

Classification reads the **recorded** verdict states, not the post-waiver ones, because
`Verdict.error` attaches a `CRITICAL connector-error` finding: re-deriving state from residual
findings would turn every unwaived ERROR into a FAIL and erase the distinction. A waiver can only
remove objections, so a connector still blocking whose recorded answer was ERROR is a blocking
error.

### D6. `revoked` is a state of its own, and unpublishing removes both refs

Not a return to `held`: the difference between "never served" and "served, then taken back" is the
whole point to everyone reading it, and the fetch ledger can answer *to whom*. Not a soft delete
either — retention's marks are orthogonal and deliberately do not touch `state`.

`GitStorage.unpublish` is the exact inverse of publication and removes **two** refs.
`refs/heads/main` — only when it still resolves to this snapshot, so revoking one that a later
approval already superseded does not take the marketplace down with it. And `refs/snapshots/<sha>`,
which `ApprovalService` copies alongside main and which upload-pack advertises in its own right:
leaving it would keep the revoked commit fetchable by SHA by everyone who ever cloned it. The
adversarial test asserts both, through a real `git` binary.

Quarantine is untouched. Nothing is re-ingested, and nothing is deleted.

The write order is the safety property: run recorded → violation in the ledger and on the wire →
state transition → refs removed. A crash anywhere leaves recorded content that is still served,
never retracted content nobody can explain. The `UPDATE ... WHERE state = 'approved'` makes the
transition the concurrency token, so two overlapping passes cannot both revoke and both unpublish.

### D7. The way back is the ordinary approve endpoint — there is no un-revoke

`revoked → approved | rejected` runs through `SnapshotRepository.decide`, behind the same
effective-vetting gate every approval passes. So re-publishing requires the violation to have been
waived (scoped, justified, expiring) or fixed by re-ingestion, and the transition records a fresh
reviewer and timestamp. `decided_by`/`decided_at` and `revoked_by`/`revoked_at` are separate
columns so the approval that was retracted survives the retraction; the decision clears the
revocation marks so a re-published snapshot never carries a marker that no longer holds.

An `approved` snapshot still cannot be re-decided by a caller. The only exit from `approved` is a
revocation the gateway makes.

### D8. Retention treats `revoked` like `rejected`, decided rather than inherited

Retention's guard was categorical — `state <> 'approved'` — so a new state would have become
deletable silently. The queries now name the deletable states (`'held', 'rejected', 'revoked'`), so
a future state has to be added on purpose. `revoked` is admitted: it is not being served, so
deleting it destroys nothing anyone could fetch. It is reachable by the `superseded` criterion and
by an administrator, never by `held-max-age` (which names `held`), and the `min-idle` veto still
applies — which is what keeps a recently-revoked snapshot around while the consumers that fetched
it before the revocation are still recent.

### D9. Blast radius is a ledger query, not new bookkeeping

`fetch_log` has recorded every facade fetch since the first one. `fetchersOf(sha)` counts only
`upload-pack` entries: a ref advertisement means a client asked, not that it received anything, so
counting `info-refs` would name teams that never got the content and bury the ones that did. The
violation writes one ledger entry per affected identity, so the blast radius is readable from the
ledger alone rather than from a query somebody has to think to run.

## Risks / Trade-offs

- **Sweep cost on a large estate.** Bounded by `batch-size` per `interval`; the trade-off is
  coverage latency, which is explicit and computable from the two knobs.
- **A misconfigured `enforce` could revoke widely.** Mitigated by the warn default, by the
  inconclusive rule (a broken chain cannot cause it), by the requirement that the effective
  outcome — waivers included — objects, and by the documented "run warn for a cycle first".
- **An ERROR that is really a signal is ignored.** Accepted, and bounded: the run is recorded and
  announced, and the snapshot cannot be approved or re-approved while it stands.
- **Revoked-but-still-published on an I/O failure.** The one case where the record and the wire
  disagree. Logged at ERROR and written to the ledger as `snapshot-unpublish-failed`; it needs a
  person, and the code says so rather than retrying into an inconsistent state.

## Migration Plan

Schema folded into `V1__init.sql` per project convention. No data migration: existing snapshots
keep their states, `revoked_at`/`revoked_by`/`chain` are null, and a snapshot with no prior run
sorts first in the sweep queue, which is the correct fail-closed order. Upgrading changes nothing
observable until an operator sets `mode: enforce`.

## Open Questions

None blocking. Deferred deliberately: webhook-triggered feed integration, per-team notification
channels, and fleet force-uninstall.
