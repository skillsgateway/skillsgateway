# Evidence — fix-audit-records-wanted-snapshot-ref

Commit under test: `f38a7bc9095fb9f2a424b3319b6389575560c30d` — the
implementation commit, `fix(facade): record the advertised ref a fetch want
resolves to`. One fresh run of every gate after the last code edit, in the order
CLAUDE.md requires. Nothing was re-run to get a better result.

Runner: Docker 29.1.3, git 2.x from the host (the facade tests drive the real git
binary through `AbstractGatewayTest.git(...)`, isolated from host git config).

## The defect, proved before it was fixed

`FetchLedgerRefTests.a_fetch_of_a_superseded_snapshot_records_that_snapshots_ref`
was written and run **before** any production edit. Verbatim:

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 19.29 s <<< FAILURE! -- in dev.skillsgateway.server.FetchLedgerRefTests
[ERROR] dev.skillsgateway.server.FetchLedgerRefTests.a_fetch_of_a_superseded_snapshot_records_that_snapshots_ref -- Time elapsed: 0.439 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
[the ledger names the advertised ref the want resolved to, not the served tip]
expected: "refs/snapshots/4acb629b815ac83fdb275be255d4a92dbd00d397"
 but was: "refs/heads/main"
	at dev.skillsgateway.server.FetchLedgerRefTests.a_fetch_of_a_superseded_snapshot_records_that_snapshots_ref(FetchLedgerRefTests.java:49)
```

The second test in that class — an ordinary clone records `refs/heads/main` —
passed on the same run, which is what establishes that the tip-wins rule
preserves the behaviour that was already correct rather than trading one wrong
value for another.

## The tests discriminate — two mutations

**Mutation 1: `wantedRef` always returns `SERVED_REF`** (the pre-fix behaviour,
reintroduced as an early return):

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.FetchLedgerRefTests
[ERROR] Tests run: 5, Failures: 3, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.facade.WantedRefTests
[ERROR]   FetchLedgerRefTests.a_fetch_of_a_superseded_snapshot_records_that_snapshots_ref:49
[ERROR]   WantedRefTests.a_marketplace_with_no_served_tip_still_resolves_a_snapshot_ref:79
[ERROR]   WantedRefTests.a_superseded_snapshot_resolves_to_its_own_ref:47
[ERROR]   WantedRefTests.a_want_matching_no_advertised_ref_resolves_to_nothing:57
[ERROR] Tests run: 7, Failures: 4, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

**Mutation 2: the snapshot ref wins instead of the tip** — the rejected
alternative from `design.md`. It fails the other four, including the *existing*
`SVC_GW_0008` assertion, which is the check that the ambiguity rule is not free
to change:

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.FetchLedgerRefTests
[ERROR] Tests run: 5, Failures: 2, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.facade.WantedRefTests
[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.FacadeTests
[ERROR]   FacadeTests.facadeFetchesAreAuditLogged:94
[ERROR]   FetchLedgerRefTests.a_clone_of_the_served_tip_records_the_tip:68
[ERROR]   WantedRefTests.head_is_never_the_recorded_name:68
[ERROR]   WantedRefTests.the_tip_wins_when_a_snapshot_ref_names_the_same_commit:38
[ERROR] Tests run: 11, Failures: 4, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Both mutations were reverted before the gate run below. Every one of the seven
new assertions is killed by one mutation or the other, and no test survives both.

## `./mvnw clean verify`

```
[INFO] Spotless.Java is keeping 261 files clean - 0 needs changes to be clean, 261 were already clean, 0 were skipped because caching determined they were already clean
[INFO] You have 0 Checkstyle violations.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  06:45 min
```

Aggregated over `target/surefire-reports/`:

```
Tests run: 415, Failures: 0, Errors: 0, Skipped: 0
```

Including the new suites:

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.FetchLedgerRefTests
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.facade.WantedRefTests
```

`clean` is deliberate: incremental compilation truncates the generated annotation
files the traceability gate reads.

`AuditExportTests` did **not** flake on this run (its historical
`ConcurrentModificationException` is issue #93); no test was re-run.

## `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
   Duration  8.85s
JUNIT report written to .../src/main/frontend/test-results/storybook-junit.xml
```

## `(cd src/main/frontend && pnpm e2e)`

```
  ✓  13 [chromium] › e2e/portal.spec.ts:665:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim (624ms)

  13 passed (59.7s)
```

## `reqstool status local -p docs/reqstool`

```
  GW_0153             skills-gateway
  GW_0154             skills-gateway

INCOMPLETE (0)
147/147 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
Totals: 31 passed, 0 failed (31 items)
```

## `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 4.24 seconds
```

## Not regenerated, and why

`src/main/frontend/openapi.json` is unchanged. The `@Schema` description on
`FetchLogRepository.AuditEntry.ref` was corrected, but that record is not
referenced by any documented endpoint schema — the export streams NDJSON — so the
served document is byte-identical to the committed one:

```
$ diff <(canonicalized target/openapi.json) <(canonicalized src/main/frontend/openapi.json)
(no output)
```

`OpenApiContractTests` passed inside `mvnw clean verify`, which is the gate that
would have caught a drift.
