# Container image

The gateway is published as a container image running the application jar on a
`jlink`-produced Java 25 runtime:

```
ghcr.io/skillsgateway/skillsgateway
```

The base is `gcr.io/distroless/java-base-debian12:nonroot`: the OS libraries a
JVM needs and nothing else — no shell, no package manager — with the runtime
itself supplied by `jlink` rather than by a JDK in the base. The container runs
as uid 65532 and needs nothing on its root filesystem writable, which is why the
Helm chart mounts it read-only. [ADR 0012](decisions.md) records why this
replaced the GraalVM native binary the image previously carried.

It is published as a **multi-arch image index** covering `linux/amd64` and
`linux/arm64` — a plain `docker pull`/`docker run` resolves the manifest for
the caller's platform automatically, with no `--platform` flag needed on
either architecture. Both platforms come from one build: the jar is the same
bytes on either, and only the runtime beneath it is platform-specific.

The image is smoke-tested — booted against a real PostgreSQL, on a read-only
root filesystem, and checked for health and for the proxy-header behaviour —
before anything is published. That test runs on the runner's own architecture,
`linux/amd64`; the `linux/arm64` manifest is built from the same Dockerfile and
the same jar in the same build but is not booted in CI.

**Only a release publishes.** A push to `main`, the weekly scheduled rebuild
and a manually dispatched run all build and smoke-test both architectures —
proving the image works — but publish nothing: only the
[release workflow](../guides/releasing.md) ever reaches GHCR. The published
tag namespace therefore holds released versions only; there is no per-commit
or moving tag to track `main`.

The released version is the only tag the publish creates. The platform manifests
exist in the registry as the untagged children of that one tagged index, never
under a separate `-amd64`/`-arm64` tag. This is why the package's tagged-versions
listing shows one entry per release rather than three, exactly as GitHub renders
any properly multi-arch image.

## Size, startup and memory

Measured on the image this repository builds, so that a deployment can be sized
from numbers rather than from expectations:

| | |
| --- | ---: |
| Image, uncompressed | ~195 MB |
| Image, compressed (what a pull transfers) | ~130 MB |
| Application startup | ~3.6 s |
| Resident memory, idle, under a 1 GiB limit | ~255 MB |

The image is roughly the distroless base (46 MB), the `jlink` runtime (79 MB)
and the application jar (69 MB). The jar carries the built portal, so it does not
shrink much.

The entrypoint sets `-XX:MaxRAMPercentage=75.0` on purpose. The JVM's default
ceiling is a quarter of the container's limit; the heap that matters here is the
one JGit packs a large upstream repository in, and a quarter of the limit is less
than the previous artifact had.

## Tags

| Tag | Meaning |
| --- | --- |
| `<version>` (e.g. `1.2.0`) | A release. Bare semantic version, no `v` prefix. |

## Pin by digest

Digest pinning is the supported consumption mode. Every publish prints the
image digest in the workflow's job summary; deploy that, not a tag:

```
ghcr.io/skillsgateway/skillsgateway@sha256:…
```

A digest names exactly one image forever — mirrors and clusters that pin it
cannot be moved by anything that happens to the registry afterwards.

## Pulling while the package is private

While the GitHub repository is private, the GHCR package is too. Pulls then
need a token with the `read:packages` scope:

```console
$ echo "$GITHUB_TOKEN" | docker login ghcr.io -u <user> --password-stdin
$ docker pull ghcr.io/skillsgateway/skillsgateway@sha256:…
```

Package visibility can later be made public in the GHCR package settings,
independently of the repository's visibility.

## SBOM attestation

The published image index carries the build's CycloneDX SBOM as a registry
attestation (the same SBOM the running gateway serves at `/actuator/sbom`),
against the digest the publish prints and that a deployment pins. Verify what a
pinned digest contains with:

```console
$ gh attestation verify oci://ghcr.io/skillsgateway/skillsgateway@sha256:… \
    --owner skillsgateway
```
