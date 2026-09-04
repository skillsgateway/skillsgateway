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

Only images that passed the workflow's smoke test are published, and only from
a push to `main` or from the [release workflow](../guides/releasing.md) — the
weekly scheduled rebuild and manually dispatched runs never move a tag.

The publish pipeline also pushes arch-suffixed tags (`<tag>-amd64`,
`<tag>-arm64`) as an internal step toward building the index. These are not a
supported interface: they carry no stability contract and may change or
disappear as the pipeline evolves. Pin by digest or by the unsuffixed tag, as
below — never by an arch-suffixed one.

## Tags

| Tag | Meaning |
| --- | --- |
| `sha-<commit>` | Every push to `main`; immutable per commit. |
| `latest` | Moving tag following `main`; a convenience, not a deployment target. |
| `<version>` (e.g. `1.2.0`) | Releases. Bare semantic versions, no `v` prefix. |

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
