# Evidence: verify-s3-conditional-write-fidelity

Branch `test/s3-conditional-write-fidelity`.

Two runs are reported, and they are deliberately kept apart:

1. **The supervised real-S3 run** — the thing #151 asked for. It needs an AWS
   account, so it is not part of any gate and cannot be.
2. **The project gates** — one fresh run after the last code edit, with the
   real-S3 suite **skipping**, which is the state CI is in and the state every
   contributor without an AWS account is in.

## 1. The real-S3 run

The five assertions and both mutation controls were run against **real AWS S3**,
in a dedicated, empty scratch bucket created for the run and deleted afterwards.

```
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running Object-store conditional-write fidelity — real AWS S3 (issue #151)
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.714 s -- in Object-store conditional-write fidelity — real AWS S3 (issue #151)
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
```

```
Test set: dev.skillsgateway.server.storage.RealS3ConditionalWriteFidelityTests
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.714 s
```

Command, with `SKILLS_GATEWAY_FIDELITY_S3_BUCKET` and
`SKILLS_GATEWAY_FIDELITY_S3_REGION` exported:

```bash
./mvnw -o surefire:test -Dtest=RealS3ConditionalWriteFidelityTests -DfailIfNoSpecifiedTests=false
```

Run 2026-09-08 against test classes compiled from commit `bf79293`. **First run,
no retry.** Cleanup was confirmed independently afterwards: a `list-objects-v2`
against the run-scoped prefix returned a `KeyCount` of zero, so the suite left
nothing behind.

### The controls are the load-bearing part

A green suite proves nothing on its own. What makes these nine results evidence
is that **both negative controls passed as controls**:

| | Mutation | Result |
| --- | --- | --- |
| M-A | `If-Match` dropped from the 8-writer race | **8 winners, 0 refusals** — so assertion 4's single winner is caused by the precondition, not by serialization the store would have done anyway |
| M-B | `If-None-Match` dropped from the second create | **the second write overwrote** — so assertion 3's 412 is caused by the precondition, not by the store refusing a second write to a key it has already seen |

A store that refused writes for some unrelated reason — a bucket policy, an
object lock, a quota — would answer 412 to everything and sail through
assertions 1–5 while proving nothing at all. These two rule that out, and unlike
the original spike's hand-edited mutations they are re-established on every run,
against both stores.

### What this run does not cover

**The bucket was a plain bucket: no versioning, no SSE-KMS.** On a bucket with
either, an ETag is not a content hash. No assertion here ever *computes* an
expected ETag — every one only chains the ETag the store returned — so the
design is expected to hold, and covering the case needs no code change, only a
run against a bucket configured that way.

**It has not been run.** The supported-store table in
`docs/manual/guides/storage-backends.md` and the runbook both say so explicitly,
rather than letting a green run imply more than it proved. This is exactly the
overclaim #151 exists to prevent.

Also out of scope, and named in the proposal: any S3-**compatible** store on
another endpoint (there is no endpoint override), and any scheduled CI execution
of this suite.

## 2. The project gates

One fresh run of every gate after the last code edit, with the real-S3 suite
skipping.

### `./mvnw clean verify`

```
[INFO] Running Object-store conditional-write fidelity — Floci (Arconia dev service)
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.283 s

[WARNING] Tests run: 9, Failures: 0, Errors: 0, Skipped: 9, Time elapsed: 0 s -- in Object-store conditional-write fidelity — real AWS S3 (issue #151)

[INFO] Tests run: 597, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  02:39 min
```

The two lines that matter:

- The **Floci suite runs all 7** — the five assertions plus both negative
  controls — so the shared base did not weaken the suite that already existed.
- The **real-S3 suite contributes all 9 skips**, and surefire logs it at
  `[WARNING]` with the reason on every case. A build that verified nothing
  against S3 says so; it cannot be mistaken for one that did.

### `pnpm test:stories`

```
 ✓ |storybook (chromium)| src/pages/tokens.stories.tsx (1 test) 288ms
 ✓ |storybook (chromium)| src/components/markdown-view.stories.tsx (2 tests) 782ms
 ✓ |storybook (chromium)| src/pages/adoption.stories.tsx (3 tests) 289ms

 Test Files  3 passed (3)
      Tests  6 passed (6)
```

