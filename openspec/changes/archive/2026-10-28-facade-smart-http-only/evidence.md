# Evidence: facade-smart-http-only

Source state: `d42a02e0` (branch `fix/facade-smart-http-only`, rebased onto
`origin/main` `a056c258`). Every result below is from one fresh run after the
last code edit, on 2026-10-28.

Tier 3 (trust boundary: what the facade serves). Spec approval: the owner
said to proceed without stopping for approval, to keep exposure short.
Independent verification: not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_FACADE_0036) | Test | Seen failing first |
|---|---|---|
| `/git/**` refuses `info/refs` without a service, `HEAD`, `objects/info/packs` and a commit's object path, and still answers the smart advertisement | `SmartHttpOnlyTests.the_consumer_facade_serves_no_repository_file_outside_the_smart_protocol` | yes: the plain-file `GET …/info/refs` answered below 400 |
| `/publish/**` refuses the same routes, and still answers the smart advertisement | `SmartHttpOnlyTests.the_publish_endpoint_serves_no_repository_file_outside_the_smart_protocol` | yes: same |

The following suites were unchanged and stayed green: `FacadeTests`,
`HostedPushTests`, `PublicationIntegrityTests`, `RefAdvertisementTests` and
`WantedRefTests`.

## Gauntlet

`./mvnw clean verify` runs the UI gate (`pnpm verify`) in its test phase. The
full chain outran the 10-minute tool limit once, after its tests had passed, so
the UI gate was split out with `-Dskip.ui.verify=true` and run by hand straight
afterwards on the same tree.

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1110, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 08:31 min` |
| UI gate (typecheck, lint, unit tests, reqstool tags and merge) | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.9m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `343/343 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.80 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-facade-smart-http-only/mutants.sh` | `2 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Coverage on changed lines | not measured by a tool; each of the two changed lines is killed by its own mutant | — |
| Property-based tests | skipped: a fixed set of routes, enumerated in the test | — |
| Supply chain | no dependency added or changed | — |

```
M1-facade-dumb-http-on killed
M2-publish-dumb-http-on killed
2 mutants run, all killed
```

## Known limits

- Objects of withdrawn snapshots remain in the served object database. Over
  HTTP they are now unreachable, because upload-pack refuses unadvertised
  wants. Pruning them is a separate change (design.md, D3).
