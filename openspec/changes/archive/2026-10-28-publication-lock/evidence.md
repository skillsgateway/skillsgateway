# Evidence: publication-lock

Source state: `ca8e6dc5`, built on the verified tree of #735, #736 and #737 on
top of `main`. When it is rebased onto `main` after those merge, the tree
must be identical, and that is checked before the PR is opened. Every result
below is from one fresh run after the last code edit, on 2026-10-28.

Tier 3 (concurrency at the publication trust boundary). Spec approval: the
owner said to proceed without stopping, to keep exposure short. Independent
verification: not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_APPROVAL_0033) | Test | Seen failing first |
|---|---|---|
| An approval waits for the marketplace's lock, then publishes | `PublicationLockTests.an_approval_waits_for_the_marketplace_lock_and_then_publishes` | yes, on a stub lock that locked nothing: `the transition never waited on the publication lock` |
| A withdrawal asking to return waits, and does not return to a predecessor the holder withdrew | `…a_rollback_does_not_republish_a_predecessor_withdrawn_while_it_waited` | yes: same |
| A repair waits, and does not republish a snapshot the holder withdrew | `…the_reconciler_does_not_republish_a_snapshot_withdrawn_while_it_waited` | yes: same |

Each test holds the lock from another thread and detects the waiter through
`pg_locks` (an ungranted advisory lock), so the interleaving is forced rather
than timed.

These suites ran unchanged and green before the full gauntlet:
`PublicationIntegrityTests` (refused publication returns the record),
`PublicationReconciliationTests`, `AdministrativeRevocationTests`,
`RevetEnforceTests`, `RevetTests`, `ApprovalTests`, `FourEyesEnforceTests`,
`NameCollisionTests`, `NameCollisionRaceTests`, `DecisionCompareAndSetTests`,
`MarketplaceRemovalTests`, `CatalogTests`, `StagingRefSweepTests`,
`ObjectStoreFacadeTests`, `FacadeTests`, `HostedPushTests` and
`SweepCoordinationTests`.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1133, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:47 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `348/348 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.65 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-publication-lock/mutants.sh` (in two batches: `ONLY='M1\|M2\|M3'`, then `ONLY='M4\|M5\|M6'`) | `6 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-lock-does-not-block killed
M2-approval-wrong-lock killed
M3-revoke-wrong-lock killed
M4-rollback-not-reread killed
M5-repair-wrong-lock killed
M6-repair-not-reread killed
```

## Known limits

- `RevetService.quarantine` runs under the lock, but no test of its own covers
  that. Re-vetting enforcement needs its own Spring context, and adding a
  context for one test would move `ContextBudgetTests`.
- Marketplace removal unpublishes without the publication lock (design.md, D4).
  An approval racing a removal is refused by `decide`'s row lock, not by this
  lock.
- A transaction that fails to commit after `commitPublication` moved the
  references leaves them serving a rolled-back row. The reconciler reports that
  as served without an approval.
- The serve-nothing decision is still not persisted, so the startup reconciler
  can re-serve a predecessor an administrator chose not to serve. That is a
  separate change.
