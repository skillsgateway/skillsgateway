# Evidence: approver-scoping

Source state: `e5e0645a` (tree `b2af0915`), built on #746, which sits on `main`.
When rebased onto `main` after #746 merged, the tree is unchanged. Every result
below is from one fresh run after the last code edit, on 2026-10-28.

Tier 3 (authorization scoping). Spec approval: the owner said to proceed
without stopping, to keep exposure short. Independent verification: not
performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_AUTH_0056) | Test | Seen failing first |
|---|---|---|
| A re-registered name's approver gets `403` on the predecessor's snapshot, and an administrator does not | `MarketplaceRemovalTests.the_approver_of_a_reused_name_is_refused_the_predecessors_snapshot_and_an_admin_is_not` | yes: `Status expected:<403> but was:<200>` |
| One commit served by two marketplaces reports each marketplace's own fetcher only | `FetchersScopeTests.the_fetchers_of_a_commit_two_marketplaces_serve_are_reported_per_marketplace` | yes: the report named the other marketplace's fetcher |

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1152, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:36 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)`, on the second run; the first hit the known harness flake |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `357/357 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.65 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-approver-scoping/mutants.sh` | `2 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-name-not-live killed
M2-fetchers-unbound killed
2 mutants run, all killed
```

## Known limits

- Only `GET /snapshots/{id}/files` is exercised for the predecessor refusal.
  The other snapshot-id and waiver-id routes go through the same two `require*`
  methods, which `liveName` now feeds.
