# Evidence: frontmatter-hook-budget

Source state: `597036f7` (tree `ead6f85d`), built on #740, which sits on `main`.
When rebased onto `main` after #740 merged, the tree is unchanged. Every result
below is from one fresh run after the last code edit, on 2026-10-28.

Tier 2 (bounded work on hostile input). Spec approval: the owner said to
proceed without stopping, to keep exposure short. Independent verification:
not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_INGEST_0071) | Test | Seen failing first |
|---|---|---|
| Hooks aliased three per level over twelve levels yield no hook and a "too large" problem within 2 s | `PluginComponentsTests.frontmatter_hooks_that_alias_themselves_into_a_huge_tree_are_refused_quickly` | yes: the expansion was built and read as a hook (`Expecting empty but was: [Hook[event=Stop …`) |

At this size the expansion still finished quickly. The defect is that nothing
bounded it, and the expansion grows exponentially with each further level.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1138, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:49 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)`, on the second run; the first hit the known import flake |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `351/351 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.68 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-frontmatter-hook-budget/mutants.sh` | `2 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-unchecked killed
M2-never-counts killed
2 mutants run, all killed
```
