# Evidence: pluggable-git-storage

Scope of this report: **task 2.1 only** — the conditional-write fidelity spike.
Rebased onto `main` after #134 and #135.
The DFS backend itself is not implemented, so this is not the change's final
evidence report; it is the evidence for the decision gate the plan hangs on.

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

## Conclusion

The plan survives unchanged. Task 2.2's fallback is **not** triggered: the
conditional-write contract stays in the default `verify` and the offline build
stays offline. No upstream contribution is needed.

The honest limit: this verifies *Floci*, not AWS S3. It shows the design is
implementable and that our tests are meaningful; it does not certify the
production target. Task 2.3 (the same five assertions against real S3 and MinIO)
stays open, and the supported-store list is not publishable until it closes.

## Gate run

Final fresh run after the last edit, on the merge of `origin/main` (`007f996`,
after #138, #140 and #141) into this branch. An earlier run on the pre-merge
tree reported 12 e2e tests where main now has 13 — the discrepancy is what
caught it. A gate run against a branch that has not merged main describes a tree
nobody will merge, so it is not evidence.

`TESTCONTAINERS_RYUK_DISABLED=true` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock` were
exported (see "Environment" below).

```
$ ./mvnw clean verify
[INFO] BUILD SUCCESS
```

203 tests, 0 failures, 0 errors (aggregated from `target/surefire-reports/*.xml`);
5 of them are this spike, and the rest is what #138 and #139 brought in.

```
$ (cd src/main/frontend && pnpm test:stories)
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

```
$ (cd src/main/frontend && pnpm e2e)
  13 passed (28.7s)
```

```
$ reqstool status local -p docs/reqstool
112/112 complete · 0 incomplete · PASS
```

```
$ openspec validate --all --strict
Totals: 26 passed, 0 failed (26 items)
```

```
$ mkdocs build --strict
INFO    -  Documentation built in 0.64 seconds
```

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

## Dependencies added

Both test-scoped, for the spike and for the backend that follows:
`software.amazon.awssdk:bom` 2.46.7 (imported), `software.amazon.awssdk:s3`,
`io.arconia:arconia-dev-services-floci` (test scope, version from the Arconia
BOM) and `io.awspring.cloud:spring-cloud-aws-starter-s3` (test scope, from a
newly imported `spring-cloud-aws-dependencies` 4.1.0 BOM).
`io.floci:testcontainers-floci` was removed — the dev service brings the same
container class transitively.
