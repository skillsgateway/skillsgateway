# Proposal: jvm-container-release

## Why

[ADR 0012](../../../../docs/decisions/0012-native-image-as-the-release-artifact.md)
— accepted 2026-09-07 — decided that the release artifact becomes a JVM
container and the GraalVM native image is dropped. This change implements that
decision.

The ADR's argument, in one line: the measured benefit was ~100 MB per instance
at a sizing where nothing is memory-bound, and the measured cost is a defect
class the test suite is structurally incapable of seeing — a condition or a
reflection target resolved when the image is built, where a correctly spelled,
correctly deployed setting is inert. Three instances are known, two of them
shipped and broke the login and ingest paths.

Two of the three are open and are live defects in the image being served until
this lands. Neither is worth fixing separately, because both stop existing when
the artifact does:

- [#293](https://github.com/skillsgateway/skillsgateway/issues/293) — JGit's
  auto-GC reads a config enum reflectively after every fetch; nothing registered
  it, so every ingest that reaches a fetch returns 500 on the published image and
  passes on the JVM.
- [#308](https://github.com/skillsgateway/skillsgateway/issues/308) — the
  storage-migration runner is behind `@ConditionalOnProperty`, so the documented
  migration procedure could be accepted and start an ordinary server instead:
  a data-movement path that can appear to succeed.

Issue [#310](https://github.com/skillsgateway/skillsgateway/issues/310).

## What Changes

Observable application behaviour is unchanged — no endpoint, no portal page and
no configuration key moves. What changes is the artifact those behaviours ship
in, and what that artifact can and cannot fail at.

- **The `Dockerfile` packages the Spring Boot jar on a `jlink`-produced Java 25
  runtime**, on `gcr.io/distroless/java-base-debian12:nonroot`. The properties
  the deployment posture depends on are unchanged and asserted: no shell, no
  package manager, uid 65532, a root filesystem nothing writes to. `release.yml`
  already builds and attaches the jar, so the artifact existed already.
- **The container workflow's two-runner matrix collapses to one `buildx`
  multi-arch build.** The cross-compilation constraint that forced a runner per
  architecture was native-image's; the jar is the same bytes on both.
- **The migration runner loses its `@ConditionalOnProperty`** and decides at
  runtime, which is the remedy already applied in `ForwardedHeadersConfig` for
  #272. An audit of the whole main source tree found this to be the only
  `@ConditionalOnProperty` or `@Profile` on an application bean, so the class is
  closed rather than sampled.
- **`GW_0015 — Container distribution` is amended** to state the properties the
  base image is chosen for rather than the binary it used to carry, and
  **`GW_0072 — Container image publication by digest`** to describe one build
  producing both platforms and one tagged index per release.
- **`GW_0114 — Verified migration between storage backends` is amended** to
  require that whether a start is a migration is decided from the configuration
  the running process is given.
- **The `native` Maven profile stays.** It costs nothing and leaves the way back
  open, as the ADR says. Nothing ships it.
- **Docs corrected in the same PR**, above all
  `guides/storage-backends.md`, whose migration procedure told operators to run
  the native binary — the substance of #308.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `release-packaging`: the image's contents and the shape of its publication.
- `git-storage`: the migration runner's availability is stated rather than
  implied.

## Impact

- **DB**: none.
- **Backend**: `GitStorageConfiguration.storageMigrationRunner` becomes
  unconditional and returns immediately when the flag is unset. No other
  application code changes; comments elsewhere that describe the native image
  are corrected.
- **API**: none.
- **Portal**: none.
- **Chart**: no template or value changes. The `resources` comment is corrected
  — it described a native binary's footprint — and now carries the measured
  idle figure and the heap-ceiling consequence of the container limit.
- **CI**: `native.yml` becomes one job on one runner; `graalvm/setup-graalvm`
  becomes `actions/setup-java`; `docker/setup-qemu-action` is added for the
  arm64 leg of the multi-arch build; the publish is one tagged push rather than
  two digest pushes plus a combine job. The smoke test additionally runs the
  container with a read-only root filesystem, which is the shape the chart
  deploys.
- **Docs**: `reference/container-image.md`, `reference/compatibility.md`,
  `reference/configuration.md`, `reference/observability.md`,
  `reference/decisions.md`, `guides/storage-backends.md`,
  `guides/deploying-on-kubernetes.md`, `guides/deploying-without-kubernetes.md`
  (new "Memory" section), `guides/local-development.md`,
  `guides/releasing.md`, plus `README.md`, `CONTRIBUTING.md` and `compose.yaml`.
- **Trust boundary**: this does not change facade authentication, approval or
  the registration allowlist. It does change what the shipped artifact is, so
  the assurance argument moves from "the suite passes" to "the suite passes and
  the artifact it passed against is the artifact that ships" — which is the
  point of the change rather than a risk it takes.
- **Declarative estate obligation**: nothing to add. No API-managed runtime
  state and no grantable role is introduced.
