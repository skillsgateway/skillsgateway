# Design — multi-arch-image

## Context

`native.yml` runs a single `runs-on: ubuntu-latest` job (`native`) that builds
the GraalVM native binary, wraps it in a container, smoke-tests it, and — only
when `github.event_name == 'push' || inputs.version != ''` — pushes a
`sha-<sha>`/`latest` tag (or the released version, from `release.yml`) to GHCR
and attests its SBOM. `release.yml` calls this workflow via `workflow_call`
with `inputs.version` set and only reads the caller's overall job status; it
does not reference any job id or output internal to `native.yml`.

The binary is a compile target per architecture, not a runtime artifact
`buildx --platform` can multiply: `native-image` builds for the host it runs
on. GitHub's `ubuntu-24.04-arm` hosted runner gives a real arm64 host at the
same trigger surface (push, schedule, `workflow_dispatch`, `workflow_call`) the
amd64 build already uses.

Reviewing the actual GHCR package page while implementing this surfaced that
the existing `sha-<sha>` scheme has no retention — every main-branch push adds
a tag, forever — and that publishing a naive arch-suffixed tag per platform per
push would have tripled that. Both problems have the same root cause (tagging
something other than a released version) and the same fix.

## Goals / Non-Goals

**Goals:**
- Publish `ghcr.io/skillsgateway/skillsgateway` as a multi-arch image index
  covering `linux/amd64` and `linux/arm64`.
- Publish only from `release.yml`; a main push, the schedule and a bare
  dispatch build and smoke-test both platforms but push nothing.
- Attest the SBOM against each platform manifest, so `gh attestation verify`
  against a pulled digest still finds an attestation for the bytes that
  actually run.
- Keep the registry's tagged-versions listing to one entry per release, not
  one per platform.

**Non-Goals:**
- Cross-compilation or QEMU emulation for either architecture — ruled out on
  time-cost grounds (a native-image build that already takes minutes would
  plausibly take 30–60+ under emulation).
- Any change to `release.yml`. It calls `native.yml` by `uses:` and reads only
  `needs.image.result`; nothing there names a job internal to `native.yml`.
- A checked-in workflow to remove the `sha-<sha>`/`latest` tags the retired
  scheme already published. Decided against — that cleanup is a one-time,
  deliberate registry deletion the repository owner does by hand.
- Retaining `sha-<sha>` or `latest` in any form. Both are dropped outright,
  not replaced with a bounded-retention version of the same idea.

## Decisions

### Matrix the existing job, don't fork it into two workflows

`native` becomes:

```yaml
strategy:
  matrix:
    include:
      - runner: ubuntu-latest
        platform: linux/amd64
        suffix: amd64
      - runner: ubuntu-24.04-arm
        platform: linux/arm64
        suffix: arm64
runs-on: ${{ matrix.runner }}
```

Alternative considered: two separate jobs (`native-amd64`, `native-arm64`).
Rejected — it duplicates every step twice in the file instead of once with a
matrix variable, and `SVC_GW_0072`'s test already asserts against a job named
`native`; a matrix keeps that job identity and its permissions block intact.

### Publish only from release.yml; drop sha-<sha> and latest entirely

The publish gate on every publishing step becomes `inputs.version != ''`,
dropping the `github.event_name == 'push' ||` clause entirely — a main push no
longer publishes anything. This is a deliberate behavior break, not a
byproduct: it removes the per-commit tag whose absence of retention was the
second problem this change fixes, and it removes `latest`'s meaning (there is
no longer a continuous main-tracking image for it to track).

Alternative considered: keep publishing `sha-<sha>` (drop only `latest`, per
the middle option weighed against the proposal). Rejected by explicit
decision: a per-commit tag with no retention is the exact defect being fixed,
and multi-arch would otherwise have doubled its volume (two platform legs per
push instead of one).

### Per-leg: push the platform manifest by digest, never by an arch-suffixed tag

```yaml
- name: Push image by digest
  if: inputs.version != ''
  id: push
  run: |
    IMAGE=ghcr.io/skillsgateway/skillsgateway
    docker buildx build --platform ${{ matrix.platform }} \
      --metadata-file metadata.json \
      --output type=image,name="$IMAGE",push=true,push-by-digest=true,name-canonical=true \
      .
    DIGEST=$(python3 -c '...containerimage.digest...')
    echo "digest=$DIGEST" >> "$GITHUB_OUTPUT"
```

`push-by-digest` is buildx's own documented mechanism for exactly this shape —
building each platform on a separate runner in a matrix, then combining the
results in a later job — and it is also how a single-node
`docker buildx build --platform amd64,arm64 --push` already behaves today:
platform children are pushed as manifests referenced only by their own digest,
never under a tag, and only the index gets a human-readable tag. GHCR's
package UI reflects that directly — the tagged-versions listing shows the one
index tag; the digest-only children appear (if at all) under "untagged",
exactly like any other multi-arch image.

