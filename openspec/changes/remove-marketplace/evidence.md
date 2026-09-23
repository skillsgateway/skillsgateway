# Evidence: remove-marketplace

Branch `feat/remove-marketplace`. One fresh run of every gate at commit
`9c50304f`, after the last code edit (`94bf057d`; the commit after it changes
only documentation and the regenerated OpenAPI document and portal types).

Spec approval: **not obtained (autonomous run)**. The owner settled the ledger
disambiguator (the marketplace id) before the run; everything else in
`design.md` was decided by the implementing agent and is listed in the PR.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 753, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  07:18 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  11 passed (11)
      Tests  57 passed (57)

$ (cd src/main/frontend && pnpm e2e)
  21 passed (1.2m)

$ reqstool status local -p docs/reqstool
263/263 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.47 seconds
```

`pnpm test:stories` passed on its third run after
`rm -rf node_modules/.cache/storybook`. The first two failed with
`Failed to fetch dynamically imported module` on story files this change does
not touch (`adoption`, `vetting`, `tokens`, `vetting-flow`,
`marketplace-vetting-chain`), with every test that did load passing. That is the
known cache flake after `mvnw clean`, not a regression.

A first `./mvnw clean verify` was killed by a 570 s wall-clock `timeout` before it
finished, with no failures up to that point; the run above is the complete one.

## The change's own tests

```console
$ ./mvnw test -Dskip.ui.verify=true -Dtest=MarketplaceRemovalTests
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
```

| Test | SVC | What it attacks |
| --- | --- | --- |
| `removal_withdraws_what_is_served_and_keeps_the_record` | SVC_GW_INGEST_0034 | A clone before and after; the withdrawal is administrative with the stated reason; `status` says revoked; row, snapshots, provenance and ledger survive; a held snapshot can be neither approved nor rejected |
| `a_refused_removal_changes_nothing` | SVC_GW_INGEST_0034 | null, empty and blank reasons, an unknown name, over the service and over HTTP (422/404) — still registered, still approved, still served, nothing on the ledger; a second removal is 404 and leaves one ledger entry |
| `a_removed_marketplace_is_out_of_every_name_addressed_path` | SVC_GW_INGEST_0034 | Sync sweep selection, ingest, sync-mode change, listing, approval over HTTP (409) |
| `the_facade_refuses_a_removed_name_even_with_references_left_behind` | SVC_GW_INGEST_0034 | Row stamped with the references still published — the state a failed unpublish or a raced approval leaves |
| `a_decision_on_a_removed_marketplace_is_refused_by_the_transition_itself` | SVC_GW_INGEST_0034 | The locked check inside `decide`, reached without the early check |
| `a_reused_name_is_a_new_marketplace_that_inherits_nothing` | SVC_GW_INGEST_0035 | New id, serves nothing, predecessor's approver grant gone, second live registration 409, same commit re-ingests held and approvable, `status` revoked until the successor approves then approved |
| `references_a_removed_marketplace_left_behind_are_not_served_under_its_successor` | SVC_GW_INGEST_0035 | Leftover published references cleared at re-registration |
| `a_hosted_successor_carries_none_of_its_predecessors_pushes` | SVC_GW_INGEST_0035 | Hosted origin lineage cleared |
| `purging_a_predecessor_snapshot_keeps_the_successors_copy_of_the_same_commit` | SVC_GW_INGEST_0035 | Retention compaction against a shared quarantine pin |
| `ledger_entries_of_two_marketplaces_that_held_one_name_carry_different_ids` | SVC_GW_AUDIT_0009 | Browse and export; the `-` entries carry none |
| `a_removal_is_announced_and_a_refused_one_is_not` | SVC_GW_WEBHOOK_0010 | Exact body keys; the reason never reaches the payload |

The admin-only half of the route is verified where the project verifies it,
by route: `RoleEnforcementTests` (the route added to the role-gated set) and
`MachineApiRegistryTests` (no scope reaches it).

## Mutation (manual; no mutation tool in the build)

`python3 openspec/changes/remove-marketplace/mutants.py <ids>` applies one
asserted replacement, runs `MarketplaceRemovalTests`, and restores the file
with `git checkout`. The runner aborts if a mutation site is not found exactly
once. Run against `94bf057d`.

| Mutant | Result | Killed by |
| --- | --- | --- |
| M1 facade registry check off | killed | `the_facade_refuses_…_left_behind` |
| M2 `decide` lock check off | killed | `a_decision_…_transition_itself`, `removal_withdraws_…` (reject) |
| M3 name reads unfiltered | killed | 8 tests |
| M4 shared-pin guard off | killed | `purging_a_predecessor_snapshot_…` |
| M5 successor does not unpublish predecessor refs | killed | `references_…_not_served_under_its_successor` |
| M6 grants on removed marketplaces not filtered | killed | `a_reused_name_…` |
| M7 hosted lineage not cleared | killed | `a_hosted_successor_…` |
| M8 ledger id not resolved from the name | killed | `ledger_entries_…` |
| M9 removal withdraws nothing | killed | `removal_withdraws_…`, `a_reused_name_…` |
| M10 held-content prefers retired over live-approved | killed | `a_reused_name_…` |

10/10 killed.

## Layers skipped, and known limits

- **Changed-line coverage: skipped.** The build has no coverage tool (no JaCoCo).
  The mutation pass above stands in for it on the guards.
- **Tests were written after the implementation**, not before it. The RED
  evidence is the mutation pass, where each guard's test fails with the guard
  removed. That is weaker than watching each test fail first.
- **Not killed by any test, by construction:** (a) the explicit marketplace id
  that removal, revocation and retention pass is preferred over the name
  resolution only when a successor was registered *during* the write, which
  no test interleaves; (b) the early `removed` check in `ApprovalService` is
  shadowed by the locked check in `decide`, and it exists only so that the
  approval gates write no ledger entries for a removed marketplace.
- **The approval/removal race** is serialised by a row lock (`FOR SHARE`
  against the removal's `UPDATE`). No test interleaves the two transactions.
  The facade check (M1) closes the remaining window, where a publication lands
  after a withdrawal.
- **Independent verification: not performed.**
