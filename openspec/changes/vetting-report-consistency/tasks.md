# Tasks: vetting-report-consistency

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_VETTING_0070, GW_VETTING_0071, GW_VETTING_0072 and SVC_GW_VETTING_0074–0079 in `docs/reqstool/`.

## 2. One count wording (GW_VETTING_0070)

- [x] 2.1 `vetting-flow.test.ts`: node meta, headline and stopped-at reason say "18 findings in 3 groups" for a verdict of 18 findings in 3 groups, and "3 findings" when nothing collapsed (`@SVCs SVC_GW_VETTING_0074`). Watch it fail first.
- [x] 2.2 `vetting-report.test.tsx`: the vetter section says the same phrase and not the stored `finding(s)` detail; a coverage-summary detail is still shown (`@SVCs SVC_GW_VETTING_0075`). Watch it fail first.
- [x] 2.3 `findingCount` in `lib/vetting-flow.ts`, used by the node, headline, reason and `VerdictCard` (`@Requirements GW_VETTING_0070`).

## 3. Waived state (GW_VETTING_0071)

- [x] 3.1 `vetting-flow.test.ts`: a fail verdict whose every high finding is suppressed reads "waived" in warn tone and is not objecting; one with a high finding left stays "fail"; a not-reached verdict never reads waived (`@SVCs SVC_GW_VETTING_0076`). Watch it fail first.
- [x] 3.2 `vetting-report.test.tsx`: the vetter section shows "waived" and "recorded fail", not a "fail" badge (`@SVCs SVC_GW_VETTING_0077`). Watch it fail first.
- [x] 3.3 `effectiveState` in `lib/vetting-flow.ts`; `snapshotFlow`, the outcome's objecting list and `VerdictCard` use it (`@Requirements GW_VETTING_0071`).

## 4. Every listed line marked (GW_VETTING_0072)

- [x] 4.1 `file-findings.test.ts`: `listedLines` reads `(line N)` and `(lines a, b +K more)` at the end of a message, ignores a mid-sentence mention, and `findingsByPath` puts the location line first in `lines` (`@SVCs SVC_GW_VETTING_0078`). Watch it fail first.
- [x] 4.2 `source-view.test.tsx`: a finding with lines 3 and 5 marks both lines and writes its note under each (`@SVCs SVC_GW_VETTING_0079`). Watch it fail first.
- [x] 4.3 `FileFinding.lines` and the `SourceView` index loop (`@Requirements GW_VETTING_0072`).

## 5. Row alignment

- [x] 5.1 Report rows, step-detail rows and the outcome's blocking list as two-column subgrids; check in a story with a long message.

## 6. Stories, docs, screenshots

- [x] 6.1 Stories: a vetting report with a waived vetter and a grouped vetter with a long message; a source view with a multi-line finding.
- [x] 6.2 `reference/portal.md` and `guides/approving-snapshots.md`.
- [x] 6.3 Before/after screenshots kept locally.

## 7. Gates and evidence

- [ ] 7.1 Every AGENTS.md gate fresh, recorded in `evidence.md`.