Alternative considered, and rejected: push each leg under an arch-suffixed tag
(`<version>-amd64`/`-arm64`), combine via `imagetools create`, then delete the
two arch-suffixed tags afterward to tidy the listing. Rejected on two grounds:
1. **It doesn't reliably produce the same registry state.** GHCR ties a
   container "version" to a tag; deleting a version by ID is documented (and
   confirmed by community cleanup-tool authors) as risking the underlying
   manifest a still-tagged index depends on, unlike a manifest that was never
   tagged in the first place. The failure mode is a broken multi-arch image —
   silent until someone pulls the platform GHCR could no longer serve.
2. **It's strictly more moving parts** for the same end state:
   push‑tag → combine → delete‑tag, versus push‑by‑digest → combine. The
   digest-only path is also the one Docker's own official multi-platform
   GitHub Actions guidance documents for split-runner builds, so it is the
   well-trodden path, not a novel one.

Each leg's digest is written to `/tmp/digests/<suffix>.txt` and uploaded as its
own small artifact (`digest-amd64`, `digest-arm64`) — GitHub Actions matrix job
outputs are not reliably aggregable across legs, so an artifact per leg,
downloaded by the combine job, is the documented pattern for carrying a value
out of one matrix instance into a later job.

`Attest SBOM` is unchanged in shape from before this decision — it already
attested `steps.push.outputs.digest`, a digest rather than a tag, so nothing
about the attestation step's mechanics changes; only where that digest comes
from does.

### A new `publish` job downloads both digests and creates the index

```yaml
publish:
  name: Publish multi-arch manifest
  needs: native
  if: ${{ !cancelled() && needs.native.result == 'success' && inputs.version != '' }}
  runs-on: ubuntu-latest
  permissions:
    contents: read
    packages: write
  steps:
    - uses: docker/login-action@...
    - uses: actions/download-artifact@v7.0.0
      with: { pattern: digest-*, path: /tmp/digests, merge-multiple: true }
    - run: |
        IMAGE=ghcr.io/skillsgateway/skillsgateway
        docker buildx imagetools create -t "$IMAGE:$VERSION" \
          "$IMAGE@$(cat /tmp/digests/amd64.txt)" \
          "$IMAGE@$(cat /tmp/digests/arm64.txt)"
```

The job's own `if` repeats the publish gate: it is a separate job from
`native`, so a matrix leg's per-step `if` does not protect it, and
`needs.native.result == 'success'` alone would still run the combine on a
schedule or bare dispatch (where every leg's `native` job "succeeds" — it just
skips its own push and attest steps).

### Cleaning up the retired tags is manual, not a checked-in workflow

`sha-<sha>` and `latest` from the retired scheme are not deleted by anything
in this change. A `dataaxiom/ghcr-cleanup-action`-based workflow was drafted
and then dropped by explicit decision: a registry deletion is a one-time act
here (going forward, nothing publishes those tags again), and a checked-in,
dispatchable workflow is a standing capability to delete production registry
content that this repository doesn't otherwise need — the repository owner
runs the equivalent cleanup by hand instead (`gh api` against the Packages
REST API, or the GHCR web UI), scoped to the specific legacy tags found on
the package page at the time.

## Risks / Trade-offs

- **Build time and GHCR Actions minutes roughly double** for every trigger,
  including the weekly schedule, since both platforms build even when nothing
  publishes. Accepted: correctness (a real arm64 build, proven working before
  a release ever needs it) is worth twice the minutes.
- **GitHub's arm64 hosted runners are a newer offering than amd64 runners** —
  if `ubuntu-24.04-arm` has less capacity or availability, the matrix leg
  queues longer. No mitigation beyond GitHub's own runner reliability.
- **`docker buildx build --output type=image,push-by-digest=true` needs the
  `docker-container` buildx driver**, not the default `docker` driver — hence
  the added `docker/setup-buildx-action` step. If a future runner image
  changes what ships by default, the build fails loudly rather than silently
  falling back to a single-platform push.
- **Dropping `sha-<sha>` and `latest` changes behavior** for anyone currently
  pulling either. Not treated as a breaking change to the product's supported
  contract: neither tag was ever the documented pin-by interface (`latest` was
  already described as "a convenience, not a deployment target"), and every
  released version keeps publishing under its own tag exactly as before —
  nothing that was a supported way to consume the image stops working.

## Migration Plan

No data migration. The change is deploy-time (GitHub Actions) only:
- The next release after merge is the first multi-arch, digest-published
  image; nothing before that changes.
- Existing `sha-<sha>` and `latest` tags, and every released version's tag,
  are untouched by merging this change — they remain single-manifest images
  pointing at their original digests. Removing the first two is a manual
  follow-up for the repository owner, not something this change automates.
- Rollback is reverting the workflow changes; the next push behaves as it did
  before this change (single-arch, publishing `sha-<sha>`/`latest` again).

## Open Questions

None. The issue's "Things that need thought" are resolved (SBOM attestation:
per-platform; schedule: builds both, publishes neither; release path:
unaffected), and the scope grew during implementation to also resolve the
tag-retention problem the same registry-page review surfaced, per explicit
direction: no publish outside a release, drop `latest`, and clean up the
retired tags by hand rather than with a checked-in workflow.
