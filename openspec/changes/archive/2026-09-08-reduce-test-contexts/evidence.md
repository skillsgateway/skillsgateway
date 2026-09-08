# Evidence: reduce-test-contexts

**Commit under test:** `a7837df` (`test: budget the suite's application contexts
and collapse five of them`), on `refactor/reduce-test-contexts`, branched from
`331cf68` — the tip of `main` at the time of the run.

All six gates below were run **fresh, in order, after the last code edit**.

## How the measurements were taken, and why that matters here

This change's whole claim is a set of numbers, so the conditions they were taken
under are part of the evidence.

Both sides were measured on a **quiet machine while holding the run's exclusive
gate lock** — no other Maven process, two pre-existing containers on the podman
machine. Earlier numbers taken while up to ten Maven builds shared one 8 GiB
podman VM were discarded: they carried seven `Failed to load ApplicationContext`
errors whose cause chain bottoms out in `Status 500 ... "proxy already
running"` — podman's rootless port-forwarding proxy colliding — and a
measurement taken through that is not a measurement of this change.

- **Before** was measured in a separate detached worktree at `331cf68`, so the
  two sides differ only by this branch's commit.
- Both sides ran the identical command:
  `./mvnw clean test -Dskip.ui.verify=true -Dlogging.level.org.springframework.test.context.cache=DEBUG -DargLine="-Xlog:gc*:file=…"`
- Windows: before `02:07:36–02:10:02`, after `02:22:06–02:24:22`.

## The numbers

| Measure | before (`331cf68`) | after (`a7837df`) | |
| --- | ---: | ---: | --- |
| **Distinct context cache keys** | **36** | **31** | −5 (−14%) |
| Contexts actually built (`missCount`) | 36 | 33 | −3 |
| PostgreSQL container starts | 36 | 33 | −3 |
| Floci container starts | 37 | 34 | −3 |
| Peak concurrent containers | 19 | 19 | — |
| Max heap after a mixed GC (live-set proxy) | 369 MB | 340 MB | −29 MB |
| Peak heap occupancy before a GC | 4093 MB | 4089 MB | — (cap 4096 MB) |
| Full GCs | 0 | 0 | — |
| Context load failures | 0 | 0 | — |
| Wall time, `clean test` | 2:23 | 2:13 | −10 s |
| Tests run | 586 | 587 | +1 (`ContextBudgetTests`) |

Sources: the distinct-key count is `ContextBudgetTests` writing
`target/context-budget.txt`; `missCount` is `DefaultContextCache`'s own
statistics line at DEBUG; container starts are `Creating container for image:`
lines; peak concurrent containers is `podman ps -q | wc -l` sampled every 5 s
across each window; heap figures are parsed from `-Xlog:gc*`.

### Reading these honestly

- **The distinct-key count is the headline and it is exact.** It is not a sample
  or an estimate: it is the size of the set of `MergedContextConfiguration`
  objects the framework itself will use as cache keys.
- **36, not 33 and not "approximately 30".** #303's commit message and the pom
  comment said 33; #305's inventory said approximately 30. Both counted
  annotations. This is the first count of cache keys, and it is higher than
  either — the problem was slightly worse than recorded.
- **`missCount` fell by 3, not by 5.** Before, `missCount` equalled the distinct
  count exactly: with `maxSize=8`, surefire's class order happened to group
  co-tenant classes closely enough that nothing was ever evicted and rebuilt.
  After, 33 builds serve 31 keys — two contexts were evicted and rebuilt, because
  merging classes changed which contexts are adjacent in LRU order. The net is
  still −3 builds and −3 PostgreSQL containers, but the saving is smaller than
  the key count suggests and it is order-dependent. Claiming −5 would be wrong.
- **Resident heap barely moves, and that is expected.** `maxSize=8` already
  caps how many contexts are held, so the live set was never the problem on a
  machine this size; peak occupancy sits at the 4096 MB cap on both sides
  because the suite allocates hard, not because it retains. The live-set proxy
  improved 369 MB → 340 MB. The heap argument for this work is about the 2-core
  CI runner in #302, which this measurement cannot reproduce.
- **Peak concurrent containers is unchanged at 19.** The churn is sequential —
  contexts are built and closed one at a time — so fewer builds means fewer
  start/stop cycles through podman's port-forwarding proxy, not a smaller
  simultaneous footprint. Three fewer cycles is three fewer chances at the
  `proxy already running` collision, which is a small improvement to signal
  stability rather than a fix for it.
