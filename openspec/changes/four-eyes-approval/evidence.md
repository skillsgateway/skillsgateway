# Evidence: four-eyes-approval

One fresh run of all six gates after the last code edit, plus the RED-before-GREEN
record and the mutation gauntlet the trust-boundary discipline calls for.

Commit: `e4077ecb69da661a0def54d17a6a793684c92384`

Tier 3 (old-coder): this change decides who may publish content through the only
publisher in the system. Spec approval: the OpenSpec proposal, design and tasks
were written and approved before implementation; the deltas implementation
forced on that design are recorded in `design.md` under *Implementation notes —
where the design met the code*, and were not approved in advance.

## `./mvnw clean verify`

```
    [INFO] BUILD SUCCESS
    [INFO] ------------------------------------------------------------------------
    [INFO] Total time:  01:31 min
    [INFO] Finished at: 2026-08-28T11:51:59+02:00
```

193 backend tests, 0 failures — 11 of them new (`FourEyesEnforceTests` 7,
`FourEyesTests` 4). The UI gate (typecheck, oxlint, vitest, reqstool tags) runs
inside it.

## `(cd src/main/frontend && pnpm test:stories)`

```
     Test Files  3 passed (3)
          Tests  6 passed (6)
```

## `(cd src/main/frontend && pnpm e2e)`

```
      13 passed (33.3s)
```

12 before this change; the new one is
`the_approve_dialog_warns_that_the_reviewer_supplied_the_content_and_still_allows_it`.

## `reqstool status local -p docs/reqstool`

```
    108/108 complete · 0 incomplete · PASS
```

106 before; GW_0096 and GW_0097 are the two new ones.

## `openspec validate --all --strict`

```
    ✓ change/four-eyes-approval
    Totals: 26 passed, 0 failed (26 items)
```

## `mkdocs build --strict`

```
    INFO    -  Documentation built in 0.74 seconds
```

## RED before GREEN

The rule was written as a stub — `FourEyesGate.conflicts(...)` returning
`List.of()` — with the columns, the configuration, the exception, the endpoint
and the ledger wiring already in place, so that every new test failed on
**behaviour** rather than on compilation. A collection error is a weaker RED
than an assertion failure.

```
    [ERROR] Tests run: 7, Failures: 4, Errors: 0 -- in FourEyesEnforceTests
    [ERROR] Tests run: 4, Failures: 1, Errors: 0 -- in FourEyesTests
    [ERROR]   FourEyesEnforceTests.theApprovalEndpointRefusesWithAConflictProblemNamingTheRoles:218
              JSON path "$.refused" expected:<true> but was:<false>
    [ERROR]   FourEyesEnforceTests.theAuthorOfAWaiverTheApprovalReliesOnCannotApprove:149
              Expecting code to raise a throwable.
    [ERROR]   FourEyesEnforceTests.theIngestionActorCannotApproveTheSnapshotTheyIngested:92
              Expecting code to raise a throwable.
    [ERROR]   FourEyesEnforceTests.theMarketplaceRegistrantCannotApproveItsSnapshots:124
              Expecting code to raise a throwable.
    [ERROR]   FourEyesTests.aConflictedApprovalProceedsAndTheConflictIsRecordedBesideIt:75
              JSON path "$.conflicts.length()" expected:<2> but was:<0>
    [ERROR] Tests run: 11, Failures: 5, Errors: 0, Skipped: 0
```

Filling in the detection turned the same command green:

```
    [INFO] Tests run: 7, Failures: 0, Errors: 0 -- in FourEyesEnforceTests
    [INFO] Tests run: 4, Failures: 0, Errors: 0 -- in FourEyesTests
    [INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
```

Six of the eleven passed against the stub, and that is reported rather than
hidden. Five are negative cases — an independent reviewer is not blocked, an
unrelated waiver is not a conflict, the automated triggers never conflict,
rejection is not gated, `warn` is the default — which a rule that detects
nothing satisfies trivially. They are not evidence on their own; what makes
them load-bearing is that mutants M1–M5 below kill them. The sixth,
`registrationAndIngestionOverHttpRecordTheActingIdentity`, passed because the
actor plumbing was already in place when it was written; mutants M6 and M7
prove it is not vacuous by stopping each actor from being persisted and
watching it fail.

