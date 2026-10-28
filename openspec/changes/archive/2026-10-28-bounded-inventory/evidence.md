# Evidence: bounded-inventory

Source state: `e43cc692` (tree `96fce961`), on `main` after #745. Every result
below is from one fresh run after the last code edit, on 2026-10-28.

Tier 2 (bounded work on hostile input, on a role-free read). Spec approval:
the owner said to proceed without stopping, to keep exposure short.
Independent verification: not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_INGEST_0074) | Test | Seen failing first |
|---|---|---|
| A manifest of 1,001 plugins is refused | `ManifestPolicyTests.a_manifest_with_more_plugins_than_the_bound_is_refused` | yes: `Expecting actual not to be null` |
| A manifest repeating `./` 2,000 times over 500 skills lists at most 500 skills within 10 s | `InventoryBoundTests.a_manifest_repeating_one_source_lists_its_skills_once_quickly` | yes: `execution timed out after 10000 ms` |

## A spec change made during the work, and why

The first version also refused, at ingest, two plugins declaring the same
local source. The full suite then failed two existing tests:
- `NameCollisionTests.a_lookalike_of_an_approved_plugin_is_refused_naming_the_incumbent_and_nothing_is_published`;
- `SnapshotFactsPersistenceTests.ingestion_records_the_facts_without_the_state_and_indexes_each_plugin_at_its_manifest_line`.

Both deliberately name several plugins from one directory. Rather than
change their fixtures, the refusal was dropped:
- the read-time listing of each root once is what bounds the output;
- several plugin names over one directory may exist in real marketplaces.

The requirement, proposal, design, tasks and mutants were revised to match.
Both tests pass unchanged.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1150, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:41 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)`, on the second run; the first hit the known harness flake |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `356/356 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.67 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-bounded-inventory/mutants.sh` | `2 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-no-plugin-cap killed
M2-inventory-repeats killed
2 mutants run, all killed
```
