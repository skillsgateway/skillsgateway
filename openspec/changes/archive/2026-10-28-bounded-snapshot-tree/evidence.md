# Evidence: bounded-snapshot-tree

Source state: `0bbc1471` (tree `c70bba18`), built on #742, which sits on `main`.
When rebased onto `main` after #742 merged, the tree is unchanged. Every result
below is from one fresh run after the last code edit, on 2026-10-28.

Tier 3 (memory exhaustion from ten objects of hostile input). Spec approval:
the owner said to proceed without stopping, to keep exposure short.
Independent verification: not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_INGEST_0072) | Test | Seen failing first |
|---|---|---|
| A ten-way, eight-level fan-out upstream is rejected, naming the entry bound, within 30 s | `TreeBoundTests.a_tree_with_too_many_paths_rejects_the_snapshot_quickly` | yes: `execution timed out after 30000 ms` |
| The same fan-out, over empty directories, is refused by the vetting index within 10 s | `TreeBoundIndexTests.the_vetting_index_refuses_a_tree_with_too_many_paths_quickly` | yes, with file leaves: timed out after 10 s. The leaves were then made empty, so that M2 (a recursive walk with a files-only cap) is killed |
| A 40-directory-deep tree is refused, naming the depth | `TreeBoundIndexTests.the_vetting_index_refuses_a_tree_deeper_than_the_bound` | added to kill M3 |

The non-regular-entry check from #734 now runs on the same bounded walk, and
`NonRegularEntryTests` ran unchanged and green.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1145, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:45 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)`, on the second run; the first hit the known harness flake ("no tests") |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `353/353 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.84 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-bounded-snapshot-tree/mutants.sh` | `4 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-no-entry-bound killed
M2-recursive killed
M3-no-depth-bound killed
M4-ingest-ignores-bound killed
4 mutants run, all killed
```

## Known limits

- `SnapshotFactsService.files` and the preview's tree walks still walk
  recursively. They run only on snapshots that passed ingest, which now
  rejects an over-bound tree first.
