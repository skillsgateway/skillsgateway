# ADR 0012 — Native image as the release artifact: reassessment

*Proposed, 2026-09-07. Not decided. Supplements [ADR 0002](0002-toolchain-and-product-decisions.md), which chose GraalVM native-image as the release profile and remains in force until this is decided either way.*

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

## What would settle it

- **RSS per replica under load, JVM versus native.** The measurement that decides
  the density argument. A large gap justifies option A; a small one does not.
- **JVM container image size and startup.** Cuts weakly toward native; a one-time
  pull cost for a long-lived server.
- **How many unregistered reflection sites remain** on paths the service uses.
  Every additional one cuts toward option B.
- **Whether #308 manifests.** One run of the documented migration against the
  released binary settles it.
- **Peak throughput, JIT versus AOT**, for pack-serving under concurrency.

## Status

**Proposed.** ADR 0002 stands until this is decided. No work should be started on
option B on the strength of this document; the mitigations in option A are worth
doing regardless of the outcome, since they are the difference between a defect
class that is prevented and one that is discovered in production.
