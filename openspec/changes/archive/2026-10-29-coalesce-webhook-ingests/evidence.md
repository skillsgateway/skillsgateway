# Evidence: coalesce-webhook-ingests

Source state: `68ae24a4` (tree `8448f28e`), built on #752. When rebased onto
`main` after #752 merges, the tree must be identical, and that is checked before
the PR opens. Every result below is from one fresh run after the last code
edit, on 2026-10-29.

Tier 2 (a shared queue reachable without a gateway credential). Spec approval:
the owner said to proceed without stopping, to keep exposure short.
Independent verification: not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_INGEST_0076) | Test | Seen failing first |
|---|---|---|
| 50 alpha triggers and 1 beta trigger while the queue is busy: one alpha ingest, beta runs, and a trigger after alpha's ingest started queues a new one | `WebhookIngestCoalescingTests.triggers_queued_while_the_queue_is_busy_ingest_once_and_others_still_run` | yes: each alpha trigger had its own future. Confirmed again against the unchanged `SyncService` after the count assertions were made relative |

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1167, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:01 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` on the second run; the first hit the known harness flake. No frontend file changed. |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.7m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `363/363 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.51 seconds` |
| Mutation | `openspec/changes/archive/2026-10-29-coalesce-webhook-ingests/mutants.sh` | `2 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-not-coalesced killed
M2-never-cleared killed
2 mutants run, all killed
```

## Known limits

- Clearing on start rather than on completion (design D2) has no test. Telling
  the two apart needs a trigger to land while an ingest is running, and the
  local test upstream fetches too fast to hold one open deterministically. A
  mutant that clears on completion would survive. It is not listed, so that it
  is not mistaken for a killed one.
- A repeated delivery that arrives after the queued ingest has started still
  ingests again. Coalescing bounds the queue; it does not de-duplicate
  deliveries.