- **Wall time fell 10 seconds on a 2:23 run.** Real, but within the range a
  single container start varies by; it is not the argument for this change.

## Gates

All run from `/Users/r04755/dev/.../worktrees/test-contexts`, in this order.
`./mvnw clean verify` and `pnpm e2e` were run holding the exclusive gate lock.

### 1. `./mvnw clean verify` — PASS

```
[INFO] Tests run: 587, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  03:03 min
```

### 2. `(cd src/main/frontend && pnpm test:stories)` — PASS

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
   Duration  2.55s
JUNIT report written to .../src/main/frontend/test-results/storybook-junit.xml
```

### 3. `(cd src/main/frontend && pnpm e2e)` — PASS

```
  ✓  13 [chromium] › e2e/portal.spec.ts:665:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim (357ms)

  13 passed (48.4s)
```

### 4. `reqstool status local -p docs/reqstool` — PASS

```
INCOMPLETE (0)
165/165 complete · 0 incomplete · PASS
```

### 5. `openspec validate --all --strict` — PASS

```
✓ spec/virtual-catalog
Totals: 32 passed, 0 failed (32 items)
```

### 6. `mkdocs build --strict` — PASS

```
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.78 seconds
```

## Retries

**One retry, recorded rather than hidden.** A first `./mvnw clean verify` on this
branch failed with seven `Failed to load ApplicationContext` errors across seven
classes — `ForwardedHeadersRedirectUriTests`, `RevetEnforceTests`,
`EscapeHatchRoleNegativeTests`, `ConditionalWriteFidelityTests`,
`ExternalSourceResolutionTests`, `FourEyesEnforceTests` and `CatalogTests`. The
cause chain bottoms out identically in every case:

```
Caused by: com.github.dockerjava.api.exception.InternalServerErrorException:
  Status 500: {"cause":"something went wrong with the request: \"proxy already running\n\""
→ org.testcontainers.containers.ContainerLaunchException: Could not create/start container
→ BeanCreationException: 'devService.container.floci': Container startup failed
→ ... → ApplicationContextException: Unable to start web server
```

Ten Maven builds were sharing one 8 GiB podman VM at the time. The same commit,
run alone under the gate lock, passes with zero container-start errors. **None of
the seven is a class this change touches**, and the six classes it does merge
passed in both runs.

That failure mode is worth recording for a second reason: it is caused by the
container churn that `spring.test.context.cache.maxSize=8` produces — evicted
contexts are rebuilt, and each rebuild cycles a Floci and a PostgreSQL container
through podman's port-forwarding proxy. Reducing context count reduces that
churn. It is an argument for #305 that the issue itself does not make.

## What was verified about the merged suites, beyond "the gates are green"

Collapsing contexts collapses databases: the Arconia PostgreSQL dev service
registers its container per context, and there is no truncation, no `@Sql` and no
`@DirtiesContext` anywhere in `src/test`, so a context was the only isolation the
suite had between classes. Every assertion in the six merged classes was read
against that before the merge, not only run afterwards. Each is one of:

- filtered to a fixture the suite named itself via `uniqueName(...)`;
- tolerant by construction (`isGreaterThanOrEqualTo`, `contains`, `anySatisfy`);
- or about a static table (`MachineApiRegistry`) rather than the database.

Three principals were deliberately kept out of the shared administrator list —
`dev`, `mallory` and `just-logged-in` — because each is the subject of an
assertion that it holds no role at all, and each such assertion becomes a
tautology the moment its principal is an administrator. That omission is
documented in `AbstractNamedAdminsTest`'s javadoc, where the next person to add
a name will read it.

`RoleEnforcementTests` was deliberately **not** merged, although its property set
was identical to `MachineCredentialLifecycleTests`'. Its deny-by-default walk is
only honest in a context whose administrator list it controls entirely, so it now
holds a context alone.

## What this change does not claim

- No requirement changed and no new requirement was added.
- No `@SVCs` annotation was moved, weakened or removed; `reqstool` reports
  165/165 on both sides.
- `spring.test.context.cache.maxSize=8` is **unchanged**. The measurement above
  suggests it could now be raised — 31 keys against a cache of 8 still evicts —
  but "suggests" is not a measurement of the raised value, and #305 asks for that
  decision to be made against numbers rather than against an argument. It belongs
  in a follow-up that measures it.
- The large win in #305 — slicing full-context tests onto `@WebMvcTest`,
  `@JdbcTest`, `@RestClientTest` and `@JsonTest` — is untouched here.
