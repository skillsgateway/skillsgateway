# Evidence: sweep-abandoned-staging-refs

Tier 2. No trust boundary is crossed — the sweep lists `refs/staging/*` and
nothing else, so the facade's namespaces are unreachable from it by
construction, and the approved-snapshot guard is untouched. But the change puts
a deletion on the same side of the estate as served content for the first time,
and the failure mode it could introduce is data loss on the publication path.
That is what the age bound exists for, and it is the claim this report is mostly
about.

Implementation commit: `97e87c3707e248028d6c9ef33aeb875a349debf1`.

## Spec ↔ test mapping

| Requirement | Verifies | Test |
| --- | --- | --- |
| GW_FACADE_0019 — Abandoned publication staging references are swept from published repositories | abandoned reference past the bound swept and its commit left unreachable; in-flight publication inside the bound survives *and completes*; a reference a live snapshot row names survives however old; a repository with nothing abandoned comes out byte-identical | `StagingRefSweepTests` (SVC_GW_FACADE_0019), 11 tests |

Eight of the eleven are the four properties above, each run against **both**
storage backends. The parameter carries the backend's `GitStorage` and a
`RetentionService` built over it; everything else — the database, the sighting
table, the properties — is shared, so a difference between the two runs could
only come from the backend.

The remaining three are the parts that are not portable, and the wiring:

- `onTheFilesystemTheSweptObjectsAreReclaimedByTheSamePass` — the filesystem
  backend's collection reclaims in the same pass. The object store tombstones
  its packs and deletes them after a grace period instead, which is why the
  shared assertion across backends is *unreachability* rather than absence.
- `compactionRunsTheSweepAndLeavesApprovedContentServed` — an on-demand
  compaction pass runs the sweep, writes `staging-refs-swept:count=1` to the
  ledger, and leaves the approved snapshot clonable through the facade with a
  real `git clone`. This is the only case that covers "approved content
  untouched" all the way out to what is advertised on the wire.
- `aZeroBoundSwitchesTheSweepOffRatherThanSweepingEverything` — the fail-safe
  reading of a mis-typed bound.

## The age bound is load-bearing, and the tests prove it

The in-flight cases would pass against a sweep with no bound at all if they only
asserted "the reference is still there" — so they do not stop there. Each one
plants a staging reference with **no snapshot row at all** (the sweep's first
look at a publication whose row it has not read), runs the sweep, and then calls
`commitPublication` and reads the commit message back off the served tip. The
survival is only worth something if the publication can still finish.

The bound was then removed — the `seen == null || seen.isAfter(staleBefore)`
guard neutered — and the suite re-run:

```
[ERROR] Tests run: 11, Failures: 2, Errors: 0, Skipped: 0 <<< FAILURE!
[ERROR] StagingRefSweepTests.anInFlightPublicationInsideTheBoundSurvivesAndStillCompletes(Backend)[1]
[ERROR] StagingRefSweepTests.anInFlightPublicationInsideTheBoundSurvivesAndStillCompletes(Backend)[2]

Expecting map:
  {}
to contain entries:
  ["refs/staging/a1669c99917c11765080e5bfbc381b01e820e74f"=AnyObjectId[a1669c99917c11765080e5bfbc381b01e820e74f]]
```

Exactly two failures, and they are the in-flight case on `[1]` filesystem and
`[2]` object-store. The other nine pass without the bound, which is the point:
the bound is not doing anything except protecting the publication in flight, and
without it the sweep deletes that publication's reference on both backends. The
guard was restored and the suite is green again in the run below.

## Why the served side cannot be reached

Three independent reasons, none of which relies on the others:

1. The sweep enumerates `getRefsByPrefix("refs/staging/")`. `refs/heads/main`
   and `refs/snapshots/*` are not in that listing.
2. Garbage collection follows only when a reference was actually removed, and it
   collects by reachability — objects the served refs reach survive.
3. `aPublishedRepositoryWithNothingAbandonedIsUntouched` compares the whole
   reference map before and after for equality, on both backends, and
   `compactionRunsTheSweepAndLeavesApprovedContentServed` clones the marketplace
   over the facade afterwards.

## No existing test was weakened

`RetentionTests` is unchanged. `RetentionService.compact` keeps its
`{selected, acted}` contract about snapshots — the sweep's counts go to the
ledger — so `compactionRemovesExpiredDeletionsAndTheirQuarantineReference` and
`theLedgerRecordsEveryRetentionAction` assert exactly what they asserted before.
The one edit inside an existing method is `collectGarbage` gaining a role
parameter; its quarantine call site passes `Role.QUARANTINE` and behaves
identically.

## Gates

One fresh run of all six, after the last edit, at `97e87c3`.

```console
$ ./mvnw clean verify
```

```
[INFO] Tests run: 507, Failures: 0, Errors: 0, Skipped: 0   (aggregated over surefire-reports)
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.StagingRefSweepTests
[INFO] Spotless.Java is keeping 276 files clean - 0 needs changes to be clean
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  05:36 min
```

```console
$ (cd src/main/frontend && pnpm test:stories)
```

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

```console
$ (cd src/main/frontend && pnpm e2e)
```

```
  13 passed (45.2s)
```

```console
$ reqstool status local -p docs/reqstool
```

```
  GW_FACADE_0019             skills-gateway

INCOMPLETE (0)
156/156 complete · 0 incomplete · PASS
```

```console
$ openspec validate --all --strict
```

```
Totals: 31 passed, 0 failed (31 items)
```

```console
$ mkdocs build --strict
```

```
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 1.38 seconds
```

No flakes needed a rerun in this pass.
