# Evidence: revet-ledger-rows-on-change

- **Source state:** every gate below ran over `1ea63156`, the commit after the
  last code edit. The only later changes are this file, `tasks.md` and the
  archive.
- **Red first:** `SVC_GW_VETTING_0017.1` and `SVC_GW_VETTING_0017.2` were run
  against the unmodified `VettingService` and failed for the reason under test —
  the re-vet appended one `vetting-verdict` per vetter (five) before
  `vetting-completed` and `revet-clear`.
- **Mutants:** each of the four conditions in `VettingService.changedSince`
  (worst severity, finding count, chain membership, a vetter missing from the
  previous run's verdicts) was removed in turn; `RevetVerdictChangeTests` failed
  for every one.

## Red run (before the implementation)

```console
$ ./mvnw test -Dtest=RevetTests
[ERROR] Tests run: 7, Failures: 2, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.RevetTests
Expecting actual:
  ["vetting-verdict", "vetting-verdict", "vetting-verdict", "vetting-verdict",
   "vetting-verdict", "vetting-completed", "revet-clear"]
to contain exactly (and in same order):
  ["vetting-completed", "revet-clear"]
```

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 1000, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  27 passed (27)
[INFO]       Tests  208 passed (208)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:28 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  17 passed (17)
      Tests  88 passed (88)
# The first run failed to import five story files ("Failed to fetch dynamically
# imported module"), the known harness flake; green after clearing
# node_modules/.cache/storybook and .vite. This change touches no frontend file.

$ (cd src/main/frontend && pnpm e2e)
  24 passed (1.2m)

$ reqstool status local -p docs/reqstool
324/324 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.51 seconds
```
