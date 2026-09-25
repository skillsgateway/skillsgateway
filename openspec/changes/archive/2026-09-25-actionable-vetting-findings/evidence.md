# Evidence: actionable-vetting-findings (Tier 3)

- **Spec approval:** not obtained (autonomous run). The acceptance criteria are
  `proposal.md`, `design.md` and the SVCs of GW_VETTING_0040 to
  GW_VETTING_0044 and GW_APPROVAL_0022. They were committed at `f3f802a7`,
  before the implementation commits, and are the artifacts to review after
  the fact. Confidence is claimed accordingly.
- **Source state:** `1d111a0f` on `feat/actionable-vetting-findings`. All gates
  below and the mutation run were run over that commit, after the last edit.
- **Entry points:** the project gates below, and
  `openspec/changes/actionable-vetting-findings/mutants.sh`.
- **Independent verification:** not performed.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 805, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  21 passed (21)
[INFO]       Tests  184 passed (184)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:05 min

$ (cd src/main/frontend && pnpm test:stories)   # after rm -rf node_modules/.cache/storybook node_modules/.vite
 Test Files  14 passed (14)
      Tests  68 passed (68)

$ (cd src/main/frontend && pnpm e2e)
  22 passed (1.0m)

$ uvx --from reqstool==0.12.0 reqstool status local -p docs/reqstool
281/281 complete · 0 incomplete · PASS

