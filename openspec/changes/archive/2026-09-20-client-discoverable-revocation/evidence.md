# Evidence: client-discoverable-revocation

One fresh run of every gate, after the last code and documentation edit.

**Commit:** `02346e3a65f6cf7bd30738c310b3b7372087295f`

## 1. `MAVEN_OPTS="-Xmx3g" ./mvnw clean verify`

```
Tests run: 701, Failures: 0, Errors: 0, Skipped: 9
Spotless.Java is keeping 382 files clean - 0 needs changes to be clean, 382 were already clean, 0 were skipped because caching determined they were already clean
You have 0 Checkstyle violations.
BUILD SUCCESS
Total time:  06:37 min
```

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  9 passed (9)
      Tests  45 passed (45)
```

!!! note "The first run of this gate failed and the second passed"

    Five story files failed on the run immediately after `mvnw clean`, with
    all 13 collected tests passing — the known cold-cache harness flake, not a
    result about this change. The passing run above is unmodified source, run
    again with the Vite cache warm.

## 3. `(cd src/main/frontend && pnpm e2e)`

```
  20 passed (1.2m)
```

## 4. `reqstool status local -p docs/reqstool`

```
248/248 complete · 0 incomplete · PASS
```

## 5. `openspec validate --all --strict`

```
Totals: 31 passed, 0 failed (31 items)
```

## 6. `mkdocs build --strict`

```
Documentation built in 1.36 seconds
```

## The tests were shown to fail without the implementation

Written after the fact rather than before, so each assertion was checked by
mutating the implementation and confirming the right test — and only that test
— went red:

| Mutation | Test that caught it |
| --- | --- |
| Drop the marketplace scope check | `every_answer_a_caller_is_not_entitled_to_is_the_same_answer` |
| Drop the reversed-revocation marker | `a_holder_is_told_approved_superseded_revoked_and_reinstated_apart` |
| Return the withdrawal reason (`snapshots.violation`) in the answer | `the_revocation_reason_never_reaches_the_answer` |
| Append a ledger row per answered request | `the_check_reads_the_record_and_writes_nothing_to_the_ledger` |

The implementation was restored from backup and the suite re-run green before
the gates above.
