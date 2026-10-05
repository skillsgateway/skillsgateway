# Evidence: multi-replica-sessions

One fresh run of every gate after the last code edit, on commit
`8ae248dcec86d70fd984dd2de7b8278a91a4b49f` (Java 25.0.1). The machine was running other sessions'
suites at the same time, so `clean verify` ran detached and took longer than usual. That changes
how long it took, not what it checked.

## Tests that were watched failing first

| Test | Red before the change | Green after |
| --- | --- | --- |
| `ObjectStoreBackendTests.theStartupProbeStaysUnderThePrefix` | every key was `_probe/<uuid>` at the bucket root | every key under the prefix |
| `SweepCoordinationTests.eachInstanceCountsTheTurnsItTookAndSkipped` (SVC_GW_OBSERVABILITY_0005) | `MeterNotFoundException: No meter with name 'skills_gateway.sweep.lease'` | 1 taken / 1 skipped, each on its own instance |
| `SignInFailureTests` (4, SVC_GW_AUTH_0054) | `expected: 401` (a redirect to the "Invalid credentials" page) | 401 page naming the reason class and code, WARN logged |
| `SessionAcrossInstancesTests` sign-in (SVC_GW_AUTH_0053) | the second instance answered `authorization_request_not_found` | the callback completes on the second instance; `/api/v1/me` is 200 on both |
| `SessionAcrossInstancesTests` unreadable session (SVC_GW_AUTH_0053) | `relation "spring_session" does not exist` | 401 |

Mutation check: with the tolerant `springSessionConversionService` bean removed, the
unreadable-session test fails with `expected: 401 but was: 500`.

## Gates

```text
$ ./mvnw clean verify
[INFO] Tests run: 1072, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  18 passed (18)
      Tests  94 passed (94)

$ (cd src/main/frontend && pnpm e2e)
  25 passed (1.6m)

$ reqstool status local -p docs/reqstool        # exit 0
IMPLEMENTATIONS In Code: 314 total, 314 (100.00%) verified, 0 missing annotation
Passed tests 1078, Failed tests 0, Skipped tests 0; SVCs missing tests 0, SVCs missing MVRs 0
GW_AUTH_0053           1 implementation, 2 tests, 2 passed
GW_AUTH_0054           1 implementation, 4 tests, 4 passed
GW_OBSERVABILITY_0005  1 implementation, 1 test,  1 passed

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 3.47 seconds
```

## Budgets

`ContextBudgetTests` holds at 27. The session test shares `IdpBearerFacadeTests`' context through
`AbstractIdentityProviderTest`, and starts its second gateway itself. That second gateway is the
thing under test, not a test context. `ConfigSurfaceBudgetTests` is unchanged: no
`skills-gateway.*` leaf was added.
