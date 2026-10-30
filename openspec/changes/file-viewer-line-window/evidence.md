# Evidence: file-viewer-line-window

Source state: `9c650e4f` (tree `cca366d8`), on `main` at `daf92367`. The gate results
below come from one fresh run after the last code edit, on 2026-10-30. Only
reqstool ran after the last commit, which changed a requirement's category
metadata and no code.

Tier 1 (a read path the approver gate already covers; no access rule changes).

## Spec → tests

| Scenario | Test | Seen failing first |
|---|---|---|
| SVC_GW_APPROVAL_0037: a read naming line 5000 of a 300 KiB file returns ≤128 KiB from a line boundary holding it, numbered by `firstLine`; no line, a line in the head and a line past the end return the head; a small file is whole | `PreviewTests.a_read_naming_a_line_returns_the_part_of_a_large_file_that_holds_it` | yes: `firstLine` absent (NullPointerException on `get("firstLine")`) |
| SVC_GW_APPROVAL_0038: a blank path, and a line below 1, are 400 | `PreviewTests.a_read_with_a_blank_path_or_a_line_below_one_is_a_bad_request` | yes: `IllegalArgumentException: Empty path not permitted` (500) |
| SVC_GW_APPROVAL_0039: following a finding past the part shown loads the part holding its line, numbered and marked | `snapshot-explorer.test.tsx › following_a_finding_past_the_part_shown_loads_the_part_that_holds_its_line` | yes: no button for `line 40, beyond the part shown` |

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar, UI gate | `./mvnw clean verify` | `Tests run: 1177, Failures: 0, Errors: 0, Skipped: 9` · vitest `Tests 235 passed (235)` · `BUILD SUCCESS` (7:59) |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | 368 requirements · in code 348/348 verified · tests 1196 passed, 0 failed · 0 SVCs missing tests · exit 0 |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.63 seconds` |
| API contract | `openapi.json` regenerated from `OpenApiDocsTests`; `types.gen.ts` from `pnpm gen:api-types` | additive: optional `line`, new `firstLine`, new 400 |
| Supply chain | no dependency added or changed | — |

## Known limits

- A single line longer than 128 KiB is cut, as the head already is.
- The windowing pass reads the blob from its start, bounded by the size limits
  that ingest already enforces on a blob.
