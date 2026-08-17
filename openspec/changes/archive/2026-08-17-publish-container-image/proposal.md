# Publish the container image (GHCR, by digest)

## Why

The native workflow builds the distroless container image and smoke-tests it, but
never publishes it — downstream platforms that consume images by digest and mirror
them into their own registry (e.g. resurs-internal/ai-enablement-platform, whose
invariant is ECR-mirrored images pinned by `sha256:…` digest) have nothing to pull
(issue #67). Publication is the missing last step of GW_0015's container
distribution story.

## What Changes

- Extend `.github/workflows/native.yml` to push the smoke-tested image to
  `ghcr.io/skillsgateway/skillsgateway`:
  - on pushes to `main`: tags `sha-<commit>` and `latest`
  - on `v*` tags: the release version tag
- Surface the pushed image digest (`sha256:…`) in the GitHub Actions job summary
  so digest-pinning consumers can copy it without pulling.
- Attach the CycloneDX SBOM produced by the native build to the published image
  as a registry attestation, so image and inventory travel together.
- Only images that passed the in-workflow smoke test are pushed; PR builds and
  scheduled runs never publish.
- The repository is currently private, so the GHCR package created by the first
  push is private too: consumers (including the ai-enablement-platform mirror)
  pull with a token holding `read:packages`. Package visibility can later be
  made public in GHCR package settings independently of the repo; the docs state
  both modes.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `release-packaging`: new requirement (GW_0072) — the built container image is
  published to a public registry addressable by immutable digest, with tags for
  main commits and release versions, the digest surfaced in build output, and the
  SBOM attached as an attestation. Verified by SVC_GW_0072.

## Impact

- `.github/workflows/native.yml`: registry login, push, digest summary, SBOM
  attestation; job gains `packages: write` (and `id-token`/`attestations` if
  using GitHub's attestation action) permissions on the publish path.
- `docs/reqstool/requirements.yml` / `software_verification_cases.yml`: GW_0072,
  SVC_GW_0072 (verification: automated-test — workflow-level assertions where
  testable; otherwise the packaging consistency test extends to the workflow file).
- `docs/manual/`: installation/reference docs gain the image coordinates and
  how to pin by digest.
- No application code, schema, or API changes.
