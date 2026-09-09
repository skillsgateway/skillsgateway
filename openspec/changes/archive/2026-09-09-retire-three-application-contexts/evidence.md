# Evidence: retire-three-application-contexts

Commit under test: `8c1a1f3` (the code commit of this branch), branched from
`92e1fde` (`main`). All gates below were run after the last code edit, on the
same machine, with nothing else building.

## The measurement

Both sides come from `ContextBudgetTests`, which resolves each Spring test
class's `MergedContextConfiguration` — the framework's own cache key — without
loading a context. The before side was measured on `main` at `92e1fde`.

| | Before | After |
| --- | ---: | ---: |
| Distinct application contexts | **32** | **29** |
| `BUDGET` constant | 32 | 29 |
| Headroom against the budget | 0 | 0 |

The headroom is zero on both sides because the budget is a ratchet, set to the
measured figure. What changed is the figure.

The three contexts retired, from `target/context-budget.txt`:

- `skills-gateway.roles.admins=a-person,ledger-machine,…,ledger-person`
  (`MachineLedgerTests`) — merged into `AbstractNamedAdminsTest`'s
- `skills-gateway.roles.claim=groups` + one mapping
  (`ClaimMappedRoleIsEnforcedTests`) — merged into `AbstractClaimMappingTest`'s
- the base context set (`ExternalConnectorRegistrationTests`) — the class no
  longer resolves a `MergedContextConfiguration` at all; its
  `ApplicationContextRunner` context is created and closed per test and never
  enters the cache

## Gates

### `./mvnw clean verify`

```
[INFO] BUILD SUCCESS
[INFO] Total time:  07:28 min
[INFO] Finished at: 2026-09-09T21:35:13+02:00
```

Aggregated across the 127 surefire reports: **647 tests run, 0 failures, 0
errors, 9 skipped**. Spotless kept 339 files clean; Checkstyle reported 0
violations.

**One flaky failure is worth recording rather than hiding.** The first full
`verify` of this identical tree failed one assertion in `CatalogTests`
(`publications_and_revocations_reshape_the_catalog_on_their_own`, a cloned
catalog still containing a revoked marketplace directory). `CatalogTests` is a
known-flaky suite that runs in its own context and its own database and asserts
on its own catalog clone; nothing in this change touches its context, its
fixtures or its workdirs. The run above is the re-run, and the run before the
commit passed the same suite. Recorded because a red `Build & gates` on this PR
may be that flake and not this change.

### `pnpm test:stories`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
   Duration  3.58s
```

### `pnpm e2e`

```
  13 passed (48.7s)
```

### `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
218/218 complete · 0 incomplete · PASS
```

No `@SVCs` annotation moved off the test method it was on. `SVC_GW_AUDIT_0007`,
`SVC_GW_AUTH_0015.1`, `SVC_GW_AUTH_0015.3` and `SVC_GW_VETTING_0024` are all
still claimed by the same classes, which is why the total is unchanged at 218.

### `openspec validate --all --strict`

```
Totals: 32 passed, 0 failed (32 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 1.17 seconds
```

## What was checked by reading rather than by running

Contexts are the suite's only isolation between classes — there is no
truncation, no `@Sql` and no `@DirtiesContext` in `src/test` — so collapsing a
context collapses a database. Each merge was checked against that before it was
made:

- **`MachineLedgerTests`' principals are unique to it.** `a-person` and every
  `ledger-*` name appears nowhere else under `src/test`, so a row carrying one
  was written by this suite, which is what its per-principal filters assume.
  Its one global assertion (`fetchLogRepository.list().size()` before and after
  a read) is an equality across a single test method, and JUnit is not
  configured for parallel execution anywhere in this project, so no other class
  can write between the two reads.
- **None of the eight names is one of the three reserved ones.**
  `AbstractNamedAdminsTest`'s javadoc names `dev`, `mallory` and
  `just-logged-in` as principals whose "holds no role" assertions would become
  tautologies if they were ever added to the shared list.
- **`ClaimMappedRoleIsEnforcedTests` reads only `gw-auditors`**, which
  `ClaimRoleMappingTests` already mapped to `auditor`. The other three mappings
  are inert for its caller, whose only claimed group is `gw-auditors`. Its
  marketplace name is already uniqued and its `POST` is refused, so it creates
  no row that could collide with `ClaimRoleMappingTests`' two fixed names.
- **`ClaimRoleMappingTests` asserts nothing globally.** Every assertion is
  scoped to a principal or to a named marketplace.
- **The built-in chain is still verified as production assembles it.**
  `ExternalConnectorRegistrationTests` now names the built-in connectors rather
  than discovering them by component scan. `VettingTests` and
  `ConnectorToggleTests` both walk `vettingService.connectors()` in the real
  shared context and run the chain against real content, so the scan's result
  remains under test where it already was.