$ openspec validate --all --strict               # also npx @fission-ai/openspec@1.3.1, same result
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
exit 0
```

Failures along the way, and how each was resolved:

- **The first `verify` failed on `OpenApiContractTests`.** `openapi.json` had
  been regenerated before a schema description changed. It was regenerated
  again in `9cc0e728`.
- **The second `verify` failed on Spotless.** One statement in
  `FindingGroup` was unformatted. It was formatted in `0abd183e`.
- **The first `e2e` run failed on the new test.** The test expected the
  locations in the order the files were written, but the gateway lists them in
  git tree order (`copy/` before `hello/`). The expectation was corrected in
  `1d111a0f`. The code did not change.
- **The story tests passed on the first run.**
- **`verify` passed on its third run,** over `1d111a0f`.

## Spec → test mapping

| SVC | Tests | Status |
|---|---|---|
| SVC_GW_VETTING_0040 — The lines that raised false positives raise nothing, and the payloads the rules exist for still fail | `PromptInjectionPrecisionTests` (3) | pass |
| SVC_GW_VETTING_0041 — Copies of one blob collapse into one group, and nothing else does | `FindingGroupWaiverTests.findingsOnTheSameBlob…`, `…findingsDifferingIn…NeverCollapse`, `QuarantineSnapshotTests.findingsAreIdentifiedByTheBlobThePinnedTreeHoldsAtTheirPath`, `WaiverTests.aGroupWaiverCoversEveryCopyOfItsContentAndNothingElse` | pass |
| SVC_GW_VETTING_0042 — A group waiver covers every copy of its content and nothing else | `FindingGroupWaiverTests` (5 waiver tests: covers the group, never covers differing content, leaves other groups blocking, lapsed or revoked covers nothing, cannot be built path-scoped or malformed; plus the rule-alone waiver unchanged), `WaiverTests.aGroupWaiverCovers…` (API, 400s, ledger `content=`) | pass |
| SVC_GW_VETTING_0043 — Unscanned files are one entry per reason, and a pass that skipped files says so | `UnscannedFilesTests` (3), `VettingTests.aPassThatSkippedFilesSaysSoOnItsRowAndInTheLedger`, `vetting-report.test.tsx: a_pass_that_skipped_files_says_so_in_one_entry` | pass |
| SVC_GW_VETTING_0044 — A vendored group is one row with every location and one waive action defaulting to the group | `vetting-report.test.tsx` (2), story `Vetting/VettingFlow/VendoredCopies`, e2e `a_vendored_copy_is_one_group_with_every_location_and_one_waiver_covers_it` | pass |
| SVC_GW_APPROVAL_0022 — The refusal names each blocking group at its locations | `FindingGroupWaiverTests.theWorklistHasOneEntryPerGroupNamingEveryLocation`, `WaiverTests.aGroupWaiverCovers…` (refusal message), `vetting-report.test.tsx`, the same e2e test | pass |

Negative constraints:

- **Existing SVC tests are unchanged in their assertions.** Two call sites
  gained two `null` arguments for the new repository parameters.
- **A waiver without the group qualifier behaves as before.** This is held by
  `aWaiverOnTheRuleAloneIsUnchangedByGroups` and by the unchanged
  `WaiverTests` suite.

## RED

- **Precision.** `PromptInjectionPrecisionTests` was run against the old
  `PromptInjectionVetter` (`git show main:…`) and failed 2 of 3. The trial
  lines raised findings, and "without telling the user" was not caught.
- **Group waiver, identity, refusal and unscanned files.** These tests were
  written after their implementation. Their ability to fail is shown by the
  mutation run instead: every mutant below is killed by the test named for it.
  That is a weaker order than test-first, and it is recorded here as such.

## Mutation (manual, `mutants.sh`, over `1d111a0f`)

```console
M1-content-ignored killed
M2-line-ignored killed
M3-vetter-claim-trusted killed
M4-path-scoped-group killed
M5-blobless-collapse killed
M6-clause-unbounded killed
M7-refusal-unbounded killed
M8-unscanned-per-file killed
M9-service-accepts-path-group killed
M10-form-defaults-wide killed
all mutants killed
```

The runner fails closed. A mutant that does not apply exactly once, a run
with no test report, a kill by a test other than the one named, or a dirty
tree after the restore each stop it with a nonzero exit.

On its first run, `M6` (the concealment gap no longer stops at a clause break)
**survived**. With `show` removed, no fixture exercised a sentence-crossing
match on a verb of telling. Two fixtures of that shape were added in
`80632e53`, and every run since has killed it.

## Failure model (Tier 3)

| Mode | Layer that catches it |
|---|---|
| A group waiver covers other content (another blob, line, rule or commit) | `aGroupWaiverNeverCoversContentThatDiffers`, M1, M2 |
| A vetter forges a blob id to widen a group waiver | `identify` replaces it; `QuarantineSnapshotTests`, M3 |
| A group waiver outlives its commit (path scope) | record invariant, service validation and table `CHECK`; M4, M9 |
| Findings with no blob collapse together | M5 |
| Precision regresses, or loses the payloads it exists for | `PromptInjectionPrecisionTests`, M6 |
| The refusal or ledger line grows without bound | a cap of 10 plus a count, M7; 20 for unscanned entries, M8 |
| The portal defaults to a wider acceptance | M10 |

**Known limits.**

- "Do not show the user X" is no longer a concealment finding. See
  `design.md`, decision 6.
- Collapsing helps only with byte-identical copies.
- The database `CHECK`s on `vetting_waivers` have no test of their own. They
  are a backstop behind the constructor and the service, which are tested.

## Measurement on the trial repository

`VettingPrecisionMeasurement` (opt-in, `-Dvetting.measure.repo=…`, run on a local
clone only) over `pbakaus/impeccable` at `9d715cc4f5564a990ca8345abfdd5df6dc9b41c8`:

| | `main` | this change |
|---|---|---|
| prompt-injection high findings | 66 (63 concealment, 2 override, 1 credential path) | 1 (`credential-path-reference` at `docs/CLI-CONTRACT.md:821`, `id_rsa` in a regex literal) |
| … as groups (same blob and line) | 51 | 1 |
| secret-scan informational entries | 51 (one per file) | 2 (22 over the size limit, 29 binary) |
| secret-scan pass summary | "(51 skipped as binary or oversize)" | "51 file(s) not scanned (22 over the size limit, 29 binary)" |

The `main` column came from the same harness logic, run in a scratch worktree
of `main` and never committed. The one remaining finding is a genuine
reference to a credential file name. It is judged, and waived if appropriate,
in one step.

## Portal audit (`/impeccable audit`, then `harden`)

The detector reported no findings on `vetting-report.tsx` or `vetting-flow.tsx`.

| Dimension | Score |
| --- | --- |
| Accessibility | 3 |
| Performance | 4 |
| Responsive | 3 |
| Theming | 4 |
| Integrity | 4 |
| **Total** | **18/20** |

- **Fixed (P2):** the scope options were truncated in a half-width select.
  The select is now full width.
- **Fixed (P2):** long `path:line` strings could overflow the row on a narrow
  viewport. They now break anywhere.
- **Dismissed (P3):** `list-none` drops list semantics in Safari. The pattern
  is repository-wide, and a fix belongs in its own change.
