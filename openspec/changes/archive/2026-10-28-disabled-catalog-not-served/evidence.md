# Evidence: disabled-catalog-not-served

Source state: `e883d05c` (tree `b7a723df`), on `main` after #747. It was then
rebased over #725, which changes only the `Dockerfile` base image digest.
Outside the `Dockerfile` the tree is identical, and `PackagingTests`, the one
suite that reads it, was rerun after the rebase: 13 tests, 0 failures. Every
other result below is from one fresh run after the last code edit, on
2026-10-28.

Tier 2 (serving boundary). Spec approval: the owner said to proceed without
stopping, to keep exposure short. Independent verification: not performed.
Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_FACADE_0038) | Test | Seen failing first |
|---|---|---|
| A disabled catalog that storage still holds answers as not found | `DisabledCatalogTests.a_disabled_catalog_answers_as_not_found_though_storage_still_holds_it` | yes: `Expecting code to raise a throwable` |
| An enabled catalog is served | `DisabledCatalogTests.an_enabled_catalog_is_served` | regression armor |

The first draft passed `null` as the request, which the resolver dereferences.
It became a `MockHttpServletRequest` before the RED run that is reported here.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1154, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:45 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)`, on the second run; the first hit the known harness flake |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.9m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `358/358 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.73 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-disabled-catalog-not-served/mutants.sh` | `1 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-flag-ignored killed
1 mutants run, all killed
```

## Known limits

- A re-enabled catalog serves its last revision until the next rebuild
  (design.md, Not in this change).
