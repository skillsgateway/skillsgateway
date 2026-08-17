# Design: publish-container-image

## Context

`.github/workflows/native.yml` builds the GraalVM native binary, builds the
distroless image (`docker build -t skills-gateway:ci .`), smoke-tests it against
a real Postgres, lints the Helm chart, and uploads the image as a tarball
artifact plus the CycloneDX SBOM. Nothing is pushed to a registry. The
internal-platform consumes images by immutable digest mirrored into ECR, so
a pullable, digest-addressable image is the missing handoff (issue #67, their
Q53). The repo is private, so the GHCR package will start private.

## Goals / Non-Goals

**Goals**

- Push the *smoke-tested* image to `ghcr.io/skillsgateway/skillsgateway` on `main`
  pushes (`sha-<commit>`, `latest`) and on `v*` tag pushes (version tag).
- Surface the pushed `sha256:…` digest in the job summary.
- Attach the native-build CycloneDX SBOM to the image as an attestation.

**Non-Goals**

- No multi-arch build (stays linux/amd64, what the runner produces).
- No change to Dockerfile, Helm chart, application code, or the ci.yml gates.
- Not making the package public — that is a one-time manual GHCR setting the
  owner flips when ready; docs describe both modes.

## Decisions

1. **Push the already-built image; don't rebuild with buildx.** The image wraps
   a native binary compiled earlier in the job — rebuilding via a push-capable
   builder would either recompile or complicate the Dockerfile. `docker tag` +
   `docker push` of the exact bytes that passed the smoke test is simpler and
   preserves "what was tested is what ships".
2. **Trigger `v*` tags via the existing `push` event** (`tags: ['v*']` added to
   the trigger). Publish steps run only when `github.event_name == 'push'` —
   scheduled and manual runs keep building/smoking but never publish, so the
   weekly cron can't move `latest`.
3. **Login with the workflow's `GITHUB_TOKEN`** (`docker/login-action`,
   `packages: write` job permission). No PAT secrets to manage; the package
   lands under the repo owner automatically.
4. **SBOM attestation via `actions/attest-sbom`** (needs `id-token: write` +
   `attestations: write`), subject = the pushed digest, predicate =
   `target/classes/META-INF/sbom/application.cdx.json`. GitHub-native beats
   introducing cosign key management; verifiable with `gh attestation verify`.
5. **Digest from `docker inspect --format='{{index .RepoDigests 0}}'` after the
   push**, written to `$GITHUB_STEP_SUMMARY` and exposed as a step output —
   consumers copy the pin without pulling.
6. **Verification (SVC_GW_0072) extends the packaging consistency test** to the
   workflow file: assert native.yml contains the GHCR image name, tags for both
   channels, a publish condition restricted to push events, and the required
   permissions. The push itself is only exercisable in CI; the test pins the
   contract that makes it happen.

## Risks / Trade-offs

- [Private package: mirror pulls fail without auth] → documented: pulls need a
  token with `read:packages`; platform mirror already authenticates to GitHub.
- [`latest` on a private package tempts unpinned use] → docs state digest
  pinning as the supported mode; `latest` is a convenience tag only.
- [First push creates the package with default (private) visibility and repo
  linkage] → expected; owner flips visibility later if desired.
- [Attestation action requires GitHub-hosted runner OIDC] → true here
  (ubuntu-latest); if it ever fails it must not block the push — attestation
  step ordered after push, failure surfaces but image remains available.

## Migration Plan

Pure CI addition; first publish happens on merge to main. Rollback = revert the
workflow; already-pushed digests are immutable and harmless.

## Open Questions

None blocking. Package visibility flip is an owner decision outside this change.
