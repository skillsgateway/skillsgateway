# Evidence: vetter-roots-bounded

Source state: `9436a0d6` (tree `849fc926`), built on #744. When rebased onto
`main` after #744 merges, the tree must be identical, and that is checked before
the PR opens. Every result below is from one fresh run after the last code edit,
on 2026-10-28.

Tier 2 (bounded work on hostile input). Spec approval: the owner said to
proceed without stopping, to keep exposure short. Independent verification:
not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_VETTING_0066) | Test | Seen failing first |
|---|---|---|
| 3,000 plugin roots fail within 5 s with `too-many-plugin-roots` | `PluginRootBoundTests.a_snapshot_with_more_plugin_roots_than_the_bound_fails_closed_quickly` | yes: there was no such finding |
| An interrupted run over 999 roots among 200,000 files stops within 2 s | `…an_interrupted_run_over_many_roots_stops` | yes: `still vetting two seconds after the interruption` |

**Test infrastructure:** `InMemorySnapshot` overrides the new
`read(path)` with a map lookup, as `QuarantineSnapshot` does in production.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1148, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:58 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `355/355 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.82 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-vetter-roots-bounded/mutants.sh` | `2 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-no-root-cap killed
M2-no-interrupt-check killed
2 mutants run, all killed
```

## Known limits

- `QuarantineSnapshot.read`, the production lookup, is not exercised by a
  timing test of its own; the tests use the in-memory snapshot. Its behaviour
  is the same as that of the map it reads, which `QuarantineSnapshotTests`
  covers.
- `PluginComponents` still scans every path once per root (to list Markdown
  under a directory). With at most 1,000 roots and at most 200,000 entries
  (`GW_INGEST_0072`), that is bounded and interruptible between roots.
