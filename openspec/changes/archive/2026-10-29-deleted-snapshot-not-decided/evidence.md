# Evidence: deleted-snapshot-not-decided

Source state: `f1f53ce1` (tree `3fe57955`), built on #753. When rebased onto
`main` after #753 merges, the tree must be identical, and that is checked before
the PR opens. Every result below is from one fresh run after the last code
edit, on 2026-10-29.

Tier 2 (approval). Spec approval: the owner said to proceed without stopping,
to keep exposure short. Independent verification: not performed. Both are
declared downgrades.

## Spec → tests

| Scenario (SVC_GW_APPROVAL_0035) | Test | Seen failing first |
|---|---|---|
| A deleted held snapshot is neither approved nor rejected, stays held and deleted, and the refusal names the restore | `DeletedSnapshotDecisionTests.a_deleted_snapshot_is_neither_approved_nor_rejected` | yes: the approval succeeded |
| A decision made from a row read before a delete is refused | `…a_decision_read_before_a_concurrent_delete_is_refused` | no: added so the statement's guard has a test of its own (M2) |
| A restored snapshot is approved | `…a_restored_snapshot_is_decided_as_before` | no: a control |

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1170, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:02 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` on the second run; the first hit the known harness flake. No frontend file changed. |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.7m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `364/364 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.51 seconds` |
| Mutation | `openspec/changes/archive/2026-10-29-deleted-snapshot-not-decided/mutants.sh` | `2 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-no-deleted-check killed
M2-no-concurrent-guard killed
2 mutants run, all killed
```

## Known limits

- The HTTP mapping to 409 is the existing `AdminController` handler for
  `IllegalStateException`. These tests call the service, not the route.
