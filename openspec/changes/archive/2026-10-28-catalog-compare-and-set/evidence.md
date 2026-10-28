# Evidence: catalog-compare-and-set

Source state: `79a010be` (tree `2568f913`), built on #739 (serve-nothing),
which sits on `main` after #738. Every result below is from one fresh run
after the last code edit, on 2026-10-28.

Tier 3 (concurrency across instances on the catalog's serving boundary). Spec
approval: the owner said to proceed without stopping, to keep exposure short.
Independent verification: not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_FACADE_0037) | Test | Seen failing first |
|---|---|---|
| A second instance whose rebuild read a marketplace as served, which is then withdrawn and rebuilt without it before that instance commits, does not restore it, and reports the revision that is served | `CatalogTests.a_rebuild_whose_reads_went_stale_recomposes_instead_of_overwriting_a_newer_catalog` | yes: `the withdrawn marketplace is back in the catalog` |

The interleaving is forced through `beforeCommit()`, a protected no-op seam
that the test's second instance overrides. The test runs last in `CatalogTests`'
ordered sequence (`@Order(6)`), so the estate cases before it are unchanged.
The `served revision` assertion was added after the first GREEN, to kill M3.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1137, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 08:23 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.9m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `350/350 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.67 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-catalog-compare-and-set/mutants.sh` | `4 mutants run, all killed` (run on the branch before it was restacked on #739; that diff touches no catalog file) |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-no-expected-value killed
M2-read-after-composing killed
M3-lost-race-is-success killed
M4-no-recompose killed
4 mutants run, all killed
```

## Known limits

- No periodic catalog reconciliation was added. A rebuild that fails all five
  attempts is logged, and the next publication change or
  `POST /api/v1/catalog/rebuild` repairs it.
