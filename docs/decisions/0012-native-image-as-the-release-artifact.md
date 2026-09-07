# ADR 0012 — The release artifact is a JVM container, not a native image

*Accepted, 2026-09-07. Supersedes the release-profile decision in [ADR 0002](0002-toolchain-and-product-decisions.md); the rest of that ADR stands.*

## Context

ADR 0002 chose GraalVM native-image as the release profile — daily development
and tests on JIT, native builds in CI and at release — and used that choice to
justify a second one: JPA/Hibernate was rejected partly because it "adds
build-time enhancement, metadata weight, and lazy-proxy edge cases — for a
three-table schema it buys nothing."

Two things have changed since.

**The stated justification for the data-access decision is stale.** The schema is
now 18 tables across 15 `JdbcClient` repositories, roughly 2,400 lines. "For a
three-table schema it buys nothing" no longer describes the system. The
conclusion may well stand — the native-image argument against a reflective ORM
has if anything strengthened — but the recorded reasoning does not.

**The cost of the packaging choice stopped being theoretical.** Three defects of
one class are now known, all of them impossible on a JVM jar:

| Issue | What was frozen at build time | Effect |
| --- | --- | --- |
| [#272](https://github.com/skillsgateway/skillsgateway/issues/272) (fixed) | `@ConditionalOnProperty` on Spring's `ForwardedHeaderFilter` | every login failed behind a TLS-terminating proxy |
| [#293](https://github.com/skillsgateway/skillsgateway/issues/293) (open) | reflection on a JGit enum, registered nowhere | every ingest returns 500 |
| [#308](https://github.com/skillsgateway/skillsgateway/issues/308) (open) | `@ConditionalOnProperty` on the storage-migration runner | a migration that may report success without migrating |

The third was found by grep, not by failure, which is the point: nothing looks
for this class today.

The decision was raised once informally, on 2026-09-05, and affirmed — keep
GraalVM — on the strength of multi-instance deployment, where per-replica
footprint multiplies. That reasoning was never written down in this repository,
which is what this ADR exists to correct regardless of which way it lands.

## The decision to be made

**What does a release consist of?** Today, a native binary in a distroless
container. The alternatives are a JVM jar in a container, or both.

It is not a packaging detail, because three things follow from it.

**Which defects are possible at all.** Native image resolves
`@ConditionalOnProperty`, `@Profile` and reflection targets when the image is
built. That creates a class where a correctly-spelled, correctly-valued,
successfully-deployed setting is inert, and where reflection that works on the
JVM throws at runtime. All three defects above are that class. On a jar, none of
them can occur.

**What the test suite can see.** The suite runs on the JVM, so it is
structurally blind to that class: 586 green tests said nothing about either
shipped outage. Choosing native image means accepting that the gates do not
exercise the artifact that ships, and paying separately — a reflection
configuration, a smoke test that performs real work, a check for AOT-frozen
conditions — to close a gap that does not exist for a jar.

**What is bought in exchange.** Fast start, small image, low per-replica memory,
a smaller dynamic attack surface. For a long-lived server — which
[architecture](../manual/architecture.md) §12 confirms this is — only the memory
argument is load-bearing, and it has never been measured here.

So the decision is a trade that cannot currently be priced: an **unmeasured**
memory and attack-surface benefit against a **measured** defect class the safety
net cannot catch.

## Options

### A. Keep native image, and fund the mitigations

Accept the defect class as a standing cost and stop paying it blindly:

1. A reflection configuration owned in-repo (`META-INF/native-image/`), seeded by
   the tracing agent against the full e2e suite. None exists today — #293 is the
   first symptom, not the last.
2. A smoke test that performs a **real ingest and a real clone** against the built
   image. The present one asserts the container boots and reports healthy, which
   is why #293 — deterministic on every fetch — shipped.
3. A build-time check that fails on any `@ConditionalOnProperty` or `@Profile`
   guarding an application bean whose property is operator-facing. #308 would
   fail it today.
4. Measure and record RSS, startup and image size for both packagings, so the
   next reassessment starts from numbers.

### B. Drop native image; ship the JVM jar

`release.yml` already builds and attaches the boot jar, so the artifact exists.
The `Dockerfile` would target a distroless Java or jlink base; `native.yml`'s
two-runner matrix collapses to one multi-arch build.

The #272/#293/#308 class disappears structurally. Lost: ~0.3 s startup becomes
seconds, the image grows, the closed-world attack-surface claim goes, and the
unmeasured per-replica memory advantage goes with it. The `native` profile stays
in the pom, so the way back remains open — behind option A's mitigations.

### C. Ship both

Jar as the supported artifact, native as opt-in. Doubles the release surface and
the smoke-testing burden, and leaves the question of which one operators actually
run unanswered. Recorded for completeness; not recommended by either assessment.

## Assessments on record

Two independent reads were taken, and they disagree, which is itself worth
recording.

**Drop it (option B).** The benefit side of this repository contains exactly one
number — 0.3 s startup, from a 2026-08-14 spike. The cost side contains two
production defects in the login and ingest paths, a third found latent, and nine
distinct native-specific accommodations in the codebase. For a long-lived server
the benefit that matters least is the one that is measured, and the one that
might matter is not.

**Keep it and fund the mitigations (option A).** The deployment posture already
assumes it — distroless, read-only root filesystem, non-root, no shell — which is
a real defence for something whose job is handling untrusted upstream
repositories. Multi-instance deployment is where per-replica footprint pays. The
three defects are each individually cheap to prevent, and the mitigations are
worth having on their own merits.

Both agree on one thing: **the decision cannot be defended on current evidence in
either direction.**

## One half of the measurement now exists

Taken 2026-09-07 from a running deployment of the published native image, over a
full 24 hours at five-minute resolution (288 datapoints):

| | |
| --- | ---: |
| Memory reserved for the instance | 1024 MiB |
| Memory used — 24 h average | 119 MiB |
| Memory used — 24 h peak | 199 MiB |

The native image is using **12–19% of what is reserved for it**, leaving roughly
fivefold headroom.

**This weakens the density argument rather than supporting it.** Per-replica
footprint only pays when memory is the binding constraint, and here it is not
close to binding. A JVM jar for this application would plausibly sit in the low
hundreds of MiB — inside the same reservation. Where instances are billed by
reservation rather than by use, an artifact that is heavier but still fits costs
nothing. On these numbers the native image's memory advantage is headroom nobody
is consuming.

Two honest limits on the figure:

- **It is almost certainly an idle measurement.** [#293](https://github.com/skillsgateway/skillsgateway/issues/293)
  means every ingest fails on the published image, so no successful ingest has run
  on the instance measured. JGit packing an upstream repository is the dominant
  memory term in this application and none of it is in that 199 MiB peak. The real
  working set is higher for **both** packagings — and since JGit's buffers cost the
  same either way, the *proportional* gap between them is likely smaller than these
  idle numbers suggest. That cuts against native image again, not for it.
- **The JVM half is still unmeasured.** This is one side of a comparison. A jar
  container running the same workload is the missing number, and it is a few hours
  of work rather than a query.

## Both halves are now measured

The two artifacts were run under the same 1 GiB container limit, idle, with the
same configuration and a PostgreSQL of their own:

| Artifact | Memory used | Conditions |
| --- | ---: | --- |
| Native image | **119 MB** average, **199 MB** peak | 24 h at five-minute resolution, real hardware |
| JVM jar | **259 MB** | local container, same limit, host-native architecture |

**The gap is roughly 60–140 MB per instance — about 1.3–2.2×**, far short of the
3–5× native image is usually credited with. Two reasons specific to this service:
JGit's buffers and the connection pool cost the same on either packaging, and a
JVM under a container limit sizes its heap to fit rather than sprawling.

Against a 1 GiB reservation both fit with room to spare — the JVM at about a
quarter of it, the native image at an eighth. Neither is close to binding, and
where instances are billed by reservation an artifact that is heavier but still
fits costs nothing at current sizing.

A third measurement was taken and **discarded as invalid**: the published native
image is amd64-only, so running it on an arm64 host put it under emulation, where
it reported 283 MB — higher than the JVM. That measures the emulator, not the
artifact, and is recorded here only so nobody repeats it and believes it.

Two limits remain on the honest reading. Both figures are **idle**: #293 means no
ingest can succeed on the native image, so neither includes JGit packing a real
repository, which is the dominant term for this service. And a same-host
comparison would need an arm64 native build, which is a full native-image compile.
Neither gap changes the conclusion, because both would raise both numbers.

## Decision

**The release artifact becomes a JVM container.** The native image is dropped.

The measured benefit is ~100 MB per instance at a sizing where nothing is
memory-bound. The measured cost is a defect class the test suite is structurally
incapable of seeing — three instances known, two of which shipped and broke the
login and ingest paths, and a third found by grep in a data-migration path that
can report success without migrating. That trade does not hold.

### The base image

`gcr.io/distroless/java-base-debian12:nonroot` carrying a `jlink`-produced Java 25
runtime.

Measured alternatives:

| Base | Size | Notes |
| --- | ---: | --- |
| `distroless/base-debian12:nonroot` | 32.7 MB | today's base; no JVM, native binary only |
| **`distroless/java-base-debian12:nonroot`** | **46.1 MB** | OS libraries a JVM needs, no JDK — designed to receive a `jlink` runtime |
| `eclipse-temurin:25-jre-noble` | 337 MB | full JRE on Ubuntu; a shell and a much larger package surface |
| `distroless/java21-debian12:nonroot` | — | **unusable**: this project targets Java 25, and distroless publishes no Java 24 or 25 image |

`java-base` plus `jlink` keeps every property the current posture depends on — no
shell, non-root uid 65532, read-only root filesystem, small package surface — while
supporting Java 25, which the prebuilt distroless Java images do not. Temurin is
the fallback if `jlink` proves troublesome, at roughly seven times the base size
and with a shell in the image.

Startup regresses from ~0.3 s to seconds. Spring Boot's CDS and AOT-cache support
can recover part of it and should be evaluated, but a rollout taking seconds
longer is not a cost this service is sensitive to.

## What would settle it

- **RSS per replica under load, for a JVM jar.** The native half is now measured
  (above). The jar half is not, and it is what closes the comparison. If a jar also
  fits well inside the current reservation, the density argument is finished.
- **JVM container image size and startup.** Cuts weakly toward native; a one-time
  pull cost for a long-lived server.
- **How many unregistered reflection sites remain** on paths the service uses.
  Every additional one cuts toward option B.
- **Whether #308 manifests.** One run of the documented migration against the
  released binary settles it.
- **Peak throughput, JIT versus AOT**, for pack-serving under concurrency.

## Status

**Accepted.** Option B. The release-profile decision in ADR 0002 is superseded;
everything else in that ADR stands, including the rejection of JPA — whose
recorded justification ("for a three-table schema") is stale at 18 tables and
should be amended to rest on the argument that still holds.

The `native` Maven profile is not deleted. Keeping it costs nothing and leaves the
way back open should the measurements change — a deployment where memory genuinely
binds, or a Spring Boot release that closes the frozen-condition class.

One mitigation from option A survives the decision and should still be done: a
smoke test that exercises a **real ingest and a real clone** against the built
image. The present test asserts only that the container boots and reports healthy,
which is why #293 — deterministic on every fetch — shipped. That gap is about the
smoke test, not about native image, and changing the artifact does not close it.
