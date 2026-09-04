# Design — multi-arch-image

## Context

`native.yml` runs a single `runs-on: ubuntu-latest` job (`native`) that builds
the GraalVM native binary, wraps it in a container, smoke-tests it, and — only
when `github.event_name == 'push' || inputs.version != ''` — pushes it to GHCR
and attests its SBOM. `release.yml` calls this workflow via `workflow_call`
with `inputs.version` set and only reads the caller's overall job status; it
does not reference any job id or output internal to `native.yml`.

The binary is a compile target per architecture, not a runtime artifact
`buildx --platform` can multiply: `native-image` builds for the host it runs
on. GitHub's `ubuntu-24.04-arm` hosted runner gives a real arm64 host at the
same trigger surface (push, schedule, `workflow_dispatch`, `workflow_call`) the
amd64 build already uses.

## Goals / Non-Goals

**Goals:**
- Publish `ghcr.io/skillsgateway/skillsgateway` as a multi-arch image index
  covering `linux/amd64` and `linux/arm64`, under the same tag names as today.
- Attest the SBOM against each platform manifest, so `gh attestation verify`
  against a pulled digest still finds an attestation for the bytes that
  actually run.
- Keep the publish gate (main push or `release.yml`) and the schedule/dispatch
  behavior (build, never publish) exactly as strict as they are today.

**Non-Goals:**
- Cross-compilation or QEMU emulation for either architecture — ruled out in
  the proposal on time-cost grounds.
- A stable contract for the arch-suffixed tags. They are plumbing between the
  matrix and the combine step, not a second tag scheme.
- Attesting the index digest itself. The per-platform attestations are what a
  puller's digest resolves to; an index-level attestation would describe a
  reference, not bytes.
- Any change to `release.yml`. It calls `native.yml` by `uses:` and reads only
  `needs.image.result`; nothing there names a job internal to `native.yml`.

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
`native`; a matrix keeps that job identity and its permissions block intact,
so the assertions that don't concern the multi-arch behavior don't need to
change at all.

### Per-leg: build, smoke-test, push an arch-suffixed tag, attest that digest

Each leg keeps the existing steps (native compile, container build, smoke
test, Helm lint) unchanged — they were already architecture-correct, since
Docker on a native runner produces a native-arch image without a `--platform`
flag. Only the publish step changes: the tag it pushes gains a `-${{
matrix.suffix }}` suffix (`sha-<sha>-amd64`, `latest-amd64`, `<version>-amd64`,
and the `arm64` equivalents), and `Attest SBOM` runs per leg against that leg's
own `steps.push.outputs.digest` — unchanged in shape, just now evaluated twice,
once per matrix instance.

Alternative considered: attest only the combined index's digest. Rejected per
the proposal's reasoning — a puller resolving `linux/arm64` from the index
receives the arm64 manifest's bytes, and `gh attestation verify` verifies a
subject digest, so the arm64 digest is what needs an attestation for that
verification to succeed against what was actually pulled.

### A new `publish` job builds the index from the two arch tags

```yaml
publish:
  name: Publish multi-arch manifest
  needs: native
  if: ${{ !cancelled() && needs.native.result == 'success' && (github.event_name == 'push' || inputs.version != '') }}
  runs-on: ubuntu-latest
  permissions:
    contents: read
    packages: write
  steps:
    - uses: docker/login-action@...
      with: { registry: ghcr.io, username: ${{ github.actor }}, password: ${{ secrets.GITHUB_TOKEN }} }
    - run: |
        IMAGE=ghcr.io/skillsgateway/skillsgateway
        TAGS="${VERSION:-sha-${SHA} latest}"
        BASE="${TAGS%% *}"
        TAG_ARGS=()
        for tag in $TAGS; do TAG_ARGS+=(-t "$IMAGE:$tag"); done
        docker buildx imagetools create "${TAG_ARGS[@]}" \
          "$IMAGE:${BASE}-amd64" "$IMAGE:${BASE}-arm64"
```

The job's own `if` repeats the publish gate: it is a separate job from
`native`, so a matrix leg's per-step `if` does not protect it, and `needs.
native.result == 'success'` alone would still run the combine on a schedule or
bare dispatch (where every leg's `native` job "succeeds" — it just skips its
own publish steps).

Alternative considered: pass the per-leg digests through job outputs (`docker
buildx imagetools create` accepts digest-qualified references,
`$IMAGE@sha256:...`, which would let `publish` reference exact bytes rather
than a movable tag). Not needed here: `publish` runs immediately after `native`
in the same run, so the arch-suffixed tag still points at the digest `native`
just pushed — nothing else can have moved it in between. Digest references
would remove a link that is momentarily true anyway, at the cost of plumbing
two extra job outputs through a matrix job (matrix job outputs need an
aggregation step of their own). Left as a possible hardening if the
arch-suffixed tags ever become reused for something else.

### Arch-suffixed tags are not cleaned up

The proposal already settles this as a non-goal (deleting a GHCR package
version needs a separate API call, `docker`/`buildx` cannot do it) — recorded
here only to say why `publish` does not attempt it: it would add a GHCR API
call and its own failure mode to a job whose only other job is a registry
write that already succeeded, for a cleanup that `GW_0072` does not require.

## Risks / Trade-offs

- **Build time and GHCR Actions minutes roughly double** for every trigger,
  including the weekly schedule. Accepted: correctness (a real arm64 build) is
  worth twice the minutes, and the schedule is weekly, not per-push.
- **GitHub's arm64 hosted runners are a newer offering than amd64 runners** —
  if `ubuntu-24.04-arm` has less capacity or availability than `ubuntu-latest`,
  the matrix leg queues longer. No mitigation beyond GitHub's own runner
  reliability; both legs already retry the same way any CI job does.
- **`docker buildx imagetools create` needs buildx**, which is not installed by
  default the same way `docker` itself is on every runner image — the
  hosted `ubuntu-latest`/`ubuntu-24.04-arm` images bundle `docker-buildx-plugin`
  today, so `publish` uses it as-is. If a future runner image drops it, `publish`
  fails loudly (imagetools is a `docker` subcommand, not a silent no-op) rather
  than pushing a wrong image.

## Migration Plan

No data migration. The change is deploy-time (GitHub Actions) only:
- First run of `native.yml` on `main` after merge publishes the first
  multi-arch `sha-<sha>` and `latest` tags; nothing before that changes.
- Existing single-arch tags (older `sha-<sha>`, `latest`, and released
  versions) are untouched — they remain single-manifest images pointing at
  their original digests. Nothing retroactively becomes multi-arch.
- Rollback is reverting the workflow change; the next push publishes
  single-arch again under the same tag names.

## Open Questions

None — the issue's "Things that need thought" are each resolved above (SBOM
attestation: per-platform; schedule: builds both; release path: unaffected;
arch-suffixed tags: deliberately not a stable interface).
