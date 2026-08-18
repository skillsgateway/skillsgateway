# Evidence: add-minimum-release-age

One final fresh run of every gate after the last code edit. Commit SHA at the
bottom.

## Discipline notes

This change touches `ApprovalService` — a trust boundary — so the
`old-coder` discipline applies: the load-bearing assertions were each shown to
be able to fail.

Non-vacuity mutants (each killed, then reverted — verified with `git diff`):

| Mutant | Killed by |
| --- | --- |
| Boundary comparison `!now.isBefore(eligibleAt)` → `now.isAfter(eligibleAt)` (off-by-one at the exact boundary) | `ReleaseAgeGateTests.eligibility_turns_over_exactly_at_the_boundary_instant` |
| The gate removed from the approval path (`ingestionAge = Duration.ZERO` instead of `requireReleaseAge(...)`) | 4 of the 6 `MinimumReleaseAgeTests` |
| Re-ingestion of a known commit creates a new snapshot row instead of returning the existing one (the clock-reset hole) | `MinimumReleaseAgeTests.re_ingesting_the_same_commit_does_not_restart_the_clock` |
| The portal's approve control no longer disabled on ineligibility | `marketplaces.test.tsx > approve_is_disabled_with_the_remaining_time_inside_the_cooling_off_window` |

Adversarial coverage beyond the happy path:

- A commit whose committer date claims 400 days of age, ingested moments ago, is
  refused exactly as a fresh one — the gate never reads upstream commit
  metadata.
- Re-ingestion of the same commit leaves the first-sighting instant untouched,
  so a re-push cannot restart the window. Verified against the real ingestion
  path, not a stub.
- The refusal is proven to precede the transition: the snapshot is still `held`
  and a real `git clone` of the facade fails, so nothing was published.
- Rejection is proven not to be age-gated.
- The exactly-at-the-boundary case is verified by evaluating the rule at
  `firstSeen + minimum` rather than by racing a clock, so the `>=` versus `>`
  distinction is actually asserted.
- The zero default is asserted implicitly by the whole rest of the suite: every
  other test approves a snapshot the instant it is ingested, and all 87 pass
  unchanged.

Known, documented limits (in `design.md`): a retention *purge* of a
non-approved snapshot followed by re-ingestion is a genuinely new first
sighting; clock skew between instances can shift eligibility by moments.

## Gates

### `./mvnw clean verify`

```
[INFO] BUILD SUCCESS
[INFO] Total time:  01:07 min
surefire aggregate: tests=126 failures=0 errors=0 skipped=0
```

### `(cd src/main/frontend && pnpm e2e)`

```
  11 passed (24.7s)
```

### `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
91/91 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 23 passed, 0 failed (23 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 0.59 seconds
```

## Commit

All five gates above were run on the rebased branch after its last code edit.
The implementation commit is
`4d93672` (`feat(approval): global minimum release age before a snapshot can be
approved`), rebased onto `5af1e40`; the run was taken at the branch tip with the
OpenSpec change already archived. The only change after the run is the text of
this report, which nothing executes.

The branch was rebased onto a main that had moved twenty commits, including the
CEL policy gate (#97) in the very method this change touches. The age gate now
sits after that gate, for the same reason it sits after the vetting gate: a
policy denial names a rule someone can take up now, while this refusal only says
how long to wait. The three-gate ordering, and the ledger-refusal path reusing
the marketplace the merged method already resolves, are the only substantive
changes the rebase required; the gate run above is entirely post-rebase.
