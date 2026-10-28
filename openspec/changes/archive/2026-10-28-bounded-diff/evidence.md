# Evidence: bounded-diff

Source state: `bfedfe02` (tree `9f3ecb50`), built on #743, which sits on `main`.
When rebased onto `main` after #743 merged, the tree is unchanged. Every result
below is from one fresh run after the last code edit, on 2026-10-28.

Tier 2 (bounded work on hostile input). Spec approval: the owner said to
proceed without stopping, to keep exposure short. Independent verification:
not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_INGEST_0073) | Test | Seen failing first |
|---|---|---|
| A 64,000-line file pair of random `x`/`y` lines (seeds 1 and 2) diffs within 5 s | `PreviewTests.a_diff_of_files_built_to_defeat_the_diff_heuristic_answers_quickly` | yes: `execution timed out after 5000 ms` |

**A first fixture that passed vacuously.** Two files of constant, different
lines answered in 0.4 s on unchanged code, because they share no line, so
histogram has nothing to align. The fixture was replaced by the audit
payload's random two-value lines before the fix, and then timed out as
intended.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1146, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 08:01 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `354/354 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.66 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-bounded-diff/mutants.sh` | `2 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-fallback-restored killed
M2-default-algorithm killed
2 mutants run, all killed
```
