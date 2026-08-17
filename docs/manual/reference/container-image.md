# Container image

The gateway is published as a container image running the GraalVM native
binary:

```
ghcr.io/skillsgateway/skillsgateway
```

Only images that passed the release workflow's smoke test are published, and
only from push events — the weekly scheduled rebuild and manually dispatched
runs never move a tag.

## Tags

| Tag | Meaning |
| --- | --- |
| `sha-<commit>` | Every push to `main`; immutable per commit. |
| `latest` | Moving tag following `main`; a convenience, not a deployment target. |
| `<version>` (e.g. `v1.2.0`) | Release tags. |

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

Each published image carries the build's CycloneDX SBOM as a registry
attestation (the same SBOM the running gateway serves at `/actuator/sbom`).
Verify what a pinned digest contains with:

```console
$ gh attestation verify oci://ghcr.io/skillsgateway/skillsgateway@sha256:… \
    --owner skillsgateway
```
