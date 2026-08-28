# Evidence — native-postgres-enum-types

One fresh run of every gate after the last edit.

**Commit:** `061255d` (`refactor(db): persist enumerated values as native PostgreSQL enum types`)

Named as the implementation commit rather than this report's own, which cannot contain its
own hash. Every file the change touches outside
`openspec/changes/native-postgres-enum-types/` is at its final state as of `061255d`.

`DOCKER_HOST` was exported from `podman machine inspect` and `TESTCONTAINERS_RYUK_DISABLED=true`
set for the runs that need a container runtime — Ryuk cannot start under rootless Podman on
this machine, and the ambient socket path does not resolve.

## Red before green

Two deliberate breaks, each run against `NativeEnumColumnTests` alone and then restored.

**1. Drop one write cast** — `:state::snapshot_state` → `:state` in
`SnapshotRepository.create`:

```
[ERROR] Tests run: 9, Failures: 0, Errors: 2, Skipped: 0 <<< FAILURE! -- in dev.skillsgateway.server.NativeEnumColumnTests
[ERROR] NativeEnumColumnTests.every_vetting_outcome_verdict_state_and_severity_round_trips -- ERROR!
Caused by: org.postgresql.util.PSQLException: ERROR: column "state" is of type snapshot_state but expression is of type character varying
[ERROR] NativeEnumColumnTests.every_snapshot_state_round_trips -- ERROR!
Caused by: org.postgresql.util.PSQLException: ERROR: column "state" is of type snapshot_state but expression is of type character varying
```

**2. Revert one column to the old shape** — `snapshots.state` back to
`TEXT NOT NULL CHECK (state IN (...))` with its casts removed, i.e. exactly the pre-change
state of that column:

```
[ERROR] Tests run: 9, Failures: 2, Errors: 0, Skipped: 0 <<< FAILURE! -- in dev.skillsgateway.server.NativeEnumColumnTests
[ERROR] NativeEnumColumnTests.every_enumerated_column_is_a_native_enum_type_carrying_exactly_its_value_set:106 [snapshots.state is a type, not text]
 but was: "text"
[ERROR] NativeEnumColumnTests.every_enumerated_column_refuses_a_value_outside_its_set
```

The second failure is the one that matters: with the `CHECK` back, the refusal case's
`UPDATE snapshots SET state = 'not-a-member-of-the-set' WHERE id = -1` matches no row and the
constraint never fires, so the statement succeeds. The type refuses it while planning. That
is the behaviour the conversion has to preserve, and the test is not vacuous about it.

**Restored, green:**

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.413 s -- in dev.skillsgateway.server.NativeEnumColumnTests
[INFO] BUILD SUCCESS
```

## `./mvnw clean verify`

```
[INFO] Tests run: 193, Failures: 0, Errors: 0, Skipped: 0
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
```

193 tests, up from 184 before the change: the nine new cases in `NativeEnumColumnTests`. No
existing test was changed, weakened or removed.

## `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

## `(cd src/main/frontend && pnpm e2e)`

```
  12 passed (25.3s)
```

## `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
108/108 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
Totals: 28 passed, 0 failed (28 items)
```

## `mkdocs build --strict`

```
INFO    -  Documentation built in 0.64 seconds
```
