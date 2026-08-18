# Design: add-minimum-release-age

## Context

`ApprovalService.approve` is the only path from quarantine to served content.
It already runs two preconditions before the state transition: the state
machine (`Snapshot.decidable()`), and the fail-closed effective vetting outcome
(`WaiverService.evaluate`, GW_0041). Both refuse before anything is decided or
published, and the vetting refusal travels as `VettingBlockedException` →
409 problem document carrying the reviewer's worklist.

`snapshots` rows are created by `IngestionService.ingestLocked`, which looks the
`(marketplace_id, sha)` pair up first and **returns the existing row unchanged**
when the same commit is ingested again — poll-driven re-ingestion included. So
`snapshots.created_at` already has the property the control needs: the instant
the gateway first saw that commit, immune to re-pushes.

## Goals / Non-Goals

**Goals:** a global cooling-off window before approval; a clock the upstream
cannot influence; no scheduler; a reviewer who can see when the window opens.

**Non-Goals:** per-marketplace or per-tier ages (#12, CEL), an audited
per-approval override, auto-promotion, and any age gate on rejection,
revocation or serving.

## Decisions

1. **A precondition on the approval gate, not a vetting connector.** A verdict
   is point-in-time evidence about content; "too young" is a fact about *now*.
   As a connector it would record a FAIL that kept blocking after the age had
   passed, until some later re-vet happened to run — turning a self-clearing
   wait into an operator task. Evaluated per request, the gate needs no
   scheduler, stores nothing, and clears itself.

2. **The clock is `snapshots.created_at`, no new column.** It is written once at
   first sighting and never rewritten: re-ingestion of the same commit short-
   circuits to the existing row. The commit's own committer date is never read
   by the gate — a backdated commit must not defeat the control, and the test
   suite plants a commit dated 400 days ago to prove it does not.

   The one way the clock can restart is a *purge*: retention can permanently
   remove a non-approved snapshot row, and a later re-ingestion of the same
   commit is then a genuinely new first sighting. That is correct rather than a
   hole — the gateway no longer holds any record of having seen it — and a
   purge is only reachable long after the cooling-off window would have
   elapsed anyway.

3. **Age is checked after the vetting gate.** Both refusals are 409 and either
   alone is disqualifying, so the order only decides which one a reviewer is
   told about first. The vetting refusal is a worklist that can be worked *now*
   (record a waiver per blocking finding); the age refusal is a wait. Reporting
   the actionable one first lets the waivers be recorded during the cooling-off
   window instead of after it.

4. **Inside the `decidable()` branch.** A snapshot that is neither held nor
   revoked is unapprovable for a reason that has nothing to do with age, and
   `SnapshotRepository.decide` already answers that with the invalid-transition
   409. Rejection is not gated at all: refusing to let a reviewer say "no" to
   suspicious content quickly would be the opposite of the control's purpose.

5. **`0` means off, and it is the default.** A zero or negative duration
   disables the gate entirely — no eligibility computation, no ledger noise —
   so upgrading a deployment changes nothing until an operator opts in.

6. **The comparison is `age >= minimum`, and the boundary is proven directly.**
   The rule is a pure function of `(firstSeen, now, minimum)`, so the
   exactly-at-the-boundary case is verified by evaluating it at
   `firstSeen + minimum` rather than by racing a real clock: eligible at that
   instant, refused one nanosecond earlier.

7. **Eligibility is readable, and it is the same code the gate runs.**
   `GET /api/snapshots/{id}/release-age` returns the same `Eligibility` value
   the gate decides on, so the portal cannot disagree with the server about
   whether a snapshot may be approved. Durations cross the wire as whole
   seconds (`ageSeconds`, `remainingSeconds`, `minimumReleaseAgeSeconds`) plus
   the absolute `eligibleAt`, so no client has to interpret a serialized
   `Duration`; the portal formats the human "eligible in 2d 4h" itself.

8. **Both outcomes reach the ledger.** The allowed approval carries the age it
   was approved at on the existing `snapshot-approved` entry's `detail`; the
   refusal appends its own `snapshot-approval-refused` entry with the age and
   the remaining time. Every decision — and every attempt — is therefore
   reconstructible from the ledger alone, which is what makes the control
   auditable rather than merely present.

## Risks / Trade-offs

- **An urgent upstream fix waits too.** Accepted, and named in the docs: the
  break-glass is a configuration change, which is deployed and reviewable,
  rather than a button that would need its own audit story and its own abuse
  analysis. A per-approval audited override is a deliberate follow-up.
- **A newly registered marketplace's first snapshot waits.** Accepted: a
  "first ever" exemption is a special case an attacker can arrange to land in.
- **Clock skew between gateway instances** can make one instance eligible
  moments before another. Immaterial at the granularity operators configure
  (hours to days), and both instances agree once the window has clearly passed.
