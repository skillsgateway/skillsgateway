# Evidence: jdbcclient-improvements

Source state: commit `9410c3b6c4748be6d1663e87a67bf86a9d1dbc83` on
`refactor/jdbcclient-improvements`, in the worktree `.claude/worktrees/jdbcclient`.
Every number below comes from one final fresh run of every gate, executed after
the last code edit. The container-backed gates ran under the run's shared gate
mutex, one at a time.

```sh
export TESTCONTAINERS_RYUK_DISABLED=true
export DOCKER_HOST=unix:///var/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock
```

## Gates

### 1. `./mvnw clean verify`

```
[INFO] Tests run: 596, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  03:03 min
```

Zero `proxy already running` container-start errors in this run.

### 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

### 3. `(cd src/main/frontend && pnpm e2e)`

```
Running 13 tests using 1 worker
  13 passed (42.2s)
```

### 4. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
165/165 complete · 0 incomplete · PASS
```

### 5. `openspec validate --all --strict`

```
Totals: 32 passed, 0 failed (32 items)
```

### 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 0.90 seconds
```
(exit 0)

## Retries, recorded honestly

The machine ran up to ten concurrent Maven builds from parallel agents against
one rootless podman. Three earlier attempts at gate 1 failed on the same
environmental cause and are recorded here because a genuinely flaky test looks
identical from the outside:

| attempt | result | cause, followed to the bottom of the chain |
| --- | --- | --- |
| 1 (unlocked) | 595 run, 0 failures, 25 errors | every error `Failed to load ApplicationContext` → `ContainerLaunchException` → `InternalServerErrorException: Status 500 {"cause":"…\"proxy already running\""}` |
| 2 (unlocked) | 596 run, 0 failures, 10 errors | same, verbatim |
| 3 (old mutex) | 596 run, 0 failures, 3 errors (`DevAuthTests`) | same, verbatim |
| 4 (FIFO queue) | **596 run, 0 failures, 0 errors, 0 blips** | — |

**No test failed in any of the four attempts.** Every error in attempts 1–3 was a
container that could not be started, never an assertion; the counts fall
monotonically as contention falls. Nothing was weakened, skipped or deleted to
reach the clean run — the diff between attempt 3 and attempt 4 is the machine,
not the branch.

## RED, before the fix

The N+1 test was observed failing against the unfixed repository before
`VettingRepository.verdicts()` was touched:

```
[ERROR] VettingRunReadTests.reading_a_run_costs_the_same_statements_whatever_the_chain_length
[reading a 12-verdict run must not cost more statements than a 1-verdict run (14 vs 3)]
expected: 3
 but was: 14
```

The **first** version of that test passed immediately, which is the more useful
fact and is recorded rather than quietly fixed: the counter had been wired to
`JdbcTemplate.execute(PreparedStatementCreator, PreparedStatementCallback)`,
which the framework never calls — its real funnel is a private overload — so the
assertion was satisfied by `0 == 0` against unfixed code. The test now counts at
the JDBC layer and carries a control assertion that the count is greater than
zero, so that failure mode cannot recur silently.

## Mutation gauntlet

No mutation tool is configured in this project, so the manual procedure was used:
five plausible bugs, each applied to the source alone, run against the test class
that should notice it, and reverted with `git checkout HEAD -- <path>`. The exact
edits are below, so the pass is reproducible from the repository.

| # | Mutation | Test class | Result |
| --- | --- | --- | --- |
| M1 | `WHERE v.run_id = :runId` → `WHERE v.run_id = v.run_id AND :runId = :runId` | `VettingRunReadTests` | KILLED (3 run, 1 failed) |
| M2 | `ORDER BY f.id` → `ORDER BY f.id DESC` | `VettingRunReadTests` | KILLED (3 run, 1 failed) |
| M3 | group key `rs.getLong("verdict_id")` → `rs.getLong("id")` | `VettingRunReadTests` | KILLED (3 run, 1 failed) |
| M4 | `findings.getOrDefault(verdict.verdictId(), List.of())` → `List.of()` | `VettingRunReadTests` | KILLED (3 run, 1 failed) |
| M5 | `RETURNING *` → `RETURNING … COALESCE(marketplace_id, 0) AS marketplace_id …` in `ConnectorToggleRepository.set` | `DataClassRowMapperTests` | KILLED (7 run, 1 failed) |

**M1 survived the first round.** Grouping by verdict id makes the run filter
redundant for *correctness* — the returned value is identical — while the read
scans every finding in the table. That is the same growth curve this change
exists to close, measured in stored data instead of chain length, so it was not
classified as an equivalent mutant. The test was strengthened instead: the proxy
now counts rows the driver hands back as well as statements it prepares, and the
test reads the same short run twice, once alone and once after a longer run
exists beside it. M1 is killed on the final source.

M3 and M5 each hit the container blip once and were rerun; both were killed on
the rerun.

## The two declines, and what was measured to reach them

### Part 1 — implicit enum casts

