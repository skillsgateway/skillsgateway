# Evidence: pluggable-git-storage

Scope of this report: **task 2.1 only** — the conditional-write fidelity spike.
The DFS backend itself is not implemented, so this is not the change's final
evidence report; it is the evidence for the decision gate the plan hangs on.

Commit: `HEAD` of `feat/pluggable-git-storage` at the time of writing (see the
PR's commit list; the run below is the final fresh run after the last edit).

## What was proved, and why it needed proving

The object-store backend's entire consistency model reduces to one behaviour: a
conditional `PutObject` must fail when its precondition no longer holds. If the
local test double does not implement that faithfully, every concurrency test
written later is green and meaningless. `ConditionalWriteFidelityTests` tests the
double before anything trusts it.

Target: **Floci 1.5.28** (`docker.io/floci/floci:latest`, digest-dated
2026-06-26), a local AWS emulator self-described as an open-source alternative to
LocalStack Community, on the LocalStack-compatible edge port 4566, driven through
Testcontainers 2.0.5 with the AWS SDK v2 S3 client (`software.amazon.awssdk:s3`
2.46.7) the implementation would use.

| # | Assertion | Result |
| --- | --- | --- |
| 1 | `If-Match` with the current ETag succeeds and returns a **new** ETag | **PASS** |
| 2 | `If-Match` with a stale ETag returns **412**, and the stored object is byte-for-byte unchanged | **PASS** |
| 3 | `If-None-Match: *` creates exactly once; the second attempt returns 412 and does not overwrite | **PASS** |
| 4 | 8 threads racing off one barrier from one base ETag → exactly **1** winner, **7** × 412, zero other errors | **PASS** |
| 5 | ETags chain: the ETag a conditional PUT returns is the one the next must present, over 5 generations, each superseded ETag ceasing to be accepted | **PASS** |

Assertion 2 checks the *content* after the refusal, not just the status code: an
emulator that answers 412 and writes anyway is the worst possible outcome and is
exactly what a status-only assertion misses. Assertion 4 uses real threads off a
`CountDownLatch`, because sequential calls cannot distinguish a correct
implementation from one that ignores preconditions but happens to be ordered.

## Proving the spike can fail

A green suite against a test double proves nothing by itself, so the suite was
mutated twice and re-run.

**Mutation A** — drop `If-Match` from the stale write in assertion 2:

```
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
Expecting code to raise a throwable.
```

The unconditional write succeeds where the conditional one was refused, so the
412 is caused by the precondition and not by the emulator refusing writes
generally.

**Mutation B** — drop `If-Match` from the concurrent writers in assertion 4:

```
[ERROR]   ConditionalWriteFidelityTests.exactlyOneConcurrentWriterWins:216
  [compare-and-swap means exactly one writer commits from a given base version]
  expected: 1L
   but was: 8L
```

Eight winners instead of one — precisely the signature of a store that ignores
preconditions, and precisely what the suite exists to catch. The original was
restored before the gate run below.

## Repeatability

The suite was run three times against a fresh container; `Tests run: 5,
Failures: 0, Errors: 0` each time (2.081 s, 1.440 s, 1.505 s). Assertion 4 is
concurrent and therefore the flakiness candidate; it did not vary.

## Conclusion

The plan survives unchanged. Task 2.2's fallback is **not** triggered: the
conditional-write contract stays in the default `verify` and the offline build
stays offline. No upstream contribution is needed.

The honest limit: this verifies *Floci*, not AWS S3. It shows the design is
implementable and that our tests are meaningful; it does not certify the
production target. Task 2.3 (the same five assertions against real S3 and MinIO)
stays open, and the supported-store list is not publishable until it closes.

## Gate run

Final fresh run after the last edit. `TESTCONTAINERS_RYUK_DISABLED=true` and an
explicit `DOCKER_HOST` were exported (see "Environment" below).

```
$ ./mvnw clean verify
[INFO] Tests run: 187, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

(182 before this change; the 5 added are the spike.)

```
$ (cd src/main/frontend && pnpm test:stories)
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

```
$ (cd src/main/frontend && pnpm e2e)
  12 passed (26.1s)
```

```
$ reqstool status local -p docs/reqstool
106/106 complete · 0 incomplete · PASS
```

```
$ openspec validate --all --strict
Totals: 27 passed, 0 failed (27 items)
```

```
$ mkdocs build --strict
INFO    -  Documentation built in 0.60 seconds
```

## Environment

- `TESTCONTAINERS_RYUK_DISABLED=true` is required on this machine — Ryuk cannot
  start on rootless Podman here.
- `DOCKER_HOST` **was** required, contrary to expectation.
  `~/.testcontainers.properties` names the correct socket
  (`unix:///var/folders/…/podman/podman-machine-default-api.sock`, matching
  `podman machine inspect`), but Testcontainers 2.0.5 did not consult it: the
  failure listed only `UnixSocketClientProviderStrategy` and
  `DockerDesktopClientProviderStrategy` as attempted, with no
  environment/property strategy among them. Exporting the same path as
  `DOCKER_HOST` resolved it immediately
  (`Found Docker environment with Environment variables, system properties and
  defaults`). This was a configuration-precedence issue in Testcontainers 2.x,
  **not** the memory pressure that was suspected — the Podman VM reports 1942 MB
  and no OOM or startup timeout occurred in any run.
- Containers were reaped with `podman container prune -f` afterwards, since
  nothing reaps them with Ryuk disabled.

## Dependencies added

Both test-scoped, for the spike and for the backend that follows:
`software.amazon.awssdk:bom` 2.46.7 (imported), `software.amazon.awssdk:s3`,
`org.testcontainers:testcontainers`.
