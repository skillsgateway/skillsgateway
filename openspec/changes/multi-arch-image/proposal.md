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

## What Changes

- **`native.yml`'s `native` job becomes a matrix** over `ubuntu-latest`
  (`linux/amd64`) and `ubuntu-24.04-arm` (`linux/arm64`). Each leg builds,
  smoke-tests (natively — arm64 gets real hardware, not emulation) and, only
  when the existing publish gate is true, pushes its own arch-suffixed tag
  (`sha-<sha>-amd64`, `sha-<sha>-arm64`, or `<version>-amd64`/`<version>-arm64`
  from `release.yml`) and attests the SBOM against its own per-arch digest.
  Attesting each platform manifest individually is what makes the attestation
  cover what actually runs; attesting only a combined index would leave the
  per-arch images — the thing a puller actually receives — unattested.
- **A new `publish` job combines the two arch-suffixed tags into a multi-arch
  index** via `docker buildx imagetools create`, published under the same tag
  names the single-arch build used to publish directly: `sha-<sha>` and
  `latest` on a main push, `<version>` when `release.yml` drives it. The public
  tag contract (`GW_0072`) is unchanged — a puller still asks for `latest` or a
  version and gets one manifest reference back; only what is behind that
  reference changes, from one platform manifest to an index over two.
- **The arch-suffixed tags are a pipeline-internal detail, not a supported
  interface.** They exist because `docker push` needs a tag and the combine
  step needs something to reference; nothing documents them as pinnable, and
  nothing guarantees they survive a future change to how the index gets built.
  Consumers keep pinning by digest or by the unsuffixed tag, as `GW_0072`
  already requires.
- **The weekly scheduled rebuild and manual dispatch build both architectures**
  (the matrix runs on every trigger, same as the single leg does today); only
  the publish steps stay gated to a main push or `release.yml`, unchanged.

Not breaking: the published tag names, the digest-pinning contract, and
`release.yml`'s call into `native.yml` are all unchanged — `release.yml`'s
`image` job already only depends on the caller's overall job status, not on any
job name or output internal to `native.yml`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `release-packaging`: `GW_0072` — the publication contract now describes a
  multi-arch image index (`linux/amd64` + `linux/arm64`) built from two
  per-architecture legs and combined into the index published under the
  existing tag names, with the SBOM attested against each platform manifest
  rather than a single digest. `SVC_GW_0072` gains the matching assertions.

## Impact

**Workflows**

- `.github/workflows/native.yml` — the `native` job becomes a matrix; a new
  `publish` job creates and pushes the multi-arch index.

**Code**

- `src/test/java/dev/skillsgateway/server/PackagingTests.java` —
  `SVC_GW_0072`'s test asserts the matrix, the per-arch attestation, and the
  combine job.
- `docs/reqstool/requirements.yml`, `docs/reqstool/software_verification_cases.yml`.

**Documentation**

- `docs/manual/reference/container-image.md` — documents the multi-arch index
  and the arch-suffixed tags' non-stable status.

**Explicitly out of scope**

- `release.yml` — its `image` job calls `native.yml` via `workflow_call` and
  depends only on the caller's job status, so it needs no change.
- Index-level SBOM attestation — the per-platform attestations are the
  complete answer per the issue's own analysis; adding a third, index-level
  attestation would be redundant.
