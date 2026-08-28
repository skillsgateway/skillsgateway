# Evidence: pluggable-git-storage

Scope of this report: **tasks 2.1, 2.3 (partly) and 2.4** — the
conditional-write fidelity spike, a MinIO probe of store portability that is
evidence rather than a gate, and the ref-database decision.
The DFS backend itself is not implemented, so this is not the change's final
evidence report; it is the evidence for the decision gates the plan hangs on.
Sections 3–8 are deliberately not started: task 6.3 builds on the task 2.4
decision, which is now accepted, and on the two design gaps that acceptance
added to it.

Commit: `HEAD` of `feat/pluggable-git-storage` at the time of writing (see the
PR's commit list; the run below is the final fresh run after the last edit).

## What was proved, and why it needed proving

The object-store backend's entire consistency model reduces to one behaviour: a
conditional `PutObject` must fail when its precondition no longer holds. If the
local test double does not implement that faithfully, every concurrency test
written later is green and meaningless. `ConditionalWriteFidelityTests` tests the
double before anything trusts it.

Target: **Floci 1.5.33** (`docker.io/floci/floci`, pinned — not `latest`), a
local AWS emulator self-described as an open-source alternative to LocalStack
Community, started by the **Arconia Floci dev service**
(`io.arconia:arconia-dev-services-floci` 0.29.0) and reached through the
`S3Client` Spring Cloud AWS auto-configures from the connection details that dev
service publishes. The test names no image, no port and no URL.

| # | Assertion | Result |
| --- | --- | --- |
| 1 | `If-Match` with the current ETag succeeds and returns a **new** ETag | **PASS** |
| 2 | `If-Match` with a stale ETag returns **412**, and the stored object is byte-for-byte unchanged | **PASS** |
| 3 | `If-None-Match: *` creates exactly once; the second attempt returns 412 and does not overwrite | **PASS** |
| 4 | 8 threads racing off one barrier from one base ETag → exactly **1** winner, **7** × 412, zero other errors | **PASS** |
| 5 | ETags chain: the ETag a conditional PUT returns is the one the next must present, over 5 generations, each superseded ETag ceasing to be accepted | **PASS** |

Assertion 2 checks the *content* after the refusal, not just the status code: an
emulator that answers 412 and writes anyway is the worst possible outcome and is
exactly what a status-only assertion misses. Assertion 4 uses real threads off a
`CountDownLatch`, because sequential calls cannot distinguish a correct
implementation from one that ignores preconditions but happens to be ordered.

## Proving the spike can fail

A green suite against a test double proves nothing by itself, so the suite was
mutated twice and re-run.

**Mutation A** — drop `If-Match` from the stale write in assertion 2:

```
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
Expecting code to raise a throwable.
```

The unconditional write succeeds where the conditional one was refused, so the
412 is caused by the precondition and not by the emulator refusing writes
generally.

**Mutation B** — drop `If-Match` from the concurrent writers in assertion 4:

```
[ERROR]   ConditionalWriteFidelityTests.exactlyOneConcurrentWriterWins:216
  [compare-and-swap means exactly one writer commits from a given base version]
  expected: 1L
   but was: 8L
```

Eight winners instead of one — precisely the signature of a store that ignores
preconditions, and precisely what the suite exists to catch. The original was
restored before the gate run below.

## Repeatability

The suite was run repeatedly across three container wirings — a raw
`GenericContainer`, `io.floci:testcontainers-floci`, and finally the Arconia dev
service; `Tests run: 5, Failures: 0, Errors: 0` every time. Assertion 4 is
concurrent and therefore the flakiness candidate; it did not vary under any
wiring, including the dev service, which was the specific worry — a dev service
that pooled or reused state could have weakened it.

## The dev service, and a wrong answer that was believed first

The project rule is that a container-backed test uses an Arconia dev service
wherever one exists, so that one container serves both `bootRun` and the suite.
`io.arconia:arconia-dev-services-floci` exists, and this spike now uses it.

An earlier revision of this report said the opposite: that the dev service was
unusable, because putting Spring Cloud AWS on the classpath made awspring's
`CredentialsProviderAutoConfiguration` fail **every** Spring context in the
project for want of an `AwsRegionProvider` bean — measured at the time as the
whole gateway suite erroring on startup (`FacadeTests`, `IngestionTests`,
`VettingTests`, `EstateStartupFailureTests` and the rest) — and that the only
escapes were to configure Spring Cloud AWS for real or to exclude its
auto-configurations, both of which put AWS-vendor configuration into a product
that does not use AWS.

That was wrong. The cause was a dependency choice, not an incompatibility.

`FlociDevServicesAutoConfiguration` is `@ConditionalOnClass` on
`io.awspring.cloud.autoconfigure.core.AwsConnectionDetails`, which lives in
`spring-cloud-aws-autoconfigure` — so adding exactly that artifact satisfies the
condition and looks correct. It does not bring `spring-cloud-aws-core`, so
awspring's `RegionProviderAutoConfiguration`
(`@ConditionalOnClass(io.awspring.cloud.core.region.StaticRegionProvider, …)`)
is silently skipped, while `CredentialsProviderAutoConfiguration` — conditioned
only on AWS SDK classes — still activates and takes `AwsRegionProvider` as a
constructor parameter rather than an `ObjectProvider`. The bean never exists and
every context fails. The failure names awspring, not the dev service, which is
why it read as an incompatibility.

Three runs settled it:

| Classpath | AWS configuration | Result |
| --- | --- | --- |
| `spring-cloud-aws-autoconfigure` only | `spring.cloud.aws.region.static` set | **FAILS** — `No qualifying bean of type 'AwsRegionProvider'` |
| `spring-cloud-aws-starter` | `spring.cloud.aws.region.static` set | **BUILD SUCCESS**, 192 tests |
| `spring-cloud-aws-starter` | none at all | **BUILD SUCCESS**, 192 tests |

The third row is the interesting one: no static region, no credentials, and it
still works. `DefaultAwsRegionProviderChain` only resolves a region when
`getRegion()` is called, which never happens unless something builds a client
that needs it — the bean merely has to exist. The region property was a red
herring, and so was the original diagnosis.

Reported upstream as
[arconia-io/arconia#281](https://github.com/arconia-io/arconia/issues/281):
the docs say only "requires Spring Cloud AWS and the AWS SDK on the classpath",
and the suggestion is to name the artifact and to condition the dev service on a
`spring-cloud-aws-core` class, so an incomplete classpath leaves it inactive
rather than breaking the application.

The dependency is `test` scope rather than `optional` like the Postgres dev
service, for the one reason that survives: no application code touches object
storage yet, so there is no `bootRun` consumer to share a container with. That
flip is task 6.8.

## Task 2.3 — MinIO probed (and why neither MinIO nor AWS S3 closes the row)

`ConditionalWriteFidelityTests` verifies *Floci*. Task 2.3 exists because that
is an emulator, and the design's portability claim names real stores.

**AWS S3 was not attempted.** It requires an account and credentials this change
does not have, and acquiring them was explicitly out of scope. That row of the
decision-9 table therefore stays *documented but unexercised*, and the
supported-store list stays unpublishable as verified. It is recorded here as not
done rather than glossed, because the alternative — reasoning from AWS's
documentation and calling the row closed — is exactly the kind of unearned
confidence the fidelity spike exists to refuse.

**MinIO was verified.** The five assertions were re-run, unchanged in substance,
against a real MinIO server:

```
$ podman run -d --name sg-minio-probe -p 19000:9000 \
    -e MINIO_ROOT_USER=… -e MINIO_ROOT_PASSWORD=… \
    docker.io/minio/minio:RELEASE.2025-07-23T15-54-02Z server /data
$ java -cp "$(cat cp.txt)" MinioProbe.java     # ×3
endpoint: http://localhost:19000
PASS  1. If-Match with the current ETag succeeds and returns a new ETag
PASS  2. If-Match with a stale ETag returns 412 and does not write
PASS  3. If-None-Match: * creates exactly once
PASS  4. Under real concurrency from one base ETag, exactly one writer wins
PASS  5. ETags chain correctly across successive conditional updates

Tests run: 5, Failures: 0
```

Three consecutive runs, identical. Assertion 4 is the concurrent one and is
therefore the flakiness candidate; it did not vary.

**And the probe was mutated, for the same reason the Floci spike was.** A green
run against a store proves nothing unless the run can go red. Dropping
`If-Match` from assertion 2's stale write and from assertion 4's eight
concurrent writers, in one run:

```
PASS  1. If-Match with the current ETag succeeds and returns a new ETag
FAIL  2. If-Match with a stale ETag returns 412 and does not write
        java.lang.AssertionError: stale If-Match refused with 412, got 200
PASS  3. If-None-Match: * creates exactly once
FAIL  4. Under real concurrency from one base ETag, exactly one writer wins
        java.lang.AssertionError: exactly one winner, got 8

Tests run: 5, Failures: 2
```

`got 200` where 412 was required, and eight winners where one was required —
the same discriminating signature the Floci mutations produced. The original was
restored and re-run green before the container was removed.

So MinIO is a second independent store on which the design's single exotic
primitive is shown to hold, and the first that is a production-grade store
rather than an emulator. That is worth having as evidence. It does **not** close
task 2.3: the row that closes it is real AWS S3, and MinIO is not adopted as a
standing test store for the reason below.

**Where this run lives, and why it stays there.** It is an out-of-band probe: a
standalone program driving the same five assertions against a MinIO container
started by hand, not a JUnit test in the build — and it will not be promoted
into one. **MinIO stopped publishing free container images around October 2025.**
Pinning a gate to `RELEASE.2025-07-23T15-54-02Z` would mean a frozen tag with no
upgrade path and no guarantee it stays hosted; a test whose image supply has
already ended is a test that breaks later for reasons unrelated to this code.

Two further facts, recorded so nobody re-derives them: there is no Arconia dev
service for MinIO in either the 0.29.0 or the 0.30.0 BOM, so adopting it would
have meant a hand-rolled container or a dev service written against Arconia's
public `DevServicesRegistrar` — both were viable — and Arconia's own container
reuse and cross-application sharing activate only under `BootstrapMode.DEV`, so
neither would have deduplicated containers across the suite's Spring contexts
anyway.

**Floci is therefore the single in-build store**, and the portability question a
second store would have answered is left to real AWS S3 in task 2.3 — the store
that actually matters. This probe stands as evidence that the primitive is not a
Floci artefact; it is not a gate, and this report does not pretend it is one.

One incidental finding worth carrying into the backend. The first MinIO run
failed assertion 5 with `NoHttpResponseException: The target server failed to
respond` — a connection the Apache HTTP client had pooled during the eight-way
concurrency test and MinIO had since closed. Rebuilding the `S3Client` per
assertion fixed it; MinIO was never at fault. A long-lived `S3Client` in the
backend will need a connection TTL below the store's idle timeout, or the first
request after a quiet period will fail in a way that reads as a storage fault.

## Task 2.4 — the ref database recommendation

Recorded in full as **design decision 10**. Researched against the JGit classes
actually on the classpath — `org.eclipse.jgit-7.7.1.202607240634-r.jar` and its
sources jar — rather than from memory, which matters because these are
`internal` APIs whose shape changes between releases.

**Recommendation: a plain `DfsRefDatabase` over the manifest**, not
`DfsReftableDatabase`.

The four findings that decided it:

1. `DfsRefDatabase`'s entire abstract surface is `scanAllRefs()`,
   `compareAndPut(Ref, Ref)` and `compareAndRemove(Ref)`. That *is* a
   compare-and-swap interface, and it is the same shape as the manifest
   transition decision 3 already commits to.
2. Reftables are stored as **pack files** — `DfsReftableBatchRefUpdate
   .applyUpdates` calls `odb.newPack(PackSource.INSERT)`,
   `odb.writeFile(pack, REFTABLE)`, `odb.commitPack(...)`. So reftable does not
   move ref state out of our code; it moves it from the manifest into the pack
   list, and the conditional write must then guard `commitPackImpl` instead. The
   consistency mechanism is unchanged; only what it protects moves.
3. `ReftableBatchRefUpdate.execute` guards its precondition check and its write
   with a plain in-JVM `ReentrantLock`. Reftable's
   `performsAtomicTransactions() == true` is a statement about all-or-nothing
   *within one process*, not about cross-writer isolation — so it would not have
   solved the multi-pod problem for us, and a losing writer would discover the
   conflict only after a reftable had been written and needed pruning.
4. The cost of the recommendation, stated rather than discovered later:
   `RefDatabase.performsAtomicTransactions()` defaults to `false`, where both
   `RefDirectory` (today's backend) and `DfsReftableDatabase` return `true`, and
   `ReceivePack` advertises the `atomic` push capability only when it is `true`.
   `GitPublishConfiguration` already sets `receivePack.setAtomic(true)`. So the
   subclass must override `performsAtomicTransactions()` and `newBatchUpdate()`
   to keep the two backends observably identical — which is also the answer to
   task 2.6, and would have been needed under reftable anyway.

Also checked, because it would have been a real argument the other way and is
not: choosing the manifest loses no compaction machinery. Both
`DfsGarbageCollector` and `DfsPackCompactor` carry reftable machinery, and in
both it is **opt-in** — the collector's `setReftableConfig` /
`convertToReftable` / `writeReftable` path and the compactor's
`compactReftables`, guarded by `reftableConfig != null && !srcReftables.isEmpty()`,
are inert while `reftableConfig` is null, which is the default. An earlier
revision of this report said the collector "has no reftable coupling at all";
`javap` on `DfsGarbageCollector` lists nine reftable members, so that was wrong.
The conclusion it supported — GC and compaction work unchanged over a
manifest-based ref database — holds.

**The recommendation was reviewed independently and accepted**, with three
factual corrections (this one, the `DfsObjDatabase` method names, and a
withdrawn staleness argument) and two design gaps that are now recorded as
consequences rather than discovered during implementation: the per-ref versus
whole-manifest precondition mismatch, and the unbounded cross-replica
revocation window. Both are in decision 10.

## Conclusion

The plan survives unchanged. Task 2.2's fallback is **not** triggered: the
conditional-write contract stays in the default `verify` and the offline build
stays offline. No upstream contribution is needed.

The honest limit is now narrower but real. Two independent stores — Floci and
MinIO — implement the one primitive the design rests on, and the assertions
discriminate on both. **AWS S3, the production target, is still unexercised**,
so task 2.3 stays unchecked and the supported-store list is not publishable as
verified. Closing that row needs an account this change did not have and did not
try to obtain.

Task 2.4 is answered as a recommendation (design decision 10) and needs the
owner's acceptance before task 6.3 can start. Nothing in sections 3–8 was
implemented, deliberately: building the backend before that decision is settled
is how a design decision becomes an accident.

## Section 4 — the red runs

Task 4.5. Every case in section 4 was written before the code that satisfies it
and run against the code as it stood. Two went red on their own; the other four
passed on first run, so each was instead put through a deliberate mutation of the
production code, because a test that has never been observed to fail is a test
nobody has checked. Both kinds are below with the failure text.

Environment for all of it: Floci `1.5.33` through the Arconia Floci dev service
on podman, `TESTCONTAINERS_RYUK_DISABLED=true`,
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock` (the
socket path inside the podman machine — Floci mounts it, and without the
override the container fails to start with `failed to change selinux label ...
/var/run/docker.sock`).

### Red 1 — the refusal did not name what it would have accepted (task 4.1)

`StorageBackendSelectionTests.anUnrecognisedBackendNamesTheAcceptedValues`, against
the enum binding as section 5 left it:

```
Caused by: java.lang.IllegalArgumentException: No enum constant
    dev.skillsgateway.server.config.SkillsGatewayProperties.Storage.Backend.magic
...
to contain:
  "filesystem"
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

The refusal named the setting and the value and neither spelling that would have
worked. Green after a `@ConfigurationPropertiesBinding` converter whose message
lists the accepted set; the suite is 10/10.

### Red 2 — a publication silently lost to a concurrent revocation

The first shape of the superseding-approval case asserted that the marketplace
ends up serving the successor on every interleaving. It did not:

```
java.lang.AssertionError:
Expecting actual:
  LOCK_FAILURE
to be in:
  [NEW, FORCED, NO_CHANGE]
    at ObjectStoreConcurrencyTests.setRef
```

A revocation that lands first deletes `refs/heads/main`, and the publication that
was moving that same reference then fails its own reference-level precondition —
correctly, and identically on both backends: this is not an object-store defect,
`RefDirectory` does the same. What the case now pins down is the property the
seam actually owns — *exactly one* of the two writers decides the served tip, the
loser is refused **out loud** so its caller can retry, the successor's pinned
reference lands on every interleaving, and a retry converges on serving it. Eight
rounds per run.

**Finding, deliberately not fixed here.** `ApprovalService.approve` calls
`main.forceUpdate()` and discards the result, so on this interleaving the
publication is dropped while the database records the snapshot approved — the same
defect class as `FilesystemGitStorage.deleteRef`, which task 3.3 found and fixed,
on the other end of the same path. It is a caller-side defect above the storage
seam, on a trust boundary, and it belongs to a change that can give it its own
requirement and adversarial tests rather than to section 4.

### Red 3 — a torn manifest read, and what it turned out to be

Under eight-way contention the interleaved case failed intermittently:

```
com.fasterxml.jackson.databind.JsonMappingException: Unexpected end-of-input in field name
 (through reference chain: RepositoryManifest["packs"]->java.util.ArrayList[7])
```

Chased rather than retried away. Instrumenting the read to re-fetch on a parse
failure settled it in one line:

```
DIAG first len=6291 etag="e68578600e931147ba2ef45373d5a08c" | reread len=6392
    etag="22438f949dacff020b7f66d958fc3b3a" PARSES
```

The store served a **partially written object** — 6291 bytes of what became 6392,
with a matching `Content-Length` and an ETag of its own — while an overwrite was
in flight. That is Floci, not us: Amazon S3 does not tear a `PutObject` that way,
and the fidelity spike (task 2.1) never exercised a `GET` racing an overwrite of
the same key, which decision 10 had in fact already listed as something the
spike does not cover. Two things came out of it, both kept:

- `S3ObjectStoreClient` now checks a body against the `Content-Length` the store
  declared, so a genuinely short response is diagnosed where it happens instead of
  as a corrupt repository three layers up. (It did not fire here — Floci's length
  matched its torn body — but a short read is a real failure mode with a
  one-comparison detector.)
- `ManifestStore` reads once more before believing a manifest is corrupt. One
  retry, not a loop: corruption must stay reachable, and a store that tears every
  read has to be found out rather than worked around.

**Also recorded, because it is a capacity statement rather than a defect.** At
twenty-four concurrent writers on one repository the bounded retry is exhausted
and the transition fails loudly:

```
java.io.IOException: could not apply revocation of refs/snapshots/c93d1f25... :
    the repository manifest .../manifest was rewritten by another writer on each of 8 attempts
```

That is `MAX_ATTEMPTS = 8` behaving exactly as designed — bounded, counted, and
raised rather than swallowed. The suite runs at eight writers, which is well
inside the bound and already far beyond human-paced approvals.

### Mutations — the four cases that passed on first run

Each mutation was applied to the production code alone, the case run, and the
mutation reverted.

| Case | Mutation | Result |
| --- | --- | --- |
| `concurrentRevocationsOfASupersededSnapshotStopNothing` | `unpublish` reports it stopped the serving whenever the pinned reference existed, rather than only when the served tip was that snapshot | `Tests run: 1, Failures: 1` |
| `interleavedPublicationsAndRevocationsLoseNothing` | `ManifestStore.MAX_ATTEMPTS` 8 → 1, so a lost compare-and-swap is never retried | `Tests run: 1, Errors: 1` |
| `aWriterKilledBeforeTheManifestWriteNamesNothing` | `commitPackImpl` swallows the `IOException` from the manifest transition | `Tests run: 1, Failures: 1` |
| `anUnreachableStoreIsReportedDown` (section 9) | `checkReachable()` does not read the store | `Tests run: 1, Failures: 1` |
| `theBackendsCountersArePublishedAsMeters` (section 9) | the write-ahead depth gauge is not registered | `Tests run: 1, Errors: 1` |

### What section 4 did not write, and why

Tasks 4.3 and 4.4 verify GW_0114 and GW_0115, whose requirement text task 1.1
reserves for sections 7 and 8. `reqstool status` counts a requirement with no
`@Requirements` annotation and no passing SVC as incomplete and fails the gate, so
writing either SVC before its implementation would break traceability to prove a
point about ordering. They land with the sections that implement them, exactly as
GW_0111 landed with section 5.

## Gate run

Final fresh run after the last edit, on the merge of `origin/main` into this
branch. That merge brought in #139 (native PostgreSQL enum types), which landed
after the previous evidence run — hence the test count moving from 203 to 212.
A gate run against a branch that has not merged main describes a tree nobody
will merge, so it is not evidence.

The one exception, stated rather than hidden: the *numbers* below were pasted
into this file after the run that produced them, so this file is one edit newer
than the tree the Java, frontend and e2e gates saw. That edit is Markdown inside
`openspec/changes/`, which only `openspec validate` and `reqstool` read, and
both were re-run against the final text — `27 passed, 0 failed` and `PASS` —
along with `mkdocs build --strict`.

`TESTCONTAINERS_RYUK_DISABLED=true` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock` were
exported (see "Environment" below).

```
$ ./mvnw clean verify
[INFO] Tests run: 212, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:31 min
```

212 tests, 0 failures, 0 errors (surefire agreeing across 54 report files);
5 of them are the Floci fidelity spike —

```
Test set: dev.skillsgateway.server.storage.ConditionalWriteFidelityTests
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.703 s
```

```
$ (cd src/main/frontend && pnpm test:stories)
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

```
$ (cd src/main/frontend && pnpm e2e)
  13 passed (24.6s)
```

```
$ reqstool status local -p docs/reqstool
113/113 complete · 0 incomplete · PASS
```

```
$ openspec validate --all --strict
Totals: 27 passed, 0 failed (27 items)
```

```
$ mkdocs build --strict
INFO    -  Documentation built in 0.65 seconds
```

The MinIO probe of task 2.3 is **not** part of this run and is not part of
`verify` — see the task 2.3 section above for why, and for its own output.

## Environment

- `TESTCONTAINERS_RYUK_DISABLED=true` is required on this machine — Ryuk cannot
  start on rootless Podman here.
- `DOCKER_HOST` **was** required, contrary to expectation.
  `~/.testcontainers.properties` names the correct socket
  (`unix:///var/folders/…/podman/podman-machine-default-api.sock`, matching
  `podman machine inspect`), but Testcontainers 2.0.5 did not consult it: the
  failure listed only `UnixSocketClientProviderStrategy` and
  `DockerDesktopClientProviderStrategy` as attempted, with no
  environment/property strategy among them. Exporting the same path as
  `DOCKER_HOST` resolved it immediately
  (`Found Docker environment with Environment variables, system properties and
  defaults`). This was a configuration-precedence issue in Testcontainers 2.x,
  **not** the memory pressure that was suspected — the Podman VM reports 1942 MB
  and no OOM or startup timeout occurred in any run.
- `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock` is
  **newly required** on this machine. `FlociContainer` unconditionally
  bind-mounts the docker socket (`withFileSystemBind(DockerClientFactory
  .instance().getRemoteDockerUnixSocketPath(), "/var/run/docker.sock")`), and
  without the override that resolves to the macOS-side podman socket path, which
  does not exist inside the podman VM: `Status 500: … making volume mountpoint
  … operation not supported`. On Linux CI, where the path is a real
  `/var/run/docker.sock`, the override is unnecessary. This applies to the
  Arconia dev service too — it wraps the same container.
- Containers were reaped with `podman container prune -f` afterwards, since
  nothing reaps them with Ryuk disabled.
- The task 2.3 MinIO probe needs none of the above: it is a plain
  `podman run -p 19000:9000` and a standalone client, with no Testcontainers
  involved, so neither the Ryuk nor the socket-override workaround applies to
  it.

## Dependencies added

Both test-scoped, for the spike and for the backend that follows:
`software.amazon.awssdk:bom` 2.46.7 (imported), `software.amazon.awssdk:s3`,
`io.arconia:arconia-dev-services-floci` (test scope, version from the Arconia
BOM) and `io.awspring.cloud:spring-cloud-aws-starter-s3` (test scope, from a
newly imported `spring-cloud-aws-dependencies` 4.1.0 BOM).
`io.floci:testcontainers-floci` was removed — the dev service brings the same
container class transitively.