### `pnpm e2e`

Playwright against the real packaged jar and a mock OIDC IdP.

```
Running 13 tests using 1 worker
  ✓   1 [chromium] › e2e/portal.spec.ts:67:1 › admin_registers_ingests_and_approves_a_marketplace_in_the_portal (6.1s)
  ...
  ✓  13 [chromium] › e2e/portal.spec.ts:665:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim

  13 passed (41.4s)
```

### `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
165/165 complete · 0 incomplete · PASS
```

Read the last line, not the exit code: `reqstool status` exits 0 even when it
prints FAIL. It printed **PASS**. The count is unchanged from `main` because
this change adds no requirement and no SVC.

### `openspec validate --all --strict`

```
✓ change/verify-s3-conditional-write-fidelity
Totals: 32 passed, 0 failed (32 items)
```

### `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 1.48 seconds
```

Exit 0, no warnings — the new guide is in `nav:` and its links resolve.

## 3. Retries, and two environmental failures

The passing `mvnw clean verify` above was the **third** attempt. The first two
failed, both for the same environmental reason, and both are recorded here
rather than quietly re-run — a genuinely flaky test looks identical from the
outside, and the distinction is only visible if the attempts are written down.

| Attempt | Result | Cause |
| --- | --- | --- |
| 1 | `Tests run: 597, Failures: 0, Errors: 5, Skipped: 9` | container-start failure |
| 2 | `Tests run: 597, Failures: 0, Errors: 23, Skipped: 9` | same, worse |
| 3 | `Tests run: 597, Failures: 0, Errors: 0, Skipped: 9` — **BUILD SUCCESS** | run with exclusive access to the container runtime |

Every error in attempts 1 and 2 surfaced as `Failed to load ApplicationContext`,
which reads like a test failure until the cause chain is followed to the bottom:

```
Caused by: ... Error creating bean with name 'devService.container.floci':
    Container startup failed for image floci/floci:1.6.0
Caused by: com.github.dockerjava.api.exception.InternalServerErrorException: Status 500:
    {"cause":"something went wrong with the request: \"proxy already running\\n\""}
```

That is the container runtime's rootless port-forwarding proxy colliding when
several builds start containers simultaneously. Five other builds were running
concurrently during attempt 1 and the load was heavier still during attempt 2 —
which is why the error count went **up** on retry, 5 to 23. That is the
signature of contention, not of flakiness.

Three things establish that the failures were not caused by this change:

- **The failing classes were different each time** and none of them is touched
  here — `MachineRoleIntersectionTests` and `ForwardedHeadersFrameworkTests`
  first, then `EstateStartupFailureTests`, `ClosureCompletenessTests`,
  `SnapshotClosureTests` and the `ForwardedHeaders*` classes. Random victims.
- **All three attempts report the identical `597 tests` and the identical
  `Skipped: 9`.** The suite's composition never varied; only container starts
  failed.
- **This change starts no container and adds no Spring context.** The real-S3
  suite is plain JUnit and was skipped in all three runs; the Floci subclass
  reuses the property set its predecessor already had, so it resolves to the same
  cached context as before.

Attempt 3 ran under a cross-agent mutex that gave it exclusive use of the
container runtime, and recorded **zero** `Container startup failed` occurrences
against 64 in attempt 2. The fix was scheduling, not code.

`pnpm e2e` ran under the same mutex and passed on its first attempt. The other
four gates need no container and were not retried.

## 4. Requirements

**No requirement and no SVC was added, and none was changed.** GW_0226 and
GW_0227 were reserved for this change and are deliberately **not** consumed —
they remain free for another change to claim.

This change adds no required behaviour. It extends how the foundation of
GW_0112 — Reference transitions are the same on every storage backend is
verified. Both fidelity suites carry no `@SVCs`, as the original did not: they
verify the store, not the gateway. A suite that skips unless a bucket is
configured could not satisfy an SVC in any case, since reqstool counts a passing
surefire case and the default build has none.
