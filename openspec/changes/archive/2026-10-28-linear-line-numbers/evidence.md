# Evidence: linear-line-numbers

Source state: `777d13e2` (tree `298db914`), built on #741, which sits on `main`.
When rebased onto `main` after #741 merged, the tree is unchanged. Every result
below is from one fresh run after the last code edit, on 2026-10-28.

Tier 2 (bounded work on hostile input). Spec approval: the owner said to
proceed without stopping, to keep exposure short. Independent verification:
not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_VETTING_0065) | Test | Seen failing first |
|---|---|---|
| A hook module of 80,000 `import ` keywords is read within 2 s | `HookModulesTests.a_module_dense_with_import_keywords_is_read_in_linear_time` | yes: `execution timed out after 2000 ms` |
| A module with 40,000 event registrations is located within 2 s, with first and last events at lines 2 and 40002 | `HookModulesTests.a_module_with_many_events_is_located_in_linear_time` | yes: timed out |
| An instruction file with 30,000 injection phrases is vetted within 3 s, with findings at lines 4 and 30003 | `LinearLocationTests.an_instruction_file_dense_with_injections_is_scanned_in_linear_time` | yes: timed out, in `ContentRules.apply` |
| A script with 20,000 secret-shaped assignments is vetted within 3 s, with findings at lines 1 and 20000 | `LinearLocationTests.a_script_dense_with_secret_shaped_assignments_is_scanned_in_linear_time` | yes: timed out, after its fixture was corrected (see below) |

The existing `HookModulesTests` import-following cases ran unchanged and
green, which is the check that the look-back discovery finds the same
specifiers as the old pattern.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1142, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:38 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `352/352 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.69 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-linear-line-numbers/mutants.sh` | `4 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-off-by-one killed
M2-rules-rescan killed
M3-secrets-rescan killed
M4-event-line-wrong killed
4 mutants run, all killed
```

## What went wrong, and how it was resolved

- **The first secret-scan fixture** used `token_1 = "…"`, which the pattern's
  `\btoken\b` does not match, so the test produced no findings rather than a
  RED. It was corrected to `token = "…"` before any implementation change, and
  it then timed out as intended.

## Known limits

- No mutant reintroduces the keyword-first import scan as such. The dense-import
  test is what holds it, and it timed out against the original.
- Per-rule finding caps and a per-snapshot inventory cache, which the record
  also suggests, are not added (design.md).
