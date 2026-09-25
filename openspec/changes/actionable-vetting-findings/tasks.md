# Tasks: actionable-vetting-findings

The group waiver is a way past the approval gate, so this change is worked under
`.claude/skills/old-coder`. The adversarial tests are shown failing against a
throwaway mutant, and the precision fixtures against the old rules. The results
are recorded in `evidence.md`.

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_VETTING_0040, GW_VETTING_0041, GW_VETTING_0042,
  GW_VETTING_0043, GW_VETTING_0044 and GW_APPROVAL_0022, each with its SVC.
  Only the ids reserved for this change are used.

## 2. Precision (SVC_GW_VETTING_0040)

- [x] 2.1 `PromptInjectionPrecisionTests`: the trial lines raise nothing; every
  payload raises one finding of its rule at `path:line`; a line matching
  several concealment forms is one finding. Shown failing against the old
  rules.
- [x] 2.2 `PromptInjectionVetter`: clause-bounded concealment on verbs of
  telling, plus the hide, without-telling and let-know forms; whole-word
  override words; version 2. `ContentRules.apply` gives one finding per rule
  id and line.

## 3. Content identity and groups (SVC_GW_VETTING_0041)

- [x] 3.1 `Finding.content` and `line()`; `FindingGroup.of`;
  `QuarantineSnapshot.identify`, called in `VettingService.run` before the
  stop test.
- [x] 3.2 `vetting_findings.content_id`; `VerdictView.groups`, derived.
- [x] 3.3 Tests: `FindingGroupWaiverTests` (collapse and never-collapse) and
  `QuarantineSnapshotTests` (the blob comes from the tree, a forged blob is
  replaced, no file means null).

## 4. Group waiver (SVC_GW_VETTING_0042)

- [x] 4.1 `Waiver.content`/`line`, constructor invariants and `covers`;
  `vetting_waivers.content_id`/`content_line` with checks;
  `WaiverRepository`, `WaiverService.create` (validation and ledger detail),
  `WaiverController` request and view.
- [x] 4.2 Adversarial tests: another blob, another line, another rule,
  another snapshot, no blob, lapsed, revoked, a path scope, malformed blob ids
  (`FindingGroupWaiverTests`); end to end through the API with the ledger
  (`WaiverTests.aGroupWaiverCoversEveryCopyOfItsContentAndNothingElse`).

## 5. Refusal and worklist (SVC_GW_APPROVAL_0022)

- [x] 5.1 `WaiverEvaluation.UncoveredFinding` per group with `locations`,
  `content`, `line` and `describe()`; `VettingBlockedException` and the
  override capture use it.
- [x] 5.2 Tests: twelve copies are one entry, ten spelled out and "(+2 more)";
  the refusal names the uncovered file at `path:line`.

## 6. Unscanned files (SVC_GW_VETTING_0043)

- [x] 6.1 `ContentRules.notScanned` and `skipped`; both pattern vetters
  aggregate; the verdict row and the ledger show the summary for an
  informational-only verdict.
- [x] 6.2 `UnscannedFilesTests` and `VettingTests.aPassThatSkippedFilesSaysSoOnItsRowAndInTheLedger`.

## 7. Portal (SVC_GW_VETTING_0044, SVC_GW_APPROVAL_0022)

- [x] 7.1 Regenerate `openapi.json` and `types.gen.ts`.
- [x] 7.2 `vetting-report.tsx`: group rows, waive labels, a form defaulting to
  the group, a blocking list with locations. `vetting-flow.tsx` and
  `lib/vetting-flow.ts`: groups in the drawer and in the counts.
  `name-collision-notice.tsx`: "Waive name collision".
- [x] 7.3 Unit tests (`vetting-report.test.tsx`), the `VendoredCopies` story,
  and e2e (`a_vendored_copy_is_one_group_with_every_location_and_one_waiver_covers_it`,
  with a vendored copy in the tainted fixture).
- [x] 7.4 `/impeccable audit` and `harden` on the vetting report; findings
  fixed or dismissed in the PR.

## 8. Docs and measurement

- [x] 8.1 Vetting concept page, waiving-findings guide, portal reference.
- [x] 8.2 `VettingPrecisionMeasurement` (opt-in, local clone only): before
  and after numbers on the trial repository, in the PR.

## 9. Gates, evidence, archive

- [x] 9.1 All gates; manual mutation (`mutants.sh`); `evidence.md`.
- [ ] 9.2 `/opsx:archive`, with the synced `openspec/specs/**` committed in the
  archive commit.
