# Evidence: allowed-tools-space-form

Source state: `91fe541a` (branch `fix/allowed-tools-space-form`, on
`origin/main` `a056c258`). Every result below is from one fresh run after the
last code edit, on 2026-10-28.

Tier 2 (a fail-open in the policy gate's facts). Spec approval: the owner said
to proceed without stopping, to keep exposure short. Independent verification:
not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_APPROVAL_0031) | Test | Seen failing first |
|---|---|---|
| A skill declaring `allowed-tools: Read Bash(curl:*)` is refused by the documented `t.startsWith("Bash")` rule | `PolicyEnforcementTests.a_shell_tool_declared_in_the_space_separated_form_is_denied` | yes: `Status expected:<409> but was:<200>` |
| The space form, a mixed form and a list entry split per tool, with spaces inside parentheses kept | `SkillFrontmatterTests.tools_from_the_space_separated_form_and_mixed_forms_split_per_tool` | yes: one joined tool was returned |

The other `SkillFrontmatterTests` and `PolicyEnforcementTests` cases were
unchanged and stayed green, among them the comma form that respects
parentheses.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1110, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:56 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)`, on the second run (see below) |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `343/343 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.68 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-allowed-tools-space-form/mutants.sh` | `2 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Property-based tests | skipped: a small set of separator shapes, enumerated | — |
| Supply chain | no dependency added or changed | — |

```
M1-commas-only killed
M2-list-entries-whole killed
2 mutants run, all killed
```

## What went wrong, and how it was resolved

- The first `pnpm test:stories` run failed to initialise 2 of 21 story files
  (`adoption.stories.tsx` and one other: the iframe did not become ready within
  60000ms). This is the known harness flake, and this change touches no
  frontend file. The immediate rerun passed 21 of 21.
