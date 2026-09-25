# Proposal: actionable-vetting-findings

## Why

A trial deployment vetted the public skill repository `pbakaus/impeccable` (#490).
The approval gate raised 66 high findings, and all 66 were false positives. A
reviewer who meets that much noise learns to click past the gate, and then the
gate protects nothing. The findings were:

- **False positives.** The concealment rule fired on "do not silently overwrite
  it**. Show the user the file", because its negation reaches into the next
  sentence. It also fired on "do not show the user the helper's JSON output",
  which is a formatting instruction. The instruction-override rule fired on the
  command name `ignore-rule` and the flag `--all-values`.
- **Duplicates.** Vendored copies of the same files multiply every finding.
- **An unusable gate message.** The portal listed the rule name once per
  finding, 66 times, with no `path:line`.
- **"Waive…" reads as truncated text.** The scope labelled "This snapshot only"
  actually accepts the rule everywhere in the snapshot.
- **Unscanned files are hidden.** Files over the 1 MiB scan limit got 51
  informational rows, which bury the real findings. Meanwhile the passing
  verdict row did not say that anything went unscanned.
- **A bulk waive was asked for.**

The owner's decisions on the issue are settled. Files under `docs/` are not
exempted by path, because they are served to agents too and so are attack
surface; the fix is precision, not scope. A bulk waive is replaced by waiving
one collapsed finding group.

## What Changes

- **Precision.** The concealment rule matches only within one clause. Its
  verbs are verbs of telling (`tell`, `inform`, `mention`, `report`, `reveal`,
  `disclose`, `notify`, `alert`), not of displaying (`show`, `log`). Three
  explicit forms are added: "hide/conceal … from the user", "without telling
  the user", and "don't let the user know". The instruction-override words
  match only as whole words, never as part of a hyphenated identifier. The
  lines from the trial become regression fixtures. `prompt-injection` moves
  to version 2.
- **Collapsed groups.** The gateway stamps each finding with the git blob id of
  its file, read from the pinned tree and never taken from the vetter. The
  vetting API returns, beside each verdict's findings, `groups`: findings that
  share rule, severity, message, blob and line, with every location. The
  recorded run stays one row per location.
- **Group waivers.** A waiver may name a group's `content` (blob id) and
  `line`. It is then snapshot-scoped and covers only findings of that rule on
  that blob and line. A path scope with a group qualifier, or a malformed blob
  id, is refused.
- **Presentation.**
  - The refusal and the portal's blocking list name each group once, at its
    `path:line` locations. `uncovered` becomes one entry per group, and gains
    `locations`, `content` and `line`.
  - The waive button says "Waive finding" or "Waive all N locations".
  - The waiver form defaults to the group, and labels the wider scopes as
    wider.
  - Unscanned files become one informational entry per reason.
  - A verdict whose findings are all informational shows its coverage
    summary, which now states how many files were not scanned and why.

The API change is additive in schema. `uncovered` changes meaning: it now has
one entry per group rather than one per finding. The project is pre-1.0 and
this is declared in the PR.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`:
  - GW_VETTING_0040 — Prompt-injection markers do not fire on formatting
    instructions or command names
  - GW_VETTING_0041 — Findings on identical content are collapsed into one
    group with every location
  - GW_VETTING_0043 — Unscanned files are reported once per reason and stated
    in the coverage summary
  - GW_APPROVAL_0022 — The vetting refusal names each blocking finding group at
    its path:line locations
- `vetting-waivers`:
  - GW_VETTING_0042 — A waiver on a finding group covers exactly that group
  - GW_VETTING_0044 — The portal presents each finding group once with a waive
    action that says what it covers

## Impact

- **Backend:**
  - `vetting/`: `PromptInjectionVetter`, `SecretScanVetter`, `ContentRules`,
    `Finding`, new `FindingGroup`, `QuarantineSnapshot`, `VettingService`,
    `VettingRepository`, `Waiver`, `WaiverRepository`, `WaiverService`,
    `WaiverController`, `WaiverEvaluation`.
  - `approval/`: `VettingBlockedException`, `ApprovalService`.
- **Schema:** `V1__init.sql` gains `vetting_findings.content_id` and
  `vetting_waivers.content_id` and `content_line`, with checks.
- **API:** additive fields on `Finding`, `VerdictView.groups`, `FindingGroup`,
  `UncoveredFinding`, `WaiverRequest` and `WaiverView`.
- **Portal:** `vetting-report.tsx`, `vetting-flow.tsx`, `lib/vetting-flow.ts`
  and `name-collision-notice.tsx`, plus the e2e tainted fixture.
- **Docs:** the vetting concept page, the waiving-findings guide and the portal
  reference.
- **Stop rule:** there is no new package, estate object type, role, sweep or
  configuration leaf. A waiver gains an optional qualifier and a finding gains
  a derived field. Waivers stay deliberately API-only, as the estate guide
  already records.