## Adversarial pass — mutation gauntlet

`bash openspec/changes/four-eyes-approval/mutation-run.sh` (persisted in this
change, so the report is reproducible from the repository alone). Eight
hand-written mutants, each a bug someone could plausibly introduce while leaving
the code looking correct:

```
    killed    M1 non-human actors no longer excluded
    killed    M2 enforce mode never throws
    killed    M3 waiver authorship no longer conflicts
    killed    M4 registrant no longer conflicts
    killed    M5 approval ignores the gate
    killed    M6 ingestion actor not persisted
    killed    M7 registrant not persisted
    killed    M8 warn mode writes no ledger entry
    ----
    8/8 mutants killed
```

The runner is home-grown, so its own pass is not taken on trust. Both of its
failure paths were exercised against known-bad input:

```
    $ MUTATION_NEGATIVE_CONTROL=survivor bash .../mutation-run.sh
    SURVIVED  NC1 comment-only edit (must survive)
    negative control only: 0/1 killed, 1 survived
    exit=1

    $ MUTATION_NEGATIVE_CONTROL=anchor bash .../mutation-run.sh
    FATAL: mutant anchor did not apply in .../FourEyesGate.java
    exit=2
```

Be precise about what that buys: it proves the runner reports an unkilled mutant
and refuses a mutant it never applied. It does not prove the eight mutants are
the eight bugs that matter.

The mutants are attributed to the suite as a whole, not to any one test —
whichever test fails first scores the kill.

## What the refusals were checked to leave behind

Every enforce-mode refusal asserts more than the exception. `assertUntouched`
checks that the snapshot is still `held`, that `decidedBy` and `decidedAt` are
still null, that `GitStorage.publishedIfServing` is empty, and that a real
`git clone` through the facade fails. A gate that refused *after* publishing
would look identical from the caller's seat.

## Known limits

- **Identity comparison is exact string equality.** Two spellings of the same
  human — a different principal claim, a different provider — are two identities
  to this rule. Stated in the configuration reference rather than papered over:
  a separation-of-duties rule that over-matches locks reviewers out of content
  they had nothing to do with, which is its own failure mode.
- **The e2e covers `warn`, not `enforce`.** The acceptance deployment is one
  gateway with one mock-IdP identity; `enforce` there would leave no identity
  able to approve and would break every other e2e. The enforced refusal is
  verified over HTTP against a second Spring context configured to `enforce`.
- **No second-approval queue.** This refuses a conflicted approval; it does not
  require two approvals of an unconflicted one. Recorded as a non-goal in the
  design and under *What is not a boundary yet* in the trust-boundaries page.
- **No backfill.** Marketplaces and snapshots that predate this release carry
  null actors and never conflict, so the rule tightens only from deployment
  forward.
- **Independent verification was not performed.** Tier 3 permits it; it was not
  run, and the report claims correspondingly less.

## Notes

- No new enumerated column, so the native-PostgreSQL-enum convention has nothing
  to apply to here: `registered_by` and `ingested_by` hold free-form principal
  strings, and the warn/enforce mode is configuration, never persisted. No new
  `CHECK (col IN (...))` constraint was introduced either.
- The migration is folded into `V1__init.sql` rather than added as `V2`, per the
  repository's single-migration convention.
- One pre-existing Base UI console warning remains in
  `marketplace-detail.test.tsx`; it is on `main` and untouched by this change.
- The `SbomTests` failure and the `SessionCredentialExpiryTests` context failure
  seen during development were environmental — an offline (`-o`) build skips the
  CycloneDX plugin, and the local Podman VM has 2 GB of memory, which is not
  enough for the per-context PostgreSQL containers. Neither reproduces in the
  fresh run above.
