# Design: clearer waive action and multi-select waivers

## Context

`vetting-report.tsx` renders one `GroupRow` per finding group. A blocking,
unwaived group shows a waive button that opens an inline `WaiveForm`. That
form posts one waiver per submit to `POST /api/v1/snapshots/{id}/waivers`, and
its scope defaults to the group (`content` + `line`). `WaiveForm` is reused by
`name-collision-notice.tsx` with `snapshotOnly`.

The owner chose to do this in the portal alone (#597): no batch endpoint.

## Goals / Non-Goals

**Goals:** a label that is not mistaken for truncated text; a coverage count
shown before submit; one justification and expiry applied to many groups.

**Non-Goals:** atomic all-or-nothing recording, multi-select across snapshots,
and path- or rule-wide scopes in the bulk form.

## Decisions

- **Bulk waivers are group waivers only.** Each selected group is waived as
  that group (`scope: snapshot`, `content`, `line`), which is the narrowest
  acceptance, as GW_VETTING_0044 makes it the default. Offering "every finding
  of these rules" in bulk would widen several acceptances at once, the thing
  the group waiver (GW_VETTING_0042) exists to avoid. A group with no
  `content` (a finding not tied to a blob) cannot be group-waived, so it gets
  no checkbox and keeps its single-waive action.
- **Sequential posts, partial success reported.** The portal posts one request
  per selected group, in order, and keeps going after a refusal. The toast
  says "Recorded N of M waivers" and names each refused group's rule and the
  server's message. Every recorded waiver is valid and listed under "Accepted
  risks", where it can be revoked. A batch endpoint would make this atomic.
  The owner rejected that as new API surface for a case in which each waiver
  stands on its own.
- **Selection lives in `VettingReport`.** Groups span verdict cards, so the
  selected set is held in the report (keyed by vetter, rule, content and
  line) and handed down. A selection bar above the verdicts appears once
  something is selected. It shows "N groups, M findings selected", with
  "Waive selected…" and "Clear". Groups that a refetch reports as waived drop
  out of the selection.
- **Coverage count in the single form.** For the group scope the count is the
  group's location count. For the rule-across-snapshot scope it is the number
  of findings of that rule in the run, across all vetters, because a snapshot
  waiver matches by rule. For path scope it is the findings of that rule under
  that path in this run. The single form therefore takes the run's groups to
  count from. The name-collision use passes its own single finding.
- **Label text.** The visible label is `Waive <rule>…` or
  `Waive <rule> at N locations…`, and the aria-label keeps naming the first
  location. A long rule id wraps rather than truncates: the button gets
  `whitespace-normal`.

## Risks / Trade-offs

- [A partial failure leaves some groups waived] → each is a valid,
  individually revocable waiver; the toast names what failed, and those groups
  stay selected so the reviewer can retry.
- [A selection of many groups sends many requests] → a snapshot's blocking
  groups are tens, not thousands; the requests are sequential to keep ledger
  order readable and the server unhurried.
- [A coverage count could disagree with the server's matching] → the count
  uses the same keys the server matches on: rule for snapshot scope, rule and
  path prefix for path scope, rule, blob and line for a group.
