# Evidence: corpus-aware-vetting

One fresh run of all six gates after the last code edit, on commit
`3a99bf9bb316910e81bce9ff50544b8cda425815` (branch `feat/corpus-aware-vetting`),
2026-09-23, in a separate git worktree with its own `pnpm install`.

**One local departure, stated plainly.** Until #478 merges, `AuditBrowseTests`
and `VettingChainEstateGovernanceTests` fail deterministically in a full local
run on `main` as well — shared database, machine-specific class order. They were
the only two failures in a first full run of this branch (767 tests, 2
failures). For this evidence run #478's test diff
(`git diff origin/main origin/test/order-independent-audit-and-chain-tests -- src/test`,
74 lines, those two files only) was applied **uncommitted**, and removed again
afterwards. Nothing in this change touches either file.

## Gates

### `./mvnw clean verify`

```
[INFO] Tests run: 767, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  18 passed (18)
[INFO]       Tests  160 passed (160)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:01 min
```

Spotless and Checkstyle ran in the same build. The suites this change adds:

```
Tests run: 6,  Failures: 0 -- NameNormalizerTests
Tests run: 5,  Failures: 0 -- SnapshotFactsPersistenceTests
Tests run: 12, Failures: 0 -- NameCollisionTests
Tests run: 1,  Failures: 0 -- NameCollisionRaceTests
Tests run: 1,  Failures: 0 -- ChainPurityTests
```

Ratchets, from `target/context-budget.txt` and `target/config-surface.txt`:
`distinct application contexts: 27` (budget raised 26 → 27) and
`configuration leaves: 108` (budget raised 107 → 108). The argument is in
`proposal.md`, under "Why the surface grows".

### `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  12 passed (12)
      Tests  62 passed (62)
```

The Storybook cache was deleted after `mvnw clean verify`, as the known harness
flake requires. This run passed first time. In the run before it, on the
previous commit, the same gate needed three attempts: the first two failed with
`Failed to fetch dynamically imported module` on story files this change does
not touch, as well as on the new one.

### `(cd src/main/frontend && pnpm e2e)`

```
  21 passed (1.2m)
```

Against the jar the verify above packaged. The e2e gateway runs with the rule
switched off (see `run-e2e.sh`): its specs register one fixture repeatedly as
separate marketplaces.

### `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
268/268 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 30 passed, 0 failed (30 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 1.37 seconds
```

Exit 0.

## Old-coder gauntlet (trust boundary: `ApprovalService`)

**Spec approval: not obtained (autonomous run).** The owner's decisions recorded
in ADR 0015 were the input; the requirements and design here are the spec, to
be reviewed after the fact.

**RED.** `NameNormalizerTests` ran against a throwing stub first (6/6 errored)
and then drove the implementation. It also caught a real defect: a single
normalisation order cannot equate both `cIaude-skills` and `CLAUDE-SKILLS` with
`claude-skills`, which is why a name has two keys. The rest of the gate was
written before its tests, so each of those tests was shown to be able to fail
against a mutant instead (below). That is a weaker RED than test-first, stated
as such.

**The race test** (`NameCollisionRaceTests`, the one ADR 0015 names as deciding
whether the design survives). It forces the race rather than hoping for it: a
separate connection holds a row lock on both snapshots, and the test releases
it only once both approvals are waiting in `pg_stat_activity`. With the
advisory-lock guard removed:

```
[approved] Expected size: 1 but was: 2
```

With the guard: one approved and served, one refused as a collision with it,
still held, the refusal on the ledger.

**Manual mutation** — each applied alone to the source, the named suite run,
source restored. 13 of 13 killed:

| Mutant | Killed by |
| --- | --- |
| F1 policy gate rebuilds facts instead of reading the recorded ones | `SnapshotFactsPersistenceTests.the_policy_gate_decides_on_the_recorded_facts_not_on_a_fresh_build` |
| F2 state stored in the facts | `…ingestion_records_the_facts_without_the_state_…` |
| F3 current state not supplied on read | `…the_state_read_back_is_always_the_current_one` |
| F4 ingestion does not record | `…ingesting_the_same_commit_again_rewrites_nothing` and three more |
| C1 names the marketplace already carries are checked again | `NameCollisionTests.a_later_snapshot_of_the_incumbent_is_not_flagged_…` |
| C2 the check outside the lock removed (four-eyes loses the collision waiver) | `…a_waiver_the_reviewer_wrote_is_a_four_eyes_conflict_…` |
| C3 waivers never consulted | `…the_report_agrees_with_the_gate_…` and five more |
| C4 the in-transaction evaluation skipped | `NameCollisionRaceTests` |
| C5 an unreadable inventory passes | `…a_snapshot_whose_plugin_names_cannot_be_read_…` |
| C6 refusal not written to the ledger | `…the_report_agrees_with_the_gate_and_the_refusal_is_on_the_ledger` |
| N1 only the skeleton-first key | `NameNormalizerTests.case_separators_and_the_adrs_own_examples_…` |
| N2 invisible format characters kept | `…compatibility_forms_and_invisible_format_characters_…` |
| V1 a run's recorded chain depends on the estate | `ChainPurityTests` |

**Layers not run:**

- Changed-line coverage: the build has no coverage tool; not added.
- Property-based tests: none; the normaliser has an idempotence test over fixed inputs.
- Independent verification: not performed.
- `/impeccable audit` and `harden` on the approve dialog: not run. The change
  adds a section to an existing dialog and reuses the existing waiver form.

**Known limits.** Estate queries do not yet exclude marketplaces removed by
#477, which is not merged. A snapshot approved while the rule is switched off
whose plugin names could not be read at ingestion or at approval is an incumbent
the rule cannot see once it is switched back on (logged at the time).
