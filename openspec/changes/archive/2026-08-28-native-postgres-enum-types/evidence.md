# Evidence — native-postgres-enum-types

One fresh run of every gate after the last edit.

**Commit:** the merge of `origin/main` into this branch, plus the conflict resolution and
the one formatting fix that followed it. Only this report and the archive commit come after.

The gates were run twice. The first run was at `061255d`, before `four-eyes-approval`
merged; that change added a `registered_by` column and parameter to
`MarketplaceRepository.register` and an `ingested_by` pair to `SnapshotRepository.create`,
which collide directly with the write casts this change introduces. Merging main produced
four conflicts — both reqstool files, and both repositories — and left
`NativeEnumColumnTests` calling the old signatures. A report describing the pre-merge tree
would describe a tree nobody will merge, so the numbers below are from the second run.

Conflict resolution, for the record: the reqstool conflicts were both "two changes appended
at the same point" and were resolved by keeping all blocks, then parsing each file with a
YAML loader to confirm no requirement or case lost its `revision` field (113 of each, no
duplicate ids). The two repository conflicts were semantic — main's new actor column and
this change's write cast, in the same statement — and both were kept.
`NativeEnumColumnTests` passes `null` for the two new actor parameters: these are enum
round-trip cases that assert nothing about who acted, and `SnapshotRepository`'s own
javadoc records null as legitimate.

`TESTCONTAINERS_RYUK_DISABLED=true` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock` were set for the
runs that need a container runtime — Ryuk cannot start under rootless Podman on this
machine.

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
[INFO] BUILD SUCCESS
[INFO] Total time:  01:09 min
```

207 tests, 0 failures, 0 errors, 0 skipped, aggregated from
`target/surefire-reports/*.xml`. Nine of them are this change's `NativeEnumColumnTests`;
the rise from 193 is what `four-eyes-approval` and the chart work brought in with the
merge. No existing test was changed, weakened or removed — the only edit to a pre-existing
test file was updating four call sites in `NativeEnumColumnTests` to the signatures main
now defines.

## `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

## `(cd src/main/frontend && pnpm e2e)`

```
  13 passed (30.5s)
```

## `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
113/113 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
Totals: 28 passed, 0 failed (28 items)
```

## `mkdocs build --strict`

```
INFO    -  Documentation built in 0.63 seconds
```
