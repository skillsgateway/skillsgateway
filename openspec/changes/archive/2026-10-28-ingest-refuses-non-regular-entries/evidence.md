# Evidence: ingest-refuses-non-regular-entries

Source state: `0d6bb087` (branch `fix/ingest-refuses-symlinks`, on
`origin/main` `a056c258`). Every result below is from one fresh run after the
last code edit, on 2026-10-28.

Tier 2 (vetted bytes versus served bytes). Spec approval: the owner said to
proceed without stopping, to keep exposure short. Independent verification:
not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_INGEST_0070) | Test | Seen failing first |
|---|---|---|
| A symlinked hooks file rejects the snapshot, naming the path and "symbolic link" | `NonRegularEntryTests.a_symbolic_link_in_the_tree_rejects_the_snapshot_naming_the_path` | yes: `expected: "rejected"` (it was held) |
| A submodule entry rejects the snapshot, naming the path and "submodule" | `…a_submodule_entry_in_the_tree_rejects_the_snapshot_naming_the_path` | yes: same |
| An executable script is still held | `…an_executable_file_is_still_held` | no, it held already; regression armor, proved non-vacuous by M4 |

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1111, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:34 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `344/344 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.62 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-ingest-refuses-non-regular-entries/mutants.sh` | `4 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-upstream-unchecked killed
M2-symlink-not-named killed
M3-gitlink-not-named killed
M4-executable-refused killed
4 mutants run, all killed
```

## Known limits

- The second check, on a composite tree after external content is grafted in,
  has no dedicated test or mutant. Each grafted external tree comes from a
  repository that ingestion walks the same way, through the same helper.
- Snapshots ingested before this change are not revisited. A held snapshot with
  a link stays held until it is re-ingested.
