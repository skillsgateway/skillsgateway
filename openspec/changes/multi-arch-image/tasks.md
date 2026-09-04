# Tasks — multi-arch-image

## 1. Matrix the native build (GW_0072 / SVC_GW_0072)

- [x] 1.1 `native.yml`'s `native` job gains
      `strategy.matrix.include: [{runner: ubuntu-latest, platform: linux/amd64,
      suffix: amd64}, {runner: ubuntu-24.04-arm, platform: linux/arm64, suffix:
      arm64}]` and `runs-on: ${{ matrix.runner }}`; the job id and its
      `permissions` block stay unchanged.
- [x] 1.2 The `Push image` step tags and pushes `$IMAGE:$tag-${{
      matrix.suffix }}` for each tag in the existing `TAGS` computation
      (`sha-${SHA}`/`latest` on a main push, `$VERSION` from `release.yml`),
      and its digest capture/step-summary reference the suffixed tag.
- [x] 1.3 `Attest SBOM` is unchanged in shape — still
      `subject-digest: ${{ steps.push.outputs.digest }}` — now evaluated once
      per matrix leg against that leg's own digest.

## 2. Combine into a multi-arch index (GW_0072 / SVC_GW_0072)

- [x] 2.1 Add a `publish` job, `needs: native`, gated on
      `!cancelled() && needs.native.result == 'success' && (github.event_name
      == 'push' || inputs.version != '')` — the same publish condition as
      today, re-stated because it is a separate job.
- [x] 2.2 It logs in to GHCR and runs `docker buildx imagetools create` with
      one `-t` per unsuffixed tag name, sourced from the two arch-suffixed
      tags `native` just pushed, and writes the resulting index digest to the
      job summary (same shape as the old single-image summary).

## 3. Requirements and the packaging test

- [x] 3.1 `GW_0072` in `requirements.yml`: describe the published image as a
      multi-arch index (`linux/amd64` + `linux/arm64`) with the SBOM attested
      per platform manifest; revision `0.3.0`.
- [x] 3.2 `SVC_GW_0072` in `software_verification_cases.yml`: matching
      description; revision `0.3.0`.
- [x] 3.3 `PackagingTests.releaseWorkflowCarriesThePublishByDigestContract`
      (or a split-out test kept under `@SVCs({"SVC_GW_0072"})`): assert the
      `native` job's `strategy.matrix` covers both runners/platforms, that the
      per-leg push/attest steps reference the suffixed tag and per-leg
      digest, and that a `publish` job exists, needs `native`, carries the
      same publish-gate condition, and calls `imagetools create`. Every
      existing assertion in this test stays — none is weakened or deleted.

## 4. Documentation

- [x] 4.1 `docs/manual/reference/container-image.md`: state the image is a
      multi-arch index over `linux/amd64` and `linux/arm64`, that Docker
      resolves the right manifest automatically, and that the arch-suffixed
      tags are an internal pipeline detail, not a pinnable interface —
      pin by digest or by the unsuffixed tag, as today.

## 5. Gates

- [ ] 5.1 `./mvnw clean verify`
- [ ] 5.2 `(cd src/main/frontend && pnpm test:stories)`
- [ ] 5.3 `(cd src/main/frontend && pnpm e2e)`
- [ ] 5.4 `reqstool status local -p docs/reqstool`
- [ ] 5.5 `openspec validate --all --strict`
- [ ] 5.6 `mkdocs build --strict`
- [ ] 5.7 `evidence.md` with the pasted tails and the commit SHA.
