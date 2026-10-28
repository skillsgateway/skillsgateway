# Evidence: vetter-timeout-stops-matching

Source state: `5e82d78a` (branch `fix/vetter-timeout-stops-matching`, on
`origin/main` `a056c258`). Every result below is from one fresh run after the
last code edit, on 2026-10-28.

Tier 3 (availability under hostile input; concurrency). Spec approval: the
owner said to proceed without stopping, to keep exposure short. Independent
verification: not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_VETTING_0064) | Test | Seen failing first |
|---|---|---|
| An interrupted `RuntimeFetch.scan` of a pathological line stops within 2 s | `VetterCancellationTests.an_interrupted_scan_of_a_pathological_line_stops` | yes: `still matching two seconds after the interruption` |
| An interrupted `ExecutableSurfaceVetter` run over a hook script holding that line stops within 2 s | `…an_interrupted_vetter_run_over_a_hook_script_stops` | yes: same |
| A TOML section header that never closes is read within 1 s, and the real section after it is still found | `…a_section_header_with_no_closing_bracket_is_read_in_linear_time` | yes: `execution timed out after 1000 ms` |

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1111, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:48 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)`, on the third run (see below) |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (2.0m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `344/344 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.68 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-vetter-timeout-stops-matching/mutants.sh` | `3 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-never-interrupts killed
M2-scan-line-unguarded killed
M3-section-backtracks killed
3 mutants run, all killed
```

## What went wrong, and how it was resolved

- **A surviving mutant.** The first M2 unwrapped only the
  `DOWNLOAD_THEN_RUN` match and survived. The interrupt landed in a different,
  still-wrapped pattern on the same line, so the test could not tell. Instead of
  making the test timing-dependent, `RuntimeFetch.scan` now builds one
  interruptible view per line and every pattern there uses it
  (`refactor(vetting): one interruptible view per scanned line`). M2 then
  removes that one guard and is killed.
- **Storybook.** `pnpm test:stories` failed to import `adoption.stories.tsx` on
  the first run and found no tests on the second. The third run passed 21 of
  21. `/tmp` was at 4 GB of 63 GB, so this was not the quota cause, and this
  change touches no frontend file.

## Known limits

- Every content match site in the vetters and in `HookModules` is wrapped, but
  only the `RuntimeFetch` line guard, the wrapper itself and the section header
  are individually mutation-tested. The others are covered by the mechanism
  (`InterruptibleText` is the only way the change wraps), not by a test per
  site.
- `DOWNLOAD_THEN_RUN` still backtracks superlinearly until the time limit. That
  cost is now bounded by one limit per run, and the pattern rewrite is a
  separate change.
