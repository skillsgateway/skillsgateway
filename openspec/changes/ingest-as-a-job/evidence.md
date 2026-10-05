# Evidence: ingest-as-a-job

One fresh run of every gate after the last code edit, on commit `9037ae303ea85265d996da07c11629e4994b6a0c`
(Linux, JDK 25, Docker).

## `./mvnw clean verify`

```
[INFO] Tests run: 1073, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
```

The first run on `0d1481d5` failed `SweepLeaseDisciplineTests`: the heartbeat
was a `@Scheduled` method without a lease. It now runs on its own timer
(`9037ae30`, design D3), and this run is on that commit.

## `pnpm test:stories`

```
 Test Files  19 passed (19)
      Tests  100 passed (100)
```

The two runs before it failed to import some story files ("Failed to fetch
dynamically imported module": Vite optimising dependencies on a cold cache).
Every story that loaded passed. This third run, on the same tree, loaded all
of them.

## `pnpm e2e`

```
  25 passed (1.7m)
```

## `reqstool status local -p docs/reqstool`

```
GW_INGEST_0066 … 6 tests, 6 passed
GW_INGEST_0067 … 5 tests, 5 passed
Passed tests 1082 · Failed tests 0 · Skipped 0 · SVCs missing tests 0 · SVCs missing MVRs 0
```

## `openspec validate --all --strict`

```
Totals: 31 passed, 0 failed (31 items)
```

## `mkdocs build --strict`

```
INFO    -  Documentation built in 1.68 seconds
```

## Tests shown failing

Each `IngestJobTests` case was run against a deliberately broken copy of the
code (a mutant), and each mutant was killed:

| Mutant | Killed by |
| --- | --- |
| Claim ignores a live ingest | `an_ingest_answers_before_the_upstream_does…`, `a_live_ingest_is_not_claimed_over` |
| An earlier attempt may move the stage | `an_earlier_attempt_neither_moves_nor_clears…` |
| An earlier attempt may clear the stage | the same test, through the running-ingest CHECK |
| Heartbeat staleness ignored | `an_ingest_whose_heartbeat_lapsed_reads_interrupted…` |
| Snapshot not linked to the outcome | four tests |
| Heartbeat renews nothing | `a_running_ingest_renews_its_heartbeat` |

## API contract

`oasdiff breaking` against `main`: one change,
`response-success-status-removed` on `POST /api/v1/marketplaces/{name}/ingest`
(`201`). This is the declared break.
