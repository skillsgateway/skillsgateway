# Evidence: snapshot-decision-first-layout

One fresh run of every gate after the last code edit, on commit `84fc5b51`.

## Gates

| Gate | Command | Result tail |
| --- | --- | --- |
| Java + UI + jar | `MAVEN_OPTS="-Xmx3g" ./mvnw clean verify` | `Tests run: 742, Failures: 0, Errors: 0, Skipped: 9` · vitest `Tests 160 passed (160)` · `BUILD SUCCESS` |
| Storybook | `pnpm test:stories` | `Test Files 11 passed (11)` · `Tests 57 passed (57)` |
| e2e | `pnpm e2e` | `21 passed (1.3m)` |
| Traceability | `reqstool status local -p docs/reqstool` | `259/259 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 31 passed, 0 failed (31 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.69 seconds` |

## The new tests fail without the code they guard

Each mutation was applied to the finished code, the detail-page suite run, and
the code restored.

| Mutation | Test that failed |
| --- | --- |
| Decision row moved above the tabs | `approve_is_never_rendered_above_the_evidence_it_rests_on` |
| Blocked vetting no longer disables Approve | `a_blocked_snapshot_cannot_be_approved_from_the_card_and_says_why` |
| Serving inferred as "newest approved" instead of `servedSha` | `serving_nothing_while_an_approved_row_exists_says_nothing_is_served` |
| Every snapshot rendered as an open card | `page_length_does_not_grow_with_the_number_of_snapshots`, `two_snapshots_awaiting_…`, `a_deep_link_…`, `revoked_snapshot_…` |
| Note on other awaiting snapshots dropped | `two_snapshots_awaiting_are_both_listed_and_the_open_one_names_the_other` |

On the backend, `ServedCommitTests` fails when `servedSha` is derived from the
newest approved snapshot (recorded with the served-commit commit `10c3aee5`).

`snapshot-files.test.tsx` (19 tests) passed unchanged after the explorer was
extracted into `SnapshotExplorer`, which is the evidence the route wrapper
kept its behaviour.

## Page height

Measured on the live instance at 1440 px wide, `clean-demo` (one approved,
two held):

| | Before | After |
| --- | --- | --- |
| Snapshot area | ~660 px **per snapshot** (3,220 px page for two) | 975 px for three, flat in snapshot count |
| Whole page | 3,220 px | 3,100 px |

The whole-page figure moves less than the snapshot area because two sections
this change does not touch stay: the administrator's vetting-chain card
(568 px) and the marketplace's audit log (880 px, which grows with events, not
snapshots). The regression test pins the part this change owns — one card, one
vetting report, history collapsed — at twelve snapshots.

## Design harness

`/impeccable audit`: the detector reports nothing on the changed files. One
finding fixed: the five-tab strip could outrun a phone's width, and now scrolls
within itself. Controls at 25–28 px follow the design system's `sm` sizes used
across the portal.
