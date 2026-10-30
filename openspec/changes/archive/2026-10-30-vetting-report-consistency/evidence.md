# Evidence: vetting-report-consistency

Source state: `90078d56`, on `main` at `b1155dfa`. The gate results below come
from one fresh run after the last code edit (the review fixes), on 2026-10-30.
The commit after it adds only this file. The first run was on `8e9f93d3`.

Tier 1: a portal-only change. It touches no trust boundary, API, schema or
configuration.

## Spec → tests

| Scenario | Test | Seen failing first |
|---|---|---|
| SVC_GW_VETTING_0074: step, headline and stop reason say "18 findings in 3 groups", and "2 findings" when nothing collapsed; with mixed severities the stop reason names the worst ("18 findings in 3 groups, worst high") rather than calling every finding high | `vetting-flow.test.ts › the_step_headline_and_stop_reason_count_findings_and_groups_in_one_wording` | yes: `expected '3 findings' to be '18 findings in 3 groups'`; the review fix: `expected '… found 18 high findings in 3 groups' to be '… found 18 findings in 3 groups, worst high'` |
| SVC_GW_VETTING_0075: the vetter section says "5 findings in 2 groups; worst high", not the stored `finding(s)`, and a pass keeps its coverage summary | `vetting-report.test.tsx › the_vetter_section_counts_findings_in_the_chains_wording` | yes: text not found |
| SVC_GW_VETTING_0076: every blocking finding waived → `waived` in warn tone and not objecting; one left → `fail` and objecting; another vetter's waiver, not-reached and disabled never read waived | `vetting-flow.test.ts › a_failed_vetter_with_every_blocking_finding_waived_is_waived_and_not_objecting` | yes: `expected 'fail' to be 'waived'` |
| SVC_GW_VETTING_0077: the vetter section shows `waived` and `recorded fail`, and no `fail` badge | `vetting-report.test.tsx › a_vetter_whose_blocking_findings_are_all_waived_reads_waived_with_its_recorded_verdict` | yes: no step named `secret-scan waived` |
| SVC_GW_VETTING_0078: `(line N)`, `(lines a, b)` and `(lines … +K more)` at the end are read; a mid-sentence mention and `(no lockfile)` are not; the location line comes first | `file-findings.test.ts › a_findings_listed_lines_are_read_from_the_end_of_its_message` | yes: `[]` instead of `[4]` |
| SVC_GW_VETTING_0079: lines 3 and 5 of one finding are both marked and described | `source-view.test.tsx › every_line_a_finding_lists_is_marked_and_described_by_it` | yes: line 5 had no `data-severity` |

One existing unit test, which carries no SVC, encoded the old behaviour:
`vetting-flow.test.ts › keeps a waived finding's vetter at the verdict it
reached` expected `fail`. It now expects `waived` in warn tone. The two story
assertions that pinned group counts and `fail` for a waived vetter were updated
the same way (`VettingFlow/WithWaivers`, `VettingFlow/VendoredCopies`).

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar, UI gate | `./mvnw clean verify` | `Tests run: 1179, Failures: 0, Errors: 0, Skipped: 9` · vitest `Tests 240 passed (240)` · `BUILD SUCCESS` (08:47) |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 115 passed (115)`. The first run hit the known "Failed to fetch dynamically imported module" harness flake on `app-layout.stories.tsx` and `edit-marketplace-url.stories.tsx`, which this change does not touch. Clearing `node_modules/.cache/storybook` and `.vite` and rerunning passed. |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.9m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | in code 351/351 verified · 1203 tests passed, 0 failed · 0 SVCs missing tests · exit 0 |
| OpenSpec | `openspec validate --all --strict` | `Totals: 29 passed, 0 failed (29 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 2.60 seconds` |
| API contract | no backend change | `openapi.json` unchanged |
| Supply chain | no dependency added or changed | — |

## Known limits

- A line hidden behind "+K more" in a long line list is not marked (design D3).
- The waived state mirrors `WaiverEvaluation.effectiveState` in the portal
  (design D2). The outcome badge is still the server's.
- Parked: showing the matched text (design D5).
