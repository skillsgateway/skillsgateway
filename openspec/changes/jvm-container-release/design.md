# Design: jvm-container-release

ADR 0012 decided *what*. This records *how*, and the four judgements the ADR left
open.

## The base and the runtime

`gcr.io/distroless/java-base-debian12:nonroot` carries the OS libraries a JVM
needs and no JDK. The runtime comes from a `jlink` stage on
`eclipse-temurin:25-jdk`; distroless publishes no Java 24 or 25 image, so the
prebuilt `java21` variant is not an option for a Java 25 target.

The module set is written out explicitly rather than derived with `jdeps`. A
Spring application resolves much of its module graph reflectively, so a derived
set is one that fails at runtime instead of at build time — `java.desktop` alone
(for `java.beans`) would not appear in a static analysis of this jar. The set is
chosen generously; what proves it is the smoke test booting the image the stage
produced, which is why that step is worth more on this packaging than it was on
the last.

`eclipse-temurin:25-jre-noble` was the ADR's documented fallback if `jlink`
proved troublesome. It was not needed: the `jlink` stage worked first time, and
Temurin would have been seven times the base size with a shell in the image.

## Measurements

Taken on the image this change builds (arm64 host, podman, 1 GiB limit,
read-only root filesystem, `/tmp` and `/data` as tmpfs — the shape the chart
deploys), against a real PostgreSQL.

| | Native image (ADR 0012) | This image |
| --- | ---: | ---: |
| Image, compressed (pull) | 70.1 MB | ~130 MB |
| Image, uncompressed | — | ~195 MB |
| Startup | ~0.3 s | 3.61 s to `Started`, ~5 s to healthy |
| Resident memory, idle | 119 MB avg / 199 MB peak | 255 MB |

The compressed figure for the native image is the sum of the published
`0.2.0-b2` manifest's layer sizes, read from GHCR. The image here is the base
(46.1 MB), the `jlink` runtime (79.4 MB) and the jar (69.2 MB); the jar carries
the built portal, so it does not shrink much.

**Against the chart's reservation.** `helm/skills-gateway/values.yaml` requests
512 MiB and limits 1 GiB. 255 MB idle is half the request and a quarter of the
limit, so the existing sizing holds with headroom. Both figures are idle: JGit
packing a large upstream repository is the term neither contains, and it costs
the same on either packaging.

## The heap ceiling

The entrypoint sets `-XX:MaxRAMPercentage=75.0`.

This is not tuning for its own sake. The JVM's default ceiling is 25% of the
container limit, where GraalVM's default is 80% of it. Leaving the default would
have given JGit a third of the heap the previous artifact had while packing a
large repository — a regression introduced by the packaging change and
attributable to nothing in the application. 75% restores parity and is the
condition the measurement above was taken under.

The consequence is documented where it bites: a container with no memory limit
gives the heap 75% of the *host*, so both deployment guides now say to set one.

## CDS and the AOT cache: evaluated, not adopted

The ADR asks for this to be evaluated rather than assumed. Measured, on the same
jar and host, timing context refresh (`-Dspring.context.exit=onRefresh`), three
runs each:

| | Refresh |
| --- | ---: |
| No archive | 2.91 / 2.53 / 2.43 s |
| Spring Boot CDS archive | 2.35 / 2.22 / 2.21 s |

About 0.25 s, roughly 10%. It costs a 68.6 MB archive in the image — a third
again on top of the current size — and, decisively, a **training run that needs a
live PostgreSQL during `docker build`**, which the build has no access to.
Spring Boot's `-Dspring.context.exit=onRefresh` still refreshes the context, so
the DataSource and the schema migration are on that path.

Not adopted. A rollout taking three seconds longer is not a cost this service is
sensitive to, and buying 10% of it with a third of the image and a database
dependency in the image build is the wrong trade. Recorded here so the next
person starts from the number rather than from the idea.

## One job instead of two

The matrix existed because native-image compiles for the host it runs on. That
is gone, so both platforms come from one `buildx` invocation with QEMU for the
arm64 `jlink` stage, and the cross-job digest handoff (`push-by-digest`, digest
artifacts, a `publish` job to combine them) goes with it: one build pushes one
tagged index directly. The property that machinery existed to protect — GHCR's
tagged-versions listing holding one entry per release, with the platform
manifests present only as the index's untagged children — is preserved, and
`PackagingTests` asserts it against the new shape.

**What this gives up, stated plainly.** The old matrix smoke-tested each
platform on its own hardware. The new one smoke-tests `linux/amd64`, the
runner's own architecture, and builds `linux/arm64` from the same Dockerfile and
the same jar without booting it. Booting the arm64 image would mean booting a
JVM under QEMU, which the ADR already discarded as a measurement and which would
add minutes to every main-branch run. The exposure is narrow — the jar is
identical and the only platform-specific bytes are a `jlink` runtime built by the
same command — but it is a real reduction and is documented in
`reference/container-image.md` rather than left for someone to discover.

The SBOM attestation likewise moves from per-platform-manifest to the published
index digest, which is the digest the publish prints and the one a deployment
pins.

## What is deliberately not in this change

- **The `native` Maven profile stays.** ADR 0012 says so explicitly: it costs
  nothing and leaves the way back open if memory ever binds or Spring Boot
  closes the frozen-condition class.
- **The workflow file keeps the name `native.yml`.** `release.yml` calls it by
  path and a rename buys nothing but churn in a diff another change is stacking
  on. The workflow's `name:` and its job are renamed; a comment at the top says
  why the filename is historical.
- **The smoke test is not extended to perform a real ingest and a real clone.**
  That is [#311](https://github.com/skillsgateway/skillsgateway/issues/311), and
  ADR 0012 keeps it as the one option-A mitigation that survives the decision —
  it is about the smoke test, not about the artifact.
- **No build-time check for AOT-frozen conditions.** #308 asks for one as
  insurance against a fourth instance. With the artifact no longer built ahead of
  time there is nothing for it to protect, and the audit found one site in the
  whole tree. If the `native` profile is ever revived, the check comes back with
  it.
