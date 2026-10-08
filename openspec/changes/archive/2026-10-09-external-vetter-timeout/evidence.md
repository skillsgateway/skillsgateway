# Evidence: external-vetter-timeout

- **Source state:** `616f9580` (last code edit); every result below is from one
  fresh run after it. Later commits touch only this file, `tasks.md` and the
  archive.
- **Tier:** not a trust-boundary change; kept fail-closed (a vetter that
  outruns its limit is still an `ERROR` verdict, which blocks).

## Tests shown failing first

`VettingTimeLimitTests` was run with the connector's new constructor in place
but before `Vetter.timeLimit` and `runGuarded` used it:

```console
$ ./mvnw test -Dtest=VettingTimeLimitTests ...
[ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0
[ERROR] ...an_external_vetter_with_a_longer_read_timeout_outlives_the_chain_wide_limit
expected: PASS
 but was: ERROR
[ERROR] ...an_external_vetter_without_a_read_timeout_inherits_the_chain_wide_limit
```

The second failed because the chain's guard (500 ms) fired at the same moment
as the inherited 500 ms read timeout and its "timed out after PT0.5S" message
won; with the margin, the HTTP client's timeout is the one recorded. The
built-in case passed before and after, as it should: built-ins are unchanged.
All three pass after the change.

## Gates

```console
$ ./mvnw clean verify                              # openjdk 25.0.1
[INFO] Tests run: 1102, Failures: 0, Errors: 0, Skipped: 9
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  07:53 min

$ (cd src/main/frontend && pnpm test:stories)      # node 26.10.0, pnpm 12.10.0
 Test Files  21 passed (21)
      Tests  111 passed (111)

$ (cd src/main/frontend && pnpm e2e)
  27 passed (1.9m)

$ reqstool status local -p docs/reqstool           # reqstool 0.12.3.dev7 (local editable install)
343/343 complete · 0 incomplete · PASS

$ openspec validate --all --strict                 # openspec 1.13.2
Totals: 30 passed, 0 failed (30 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.56 seconds
```

What happened on the way:

- **First `pnpm test:stories` failed** with no assertion run: one story file's
  iframe "did not become ready within 60000ms" (`Tests  no tests`). This PR
  changes no frontend file. It is the known post-`clean` Storybook harness
  failure; after deleting `node_modules/.cache/storybook` and `.vite` the second
  run passed as shown.
- **`reqstool` on `PATH` resolved to a pyenv shim at 0.11.0**, which prints no
  completion line; the gate was run with the editable install at
  `~/.local/bin/reqstool`.
- `ConfigSurfaceBudgetTests` and `ContextBudgetTests` pass unchanged: no
  configuration leaf and no Spring test context was added.
- No testcontainers left running after the gates.
