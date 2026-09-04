# Container image

The gateway is published as a container image running the GraalVM native
binary:

```
ghcr.io/skillsgateway/skillsgateway
```

It is published as a **multi-arch image index** covering `linux/amd64` and
`linux/arm64` — a plain `docker pull`/`docker run` resolves the manifest for
the caller's platform automatically, with no `--platform` flag needed on
either architecture. Each platform's build is smoke-tested on its own native
runner before either is published, since GraalVM native-image cannot
cross-compile.

**Only a release publishes.** A push to `main`, the weekly scheduled rebuild
and a manually dispatched run all build and smoke-test both architectures —
proving the image works — but publish nothing: only the
[release workflow](../guides/releasing.md) ever reaches GHCR. The published
tag namespace therefore holds released versions only; there is no per-commit
or moving tag to track `main`.

Each platform's manifest is pushed to the registry addressed only by its own
digest, never under a separate `-amd64`/`-arm64` tag — the two are combined
into the index that is tagged with the released version. This is why the
package's tagged-versions listing shows one entry per release rather than
three: the platform manifests exist in the registry, but as the untagged
children of that one tagged index, exactly as GitHub renders any properly
multi-arch image.

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

Each published **platform manifest** — not just the index — carries the
build's CycloneDX SBOM as a registry attestation (the same SBOM the running
gateway serves at `/actuator/sbom`), so verification succeeds against the
digest a puller's platform actually resolves to. Verify what a pinned digest
contains with:

```console
$ gh attestation verify oci://ghcr.io/skillsgateway/skillsgateway@sha256:… \
    --owner skillsgateway
```
