# Design: vetting-report-consistency

## Context

See proposal.md, Why. The portal reads one `VettingView`. Each verdict carries
`findings` (one per location), `groups` (findings on identical content
collapsed, GW_VETTING_0041), a stored `detail` string
(`"18 finding(s); worst high"`, written by `VettingRepository.detailOf`), and
the view carries `suppressed` (one entry per waived finding). The flow nodes
count `groups`; the vetter section prints `detail`; the headline sums groups.

## Decisions

### D1 — One wording, derived in the portal

Raw findings are the unit, and groups are named only when grouping collapsed
something: "3 findings" or "18 findings in 3 groups". One helper in
`lib/vetting-flow.ts` writes it, and the step card, the headline, the
"stopped at" reason and the vetter section all call it. The waiver count
already counts raw findings, so "5 findings accepted" stays in the same unit.

The vetter section stops printing the stored `detail` when it is the count form
(`N finding(s); worst S`), and prints the derived phrase with the worst severity
instead. Any other `detail` is the vetter's coverage summary, and it is still
shown. The stored string is left alone: it is persisted on the verdict and
compared when a re-vet decides whether a verdict changed, so rewording it is a
backend change with no reader benefit beyond this screen.

### D2 — "waived" is a state of its own, mirroring the server's rule

`WaiverEvaluation.effectiveState` recomputes a blocking verdict from the
findings no waiver covers. The view does not expose that per verdict, so the
portal mirrors the rule: a verdict recorded as fail, error or pending, with at
least one waived finding and no unwaived finding at high or above, reads
**waived**. A disabled or not-reached verdict never does: the server keeps
those as they are. The node uses the outcome's tone for "clear with waivers"
(warn, not destructive) and its own word, and the vetter section shows a
"waived" badge next to "recorded fail". The outcome's "Objecting" list uses the
same rule, so a waived vetter stops being named there.

Alternative: add an `effectiveState` field to the verdict view. Rejected for
now. It is new API surface for a value the portal can derive from what it already
receives. If the two rules ever diverge, the outcome badge is still the
server's, so the worst case is a node word that disagrees with the badge. That
is the bug being fixed here, not a new kind.

### D3 — Extra lines are parsed in the portal, from the message

Only the executable-surface vetter reports one finding for several lines. Its
location is `path:<first line>`, and the other lines exist only in the message,
as a trailing `(line N)` or `(lines a, b, c, d, e +K more)` written by
`ExecutableSurfaceVetter.lineList`. `lib/file-findings.ts` already parses
locations, so it also reads that trailing list. `FileFinding` gains `lines`,
which holds the location's line first and then every other listed line, with no
repeats. `SourceView` indexes a finding under each entry of `lines` instead of
`line`. That is the one loop in that file, so it stays compatible with the
`firstLine` numbering in #772. The "beyond the part shown" check keeps using
`line`.

Only a parenthesised list at the very end of the message is read, so a message
that mentions "line 3" in passing marks nothing extra. Lines hidden behind
"+K more" are not marked; the note on the first line still says how many more
there are.

Alternative: a structured `lines` field on the finding. Rejected: it needs a new
column on `vetting_findings` and a new API field, for a list the message already
carries.

### D4 — Finding rows are a two-column grid

The report rows, the step-detail rows and the outcome's blocking list each
became a flex row that wraps. A long message then fell to the left edge, under
the severity badge, while short messages sat beside the rule. Each list becomes a
grid of two columns: the checkbox and severity on the left, and everything else
on the right. The rows use `subgrid`, so the right column starts at the same
place on every row, and a wrapped message lines up under its rule.

### D5 — Parked: the matched text

The issue asks to show the matched excerpt beside each finding. No finding
carries one. `Finding.message` is defined never to echo the match, so that a
secret rule cannot leak the secret, and there is no excerpt column. Showing the
line from the file instead would mean a file read per finding row. It would also
put a secret-rule match on screen unredacted in the report, which is the one
thing the issue says must not happen. A redacted excerpt needs new storage and a
new API field. The complexity stop rule parks it. Each row already links to its
line in the file view (GW_APPROVAL_0030), where the reviewer reads the match in
context, behind the same approver gate.

## Risks / Trade-offs

- [The vetter's message format changes] → The extra lines are no longer
  marked; the first line still is, so the file view falls back to today's
  behaviour. A test pins the format the vetter writes.
- [The portal's waived rule diverges from the server's] → see D2. The outcome
  badge stays the server's answer.
