# Evidence: portal-highlight-findings

- **Source state:** every gate below ran over `d21e8ddb`, the commit after the
  last code edit, rebased on `main` at `63e8a0c7`. The only later changes are
  this file, `tasks.md` and the archive.
- **Process:** test-first. Each new test was observed failing before its code
  existed. This is portal-only and not a trust boundary, so it follows the
  ordinary loop, not old-coder.
- **Decision taken during implementation:** design D3 was revised by the
  owner. Markdown and JSON open as they read, and following a finding opens
  the numbered source. The first draft broke the existing contents e2e test
  (SVC_GW_APPROVAL_0005, SVC_GW_INGEST_0032, SVC_GW_APPROVAL_0028), which is
  kept unedited.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 973, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  27 passed (27)
[INFO]       Tests  207 passed (207)
[INFO] BUILD SUCCESS
[INFO] Total time:  08:32 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  17 passed (17)
      Tests  87 passed (87)

$ (cd src/main/frontend && pnpm e2e)
  24 passed (1.2m)

$ reqstool status local -p docs/reqstool        # reqstool==0.12.0, as CI pins it
320/320 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.57 seconds
```

What happened on the way:

- **Story tests.** The first run after `mvnw clean verify` imported no tests:
  the known Storybook cache flake. Clearing `node_modules/.cache/storybook`
  and `node_modules/.vite` and running again passed (87/87).
- **Unit tests under load.** During development, UI unit runs that timed out
  (webhooks, a 31 s search test, and once the tree-marker test) coincided with
  a load average of 11–15 from another session on this machine. They passed
  every time the load was below 5, including the run inside `mvnw verify`
  above. None was edited.
- **Leaked containers:** none (`docker ps --filter label=org.testcontainers=true`).

## Spec → tests

| Requirement / scenario | Verified by |
| --- | --- |
| SVC_GW_APPROVAL_0029: a location split as the server splits it; findings grouped per file with vetter and waiver; highest severity | `lib/file-findings.test.ts` (4 tests) |
| SVC_GW_APPROVAL_0029: numbered lines; a marked line shows each finding as text and is described by them; waived; beyond truncation | `components/source-view.test.tsx` |
| SVC_GW_APPROVAL_0029: summary, following a finding opens the marked line, rendered view one control away, a file without findings unchanged, the tree marker, a failed vetting read keeps the file | `components/snapshot-explorer.test.tsx`, `components/snapshot-file-tree.test.tsx` |
| SVC_GW_APPROVAL_0030: the address carries the line (card and standalone route), a new file drops it, an addressed line is focused | `pages/marketplace-detail.test.tsx`, `pages/snapshot-files.test.tsx`, `source-view.test.tsx` |
| SVC_GW_APPROVAL_0030: every location in the report is a link (text unchanged); without a target it stays text (approval dialog) | `components/vetting-report.test.tsx`, and `a_location_in_the_vetting_tab_opens_its_file_at_its_line` |
| Both, end to end against the real jar | e2e `a_finding_is_followed_from_the_vetting_tab_to_its_marked_line` |
| Invariant: the existing vetting-report text is unchanged | the existing `a_vendored_group_is_one_row_with_every_location_and_the_gate_names_them`, unedited and green |
| Invariant: the existing contents e2e test is unedited | `snapshot_contents_are_explored_on_an_address_that_restores_the_same_file`, green |
| Invariant: no backend or API change | `openapi.json` unchanged; `OpenApiContractTests` green inside `mvnw verify` |

Tests that passed the first time they ran, all guarding today's behaviour:
`a_markdown_file_without_findings_keeps_its_rendered_view`,
`without_a_link_target_locations_stay_text` and
`a_vetting_read_that_fails_leaves_the_file_readable`.

### Design harness (`/impeccable audit` + `/impeccable harden`)

Surfaces: the Contents tab (`source-view.tsx`, `FileContent`/`FileFindings` in `snapshot-explorer.tsx`, the tree's `FindingsMarker`) and the Vetting tab's location links (`finding-locations.tsx`, `vetting-report.tsx`, `vetting-flow.tsx`). Detector: no findings.

Contrast was computed from the `oklch` tokens (sRGB compositing), not estimated:

| Finding | Before | Fix | After |
| --- | --- | --- | --- |
| **P1** "waived by … until …" on a waived note (`text-muted-foreground`, `opacity-75`, on the line's tint), light | 2.64:1 | Notes sit on `bg-background`; waived is shown by a dashed muted rule and its words, not by fading | 4.73:1 |
| **P1** severity word (`text-destructive`, `text-xs`) on the destructive tint, light | 3.99:1 | Same: the note is on the page background | 4.76:1 |
| **P2** line numbers on a marked line (muted grey on the tint), light | 3.96:1 | Foreground, medium weight (craft floor: no grey on a coloured surface) | ≥ 8:1 |
| **P2** summary entries could not wrap (`Button` is `whitespace-nowrap`) | overflow at narrow widths | `whitespace-normal break-all text-left` | wraps |
| **P3** location links used the browser's default focus outline | — | The app's `focus-visible` ring | consistent |

Dark mode passed every check before the fixes; the lowest value was 4.42:1, for the waived text.

Checked and left as is:

- Markers appear once vetting loads, and an addressed line focuses regardless.
- A reviewer without vetting access gets the file without markers (tested).
- Severity is always a word, never colour alone.
- A blob is capped at 128 KiB, so a 4-digit gutter holds every line number.
- No i18n surface: the portal is English-only.