A throwaway test created and dropped the cast against the real database:

```
A. UPDATE snapshots SET state = :state WHERE id = -1        (bound varchar, no cast)
   PSQLException: ERROR: column "state" is of type snapshot_state but expression is of type character varying
B. SELECT count(*) FROM snapshots WHERE state = :approved   (bound varchar, no cast)
   PSQLException: ERROR: operator does not exist: snapshot_state = character varying
C. EXPLAIN … WHERE state = 'approved'::snapshot_state AND deleted_at IS NULL
   Index Scan using idx_snapshots_revet_queue on snapshots  (cost=0.12..8.14 rows=1 width=284)

D. CREATE CAST (varchar AS snapshot_state) WITH INOUT AS IMPLICIT
D-A. the same UPDATE                                        accepted
D-B. the same SELECT                                        PSQLException: ERROR: operator does not exist: snapshot_state = character varying
D-C. EXPLAIN … WHERE state = 'approved' AND deleted_at IS NULL
     Index Scan using idx_snapshots_revet_queue on snapshots  (cost=0.12..8.14 rows=1 width=284)

E. UPDATE snapshots SET state = 'not-a-member-of-the-set' WHERE id = -1  (bound varchar, with the cast)
   ACCEPTED — planned cleanly, matched no rows, raised nothing
F. SELECT … JOIN … ON s.state = m.name                       still refused (snapshot_state = text)
```

D-B is the finding: an implicit varchar→enum cast does not supply the `=`
operator for `enum = varchar`, so every guarded predicate keeps its cast. Four of
the nine `::snapshot_state` casts are writes; five are predicates, and the five
include `undecide` and `revoke`. E is the error class the change would introduce:
an invalid label's rejection moves from planning time to row-execution time.
Declined; see `design.md`.

### Part 3 — batching the insert loops

30 repetitions each after a warm-up, `snapshot_closure_members`, loop versus
`JdbcTemplate.batchUpdate`:

```
### members=1    loop=0.91ms  batch=1.16ms  saved=-0.25ms
### members=5    loop=4.91ms  batch=1.35ms  saved=3.57ms
### members=20   loop=14.70ms batch=1.64ms  saved=13.06ms
### members=100  loop=51.10ms batch=4.49ms  saved=46.61ms
```

Declined; see `design.md`.

## Layers skipped, and why

- **Tool-based mutation testing** — no mutation tool is configured (no PIT). The
  manual procedure above was used instead, with each mutant and its result named.
- **Property-based tests** — nothing here has the round-trip or algebraic shape
  properties are for; the mapping behaviours are pinned as explicit regressions in
  `DataClassRowMapperTests` instead.
- **Supply chain / secrets** — no dependency changed and no credential is touched.
- **Randomised suite ordering** — the project does not configure it, and the new
  tests build their own fixtures with `uniqueName(...)` rather than depending on
  order.
- **Static types and lint** are not separate lines above because they are inside
  gate 1: Spotless (palantir-java-format), Checkstyle and `tsc`/oxlint all run
  within `./mvnw clean verify`, which passed.

## Confidence, stated honestly

- **Spec approval: not obtained (autonomous run).** The OpenSpec change is the
  spec and was written before implementation, but no human reviewed it before the
  code existed, so the correlation-breaking review did not happen. The claim is
  reduced accordingly.
- **Independent verification: not performed.**
- The isolated worktree ran the full suite itself, so there is no gap between the
  tree that was tested and the tree that lands beyond ignored build output.
- **What this evidence does not show.** The mapper rollout changes no SQL, so the
  guarded updates' behaviour is verified only by the existing SVC tests that
  already covered them (revocation, re-decision, retention, token lifecycle) plus
  the new negative test on the three nullable columns. It is not a concurrency
  proof, and none was attempted, because no concurrency-relevant statement text
  changed.

## The mutants, exactly

Reproducible by applying each edit alone and running the named test class.

**M1, M2** — `src/main/java/dev/skillsgateway/server/vetting/VettingRepository.java`,
in `findingsByVerdict`:

```
- + " WHERE v.run_id = :runId ORDER BY f.id")
+ + " WHERE v.run_id = v.run_id AND :runId = :runId ORDER BY f.id")     (M1)
+ + " WHERE v.run_id = :runId ORDER BY f.id DESC")                       (M2)
```

**M3, M4** — same file, in `findingsByVerdict` and `verdicts`:

```
- rs.getLong("verdict_id"),
+ rs.getLong("id"),                                                      (M3)

- findings.getOrDefault(verdict.verdictId(), List.of())))
+ List.<Finding>of()))                                                   (M4)
```

**M5** — `src/main/java/dev/skillsgateway/server/vetting/ConnectorToggleRepository.java`,
in `set`:

```
- + " RETURNING *")
+ + " RETURNING id, connector, COALESCE(marketplace_id, 0) AS marketplace_id,"
+ + " enabled, reason, updated_by, updated_at")
```
