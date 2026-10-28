# Evidence: decide-compare-and-set

Source state: `b2e07266` (branch `fix/decide-compare-and-set`, on
`origin/main` `a056c258`). Every result below is from one fresh run after the
last code edit, on 2026-10-28.

Tier 3 (concurrency at the approval trust boundary). Spec approval: the owner
said to proceed without stopping, to keep exposure short. Independent
verification: not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_APPROVAL_0032) | Test | Seen failing first |
|---|---|---|
| An approval read before an administrative withdrawal is refused, and the withdrawal stands | `DecisionCompareAndSetTests.an_approval_of_a_snapshot_withdrawn_since_it_was_read_is_refused_and_the_withdrawal_stands` | yes, on a stub that kept today's id-keyed `decide`: `Expecting code to raise a throwable` |
| A rejection read before an approval is refused, and the approval stands | `…a_rejection_of_a_snapshot_approved_since_it_was_read_is_refused_and_the_approval_stands` | no: run one after the other, the old `decide` re-read and refused it too. The concurrent shape is proved non-vacuous by M1 |
| A revoked read before re-approval and a second withdrawal is refused | `…an_approval_read_before_a_later_withdrawal_of_the_same_state_is_refused` | added after the first GREEN; proved non-vacuous by M2 |
| An undisturbed decision lands | `…a_decision_on_the_state_it_read_still_lands` | regression armor |

**Existing tests changed:** `MarketplaceUrlChangeTests` and
`MarketplaceRemovalTests` called `decide` with an id. They now pass the
snapshot record they already hold. The assertions are unchanged.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1112, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 08:45 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)`, on the second run (see below) |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (2.0m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `344/344 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.78 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-decide-compare-and-set/mutants.sh` | `3 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-no-state-compare killed
M2-no-stamp-compare killed
M3-decide-rereads killed
3 mutants run, all killed
```

## What went wrong, and how it was resolved

- **The first `pnpm verify` run** timed out one jsdom test,
  `webhooks.test.tsx > add_subscriber_is_disabled_until_the_name_and_url_are_valid`,
  at 5157 ms against its 5000 ms limit. Load average was 11 at the time, other
  sessions are running builds, and this change touches no frontend file. The
  rerun passed 234 of 234.
- **Two hand-written mutants were equivalent, and both were dropped:**
  - Removing the early refusal in `doApprove`: `decide` refuses the same
    undecidable snapshot on its own.
  - The first form of "re-read before comparing": the compare-and-set still
    compared the expected state.

  M3 now reproduces the old bug in full, re-reading the row and comparing
  against that, and it is killed. The early refusal in `doApprove` is kept as
  defence in depth, and it is not separately testable.

## Known limits

- This makes the row transition atomic. Serialising the publication that
  follows against a concurrent withdrawal is the per-marketplace publication
  lock, a separate change (design.md, Not in this change).
- No test runs two threads against `doApprove`. The race is pinned at the
  repository seam, by deciding on a read taken before the intervening change.
