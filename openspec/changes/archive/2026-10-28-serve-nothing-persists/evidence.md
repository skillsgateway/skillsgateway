# Evidence: serve-nothing-persists

Source state: `09cc26ed` (tree `053c85bc`), on the publication-lock change.
Rebased onto `main` after #737 merged, the tree is unchanged. Every result below
is from one fresh run after the last code edit, on 2026-10-28.

Tier 3 (an administrator's withdrawal decision against automatic repair). Spec
approval: the owner said to proceed without stopping, to keep exposure short.
Independent verification: not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_APPROVAL_0034) | Test | Seen failing first |
|---|---|---|
| After a `NOTHING` withdrawal, reconciliation does not re-serve the predecessor | `ServeNothingPersistsTests.the_reconciler_does_not_re_serve_a_predecessor_after_a_withdrawal_that_chose_to_serve_nothing` | yes: `re-served by the reconciler` |
| After `NOTHING` and then a new approval, a lost publication of the approval is repaired | `…a_later_approval_returns_the_marketplace_to_service_and_the_reconciler_keeps_it` | no: it held already, and is proved non-vacuous by M3 |
| After a rollback to the predecessor, a lost publication of it is repaired | `…a_withdrawal_that_returned_to_the_predecessor_leaves_it_repairable` | added so that M4 can be killed; it held already |

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1136, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:40 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `349/349 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.67 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-serve-nothing-persists/mutants.sh` | `4 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-not-recorded killed
M2-reconciler-ignores killed
M3-approval-does-not-resume killed
M4-suspends-after-rollback killed
4 mutants run, all killed
```

## Known limits

- The marker is cleared only by an approval that publishes. There is no
  separate "return to service" action: re-approving the predecessor through
  the two-administrator reversal path is that action.
