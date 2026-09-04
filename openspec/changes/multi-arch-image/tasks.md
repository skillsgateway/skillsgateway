# Tasks — multi-arch-image

## 1. Matrix the native build (GW_0072 / SVC_GW_0072)

- [x] 1.1 `native.yml`'s `native` job gains
      `strategy.matrix.include: [{runner: ubuntu-latest, platform: linux/amd64,
      suffix: amd64}, {runner: ubuntu-24.04-arm, platform: linux/arm64, suffix:
      arm64}]` and `runs-on: ${{ matrix.runner }}`; the job id and its
      `permissions` block stay unchanged.
- [x] 1.2 Publication moves behind `inputs.version != ''` alone — a main push,
      the schedule and a bare dispatch build and smoke-test both platforms but
      publish nothing. `sha-<sha>` and `latest` are dropped entirely, not
      replaced with a retained or per-arch variant.
- [x] 1.3 Each leg pushes its own platform manifest by digest
      (`docker buildx build --output type=image,push=true,push-by-digest=true,
      name-canonical=true`), never under an arch-suffixed tag, and uploads that
      digest as its own artifact for the combine job.
- [x] 1.4 `Attest SBOM` is unchanged in shape — still
      `subject-digest: ${{ steps.push.outputs.digest }}` — now sourced from the
      digest-only push rather than a tagged one.

## 2. Combine into a multi-arch index (GW_0072 / SVC_GW_0072)

- [x] 2.1 Add a `publish` job, `needs: native`, gated on
      `!cancelled() && needs.native.result == 'success' && inputs.version != ''`.
- [x] 2.2 It downloads both legs' digest artifacts, logs in to GHCR, and runs
      `docker buildx imagetools create -t "$IMAGE:$VERSION"` against the two
      digest-qualified references, writing the resulting index digest to the
      job summary.

## 3. A dispatch-only cleanup workflow for the retired tags (GW_0162 / SVC_GW_0162)

- [x] 3.1 New `.github/workflows/ghcr-cleanup.yml`: `workflow_dispatch` only,
      a `dry-run` input defaulting to `true`, wrapping
      `dataaxiom/ghcr-cleanup-action` (pinned by commit SHA) with
      `delete-tags: 'sha-*,latest'` and
      `exclude-tags: '[0-9]*.[0-9]*.[0-9]*'` so every release/release-candidate
      tag is excluded from deletion.
- [x] 3.2 Not dispatched by this change itself — the repository owner runs it
      (dry-run first) after merge.

## 4. Requirements and the packaging test

- [x] 4.1 `GW_0072` in `requirements.yml`: describe release-only publication of
      a multi-arch index (`linux/amd64` + `linux/arm64`) with each platform
      pushed by digest and attested individually; revision `0.4.0`.
- [x] 4.2 `SVC_GW_0072` in `software_verification_cases.yml`: matching
      description; revision `0.4.0`.
- [x] 4.3 New `GW_0162` / `SVC_GW_0162`: the dispatch-only cleanup workflow's
      contract.
- [x] 4.4 `PackagingTests.releaseWorkflowCarriesThePublishByDigestContract`:
      asserts the matrix, the release-only gate (`inputs.version != ''` alone,
      no `github.event_name == 'push'` clause), the digest-only per-leg push,
      and the combine job. Every existing assertion in this test that still
      applies stays; none is weakened. A new
      `ghcrCleanupWorkflowIsDispatchOnlyAndProtectsReleaseTags` test covers
      `SVC_GW_0162`.

## 5. Documentation

- [x] 5.1 `docs/manual/reference/container-image.md`: state the image is a
      multi-arch index over `linux/amd64` and `linux/arm64`, that only a
      release publishes (no `sha-<sha>`/`latest`), and that the platform
      manifests are intentionally untagged children of the release tag.

## 6. Gates

- [ ] 6.1 `./mvnw clean verify`
- [ ] 6.2 `(cd src/main/frontend && pnpm test:stories)`
- [ ] 6.3 `(cd src/main/frontend && pnpm e2e)`
- [ ] 6.4 `reqstool status local -p docs/reqstool`
- [ ] 6.5 `openspec validate --all --strict`
- [ ] 6.6 `mkdocs build --strict`
- [ ] 6.7 `evidence.md` with the pasted tails and the commit SHA.
