## Why

`ghcr.io/skillsgateway/skillsgateway` publishes a single `linux/amd64` manifest:
`native.yml` runs one `runs-on: ubuntu-latest` job, so the pushed tag is one
platform-specific manifest rather than an image index. arm64 is the cheaper and
increasingly default choice on managed container platforms (Fargate Graviton,
Cloud Run, Azure Container Apps, most Kubernetes node pools, Apple Silicon
laptops), so anything running there either cannot run the gateway or has to
carry a per-service `X86_64` platform pin. ([#265](https://github.com/skillsgateway/skillsgateway/issues/265))

The image packages a GraalVM native binary, so architecture is a compile
target, not a packaging choice: `native-image` does not cross-compile, and
building it under QEMU emulation turns a build that already takes minutes into
one that plausibly takes 30–60+, on every push and every scheduled run. This
needs a real second build on real arm64 hardware, which GitHub now offers as a
hosted runner (`ubuntu-24.04-arm`).

While implementing this, review of the resulting GHCR package page surfaced a
second problem this change also fixes: every main-branch push has published a
`sha-<sha>` tag with no retention, so the tagged-versions listing accumulates
one entry per commit to `main` forever, and going multi-arch the naive way
(an arch-suffixed tag per platform, per push) would have tripled that.

## What Changes

- **`native.yml`'s `native` job becomes a matrix** over `ubuntu-latest`
  (`linux/amd64`) and `ubuntu-24.04-arm` (`linux/arm64`). Each leg builds and
  smoke-tests natively — arm64 gets real hardware, not emulation — on every
  trigger (push, schedule, dispatch, release), proving the image works even
  when nothing gets published.
- **Only `release.yml` ever publishes.** A push to `main`, the weekly
  scheduled rebuild and a bare `workflow_dispatch` build and smoke-test both
  architectures but push nothing to GHCR. `sha-<sha>` and `latest` are
  retired — neither was ever the supported pin-by contract (`latest` was
  already documented as "a convenience, not a deployment target"), and every
  released version keeps publishing under its own tag exactly as before, so
  nothing that was a supported interface stops working. The published tag
  namespace now holds released versions only.
- **Each leg pushes its own platform manifest addressed only by digest**
  (`push-by-digest=true`, buildx's documented mechanism for exactly this
  cross-job matrix shape), never under an arch-suffixed tag, and attests the
  SBOM against that digest. A new `publish` job downloads both legs' digests
  and combines them with `docker buildx imagetools create` into the multi-arch
  index published under the released version's tag alone. This is what keeps
  GHCR's tagged-versions listing to one entry per release: the platform
  manifests exist in the registry, but as the untagged children of that one
  tagged index — standard GHCR behavior for any properly-built multi-arch
  image, and the reason a hand-tag-then-delete approach was rejected (see
  `design.md`) in favor of never tagging them to begin with.
- **The `sha-<sha>`/`latest` tags this pipeline already published are cleaned
  up manually**, not by a checked-in workflow — deleting registry content is
  a one-time, deliberate act better done by hand than automated.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `release-packaging`: `GW_0072` — publication no longer happens from a
  main-branch push; only `release.yml` publishes, as a multi-arch image index
  (`linux/amd64` + `linux/arm64`) with each platform pushed by digest and
  attested individually, combined into the index under the released version's
  tag. `SVC_GW_0072` gains the matching assertions.

## Impact

**Workflows**

- `.github/workflows/native.yml` — the `native` job becomes a matrix that
  never publishes on its own; a new `publish` job downloads the two per-arch
  digests and creates the multi-arch index, only when `release.yml` drives it.

**Code**

- `src/test/java/dev/skillsgateway/server/PackagingTests.java` —
  `SVC_GW_0072`'s test asserts the matrix, the digest-only per-leg push, and
  the combine job.
- `docs/reqstool/requirements.yml`, `docs/reqstool/software_verification_cases.yml`.

**Documentation**

- `docs/manual/reference/container-image.md` — documents the multi-arch index,
  that only a release publishes, and that the platform manifests are
  intentionally untagged children of the release tag.

**Explicitly out of scope**

- `release.yml` — its `image` job calls `native.yml` via `workflow_call` and
  depends only on the caller's job status, so it needs no change.
- Index-level SBOM attestation — the per-platform attestations are the
  complete answer per the issue's own analysis; adding a third, index-level
  attestation would be redundant.
- A cleanup workflow for the retired `sha-*`/`latest` tags — decided against;
  the repository owner sweeps them manually instead.
