# Deploying without Kubernetes

The Helm chart is a convenience, not a requirement. The image is an ordinary
Spring Boot application, so every setting it reads can be supplied as an
environment variable, and anything awkward to express that way can be supplied
as a configuration file the application is told to read. That is enough to run
it on plain Docker, on a container platform such as ECS or Cloud Run, on Nomad,
or from a systemd unit.

This guide is the environment-only contract: what must be set, how property
names map to variable names, how to supply the settings that are nested lists,
and what the image's shape forces you to do differently.

For Kubernetes, use [Deploying on Kubernetes](deploying-on-kubernetes.md)
instead — the chart already does everything below.

## Prerequisites

| What | Why |
| --- | --- |
| A PostgreSQL database | Snapshots, the audit ledger, tokens and grants live there. Nothing here creates one. |
| An OIDC client | The whole web surface authenticates with OIDC. Client id, client secret and the three endpoint URIs — see [Identity providers](identity-providers.md). |
| Persistent storage | On the default `filesystem` backend, the repositories live on disk under the data directory and must outlive the container. See [Storage](#storage). |
| Writable scratch at `/tmp` | Only if you run with a read-only root filesystem — Tomcat's work directory lives there and the process will not start without it. See [A writable `/tmp`](#a-writable-tmp-if-you-seal-the-root-filesystem). |
| A TLS-terminating proxy | The application speaks plain HTTP on 8080. See [Running behind a proxy](#running-behind-a-proxy) — this needs one setting, and logins fail without it. |

!!! danger "Name an administrator, or the process will not start"

    Authorization is always enforced, and a gateway whose configuration grants
    the admin role to nobody refuses to start rather than run as an estate
    nobody can administer. Name one in `skills-gateway.roles.admins`, in a
    `skills-gateway.roles.mappings` entry resolving `admin`, or in a declared
    `skills-gateway.estate.grants` entry. See
    [Delegated administration](delegated-administration.md).

## The minimum environment

```bash
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres.example.com:5432/skillsgateway?sslmode=require
SPRING_DATASOURCE_USERNAME=skillsgateway
SPRING_DATASOURCE_PASSWORD=…                     # from your secret store

# OIDC — see Identity providers for where these come from
SGW_OIDC_CLIENT_ID=…
SGW_OIDC_CLIENT_SECRET=…                         # from your secret store
SGW_OIDC_AUTHORIZATION_URI=https://idp.example.com/oauth2/v2.0/authorize
SGW_OIDC_TOKEN_URI=https://idp.example.com/oauth2/v2.0/token
SGW_OIDC_JWK_SET_URI=https://idp.example.com/discovery/v2.0/keys
SGW_OIDC_USER_NAME_ATTRIBUTE=preferred_username
SGW_OIDC_SCOPE=openid,profile,email
SKILLSGATEWAY_OIDC_ISSUER=https://idp.example.com/v2.0

# Behind a TLS-terminating proxy
SERVER_FORWARDHEADERSSTRATEGY=framework

# At least one administrator
SKILLSGATEWAY_ROLES_ADMINS_0=platform-admin@example.com
```

`SKILLSGATEWAY_DATADIR` is already `/data` in the image; override it only if you
mount somewhere else.

!!! note "Set the OIDC endpoints, not only an issuer"

    The `application.yaml` baked into the image carries placeholder provider
    endpoints (`https://idp.invalid/…`). Spring applies explicitly configured
    provider URIs *on top of* anything discovered from an issuer, so setting
    only `spring.security.oauth2.client.provider.idp.issuer-uri` leaves the
    placeholders in force and no login can complete. Set the three `SGW_OIDC_*`
    endpoint variables above. Doing so also removes a discovery call from the
    startup path, which is one less thing to be reachable at boot.

## Running behind a proxy

The application serves plain HTTP on port 8080 and terminates no TLS. Whatever
sits in front of it — an ingress, a load balancer, a reverse proxy — must
therefore tell it what the outside world sees, and the application must be told
to believe it:

```bash
SERVER_FORWARDHEADERSSTRATEGY=framework
```

Without this, Spring builds its external URLs from the container's own view of
the request. The OIDC redirect URI becomes `http://<container>:8080/login/oauth2/code/idp`
rather than the `https://<your-host>/login/oauth2/code/idp` you registered with
the provider, and every login fails on a redirect-URI mismatch. Set it wherever
TLS terminates somewhere other than the application itself, which in practice
is everywhere.

Make sure the proxy actually sends `X-Forwarded-Proto` and `X-Forwarded-Host`,
and that it is the only thing that can — these headers are trusted once this
setting is on.

## Property names as environment variables

Spring's relaxed binding maps a property to a variable by upper-casing it,
replacing `.` with `_`, and **removing** hyphens:

| Property | Environment variable |
| --- | --- |
| `skills-gateway.data-dir` | `SKILLSGATEWAY_DATADIR` |
| `skills-gateway.roles.claim` | `SKILLSGATEWAY_ROLES_CLAIM` |
| `skills-gateway.storage.backend` | `SKILLSGATEWAY_STORAGE_BACKEND` |
| `skills-gateway.retention.enabled` | `SKILLSGATEWAY_RETENTION_ENABLED` |
| `server.forward-headers-strategy` | `SERVER_FORWARDHEADERSSTRATEGY` |

Hyphens are removed, not turned into underscores: `data-dir` becomes `DATADIR`,
never `DATA_DIR`. Every setting in [Configuration](../reference/configuration.md)
is reachable this way.

### Lists

A list of scalars is indexed:

```bash
SKILLSGATEWAY_ROLES_ADMINS_0=first-admin@example.com
SKILLSGATEWAY_ROLES_ADMINS_1=second-admin@example.com
```

A list of objects indexes each field:

```bash
SKILLSGATEWAY_ROLES_MAPPINGS_0_CLAIMVALUE=SkillsGateway.Admin
SKILLSGATEWAY_ROLES_MAPPINGS_0_ROLE=admin
SKILLSGATEWAY_ESTATE_MARKETPLACES_0_NAME=corp-marketplace
SKILLSGATEWAY_ESTATE_MARKETPLACES_0_URL=https://git.example.com/skills/corp.git
```

This works, and for one or two entries it is the least machinery. It stops
being reasonable somewhere around the [declarative estate](declarative-estate.md),
which is nested lists of objects and reads as line noise in this form. For that,
supply a file.

## Supplying a configuration file

The chart mounts a ConfigMap and points `SPRING_CONFIG_ADDITIONAL_LOCATION` at
it. Outside Kubernetes the destination is the same; only the delivery differs.

```bash
SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/config/
```

The trailing slash matters: it names a directory, and Spring reads
`application.yaml` from it. The file is layered *over* the `application.yaml`
baked into the image, so it carries only what you are overriding.

!!! warning "The image has no shell"

    It is distroless: no shell, no `sh -c`, no `curl`, no package manager.
    Nothing inside the application container can write the file, template it,
    or fetch it before start. Whatever produces the file has to run somewhere
    else — the host, a volume populated ahead of time, or a separate container.

Three ways to get the file there, in rough order of how much machinery they add:

=== "Mount it from the host or a volume"

    Simplest where you control the filesystem: write `application.yaml` next to
    your deployment definition and mount its directory read-only. This is the
    plain-Docker and systemd answer.

=== "Write it from a second container"

    On a container platform with no host filesystem to mount, run a small
    utility image as an init container that writes the file into a volume
    shared with the application container, and order the application to start
    after it completes. This is the ConfigMap pattern rebuilt from parts: the
    content can come from the deployment definition itself, or the init
    container can fetch it from a secret store or an object store.

=== "Skip the file: `SPRING_APPLICATION_JSON`"

    A single environment variable carrying the whole nested structure as JSON:

    ```bash
    SPRING_APPLICATION_JSON='{"skills-gateway":{"roles":{"claim":"roles","mappings":[{"claim-value":"SkillsGateway.Admin","role":"admin"}]}}}'
    ```

    No file and no second container, at the cost of a configuration that no
    longer diffs readably. Reasonable for a handful of nested settings, poor for
    a large estate.

Whichever you choose, **the file is not a secret store**. A client secret, a
webhook secret or an audit-sink credential belongs in an environment variable
sourced from your platform's secret manager, referenced from the declaration as
described in [Declarative estate configuration](declarative-estate.md).

## Storage

On the default `filesystem` backend the quarantine, the published content the
facade serves, and any hosted marketplaces all live under the data directory —
`/data` in the image. PostgreSQL records which snapshots exist and which are
approved, so the volume and the database are one estate: a gateway that restarts
onto an empty `/data` still reports its snapshots as published and can serve
none of them. Give it storage that outlives the container.

It also means **exactly one instance**. The filesystem backend has no
cross-process locking, and the background schedulers are not cluster-aware. Two
processes on one volume is not a supported configuration, and a rolling deploy
that overlaps old and new is two processes. Configure your platform for
stop-then-start rather than an overlapping replacement.

Running more than one instance means the `object-store` backend — see
[Choosing and migrating the storage backend](storage-backends.md), which also
covers the background singletons that must be switched off first.

### A writable `/tmp`, if you seal the root filesystem

Sealing the container's root filesystem is worth doing, and this image is built
for it — but the application still needs **one writable scratch directory**.
Tomcat creates its work directory under `java.io.tmpdir` while the web server
starts, so with nowhere to write it the process exits before serving anything:

```
Unable to create tempDir. java.io.tmpdir is set to /tmp
Caused by: java.nio.file.FileSystemException:
  /tmp/tomcat.8080.13526778125704680643: Read-only file system
```

Mount ephemeral scratch at `/tmp`. Nothing durable belongs there — it is
discarded with the container, and losing it costs nothing.

!!! warning "The mount must be writable by uid 65532, not just present"

    The image runs as the distroless `nonroot` user. Several container
    platforms mount an empty volume **root-owned**, which leaves `/tmp`
    present and unwritable — and that fails with exactly the error above, so
    a half-configured mount is indistinguishable from no mount at all.

    Kubernetes handles this through `fsGroup` in the pod security context (the
    chart sets it). Elsewhere, `chown` the directory before the application
    starts — on a platform with init containers, that is what they are for.

The `object-store` backend needs the same scratch, and one thing more: its
local pack cache defaults to `{data-dir}/object-store-cache`. Where you run
with no durable volume at all, point `skills-gateway.storage.object-store.cache.dir`
at a path under your ephemeral mount — nothing in that cache is authoritative,
and deleting it at any moment is safe.

## Health checks

`/actuator/health` on port 8080 is the readiness and liveness signal, and it is
the only unauthenticated path. Probe the bare path, not the `/readiness` and
`/liveness` subpaths.

Because the image has no shell and no HTTP client, a *container-level* health
check — the kind that runs a command inside the container — cannot be written.
The probe has to come from outside: your load balancer's target health check,
your platform's HTTP probe, or an external monitor.

## A worked example: plain Docker

```bash
docker volume create skills-gateway-data

docker run -d --name skills-gateway \
  -p 8080:8080 \
  -v skills-gateway-data:/data \
  -v "$PWD/config:/config:ro" \
  --read-only \
  --mount type=tmpfs,destination=/tmp,tmpfs-mode=1777 \
  -e SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/config/ \
  -e SPRING_DATASOURCE_URL='jdbc:postgresql://postgres.example.com:5432/skillsgateway?sslmode=require' \
  -e SPRING_DATASOURCE_USERNAME=skillsgateway \
  -e SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
  -e SGW_OIDC_CLIENT_ID="$OIDC_CLIENT_ID" \
  -e SGW_OIDC_CLIENT_SECRET="$OIDC_CLIENT_SECRET" \
  -e SGW_OIDC_AUTHORIZATION_URI=https://idp.example.com/oauth2/v2.0/authorize \
  -e SGW_OIDC_TOKEN_URI=https://idp.example.com/oauth2/v2.0/token \
  -e SGW_OIDC_JWK_SET_URI=https://idp.example.com/discovery/v2.0/keys \
  -e SGW_OIDC_USER_NAME_ATTRIBUTE=preferred_username \
  -e SGW_OIDC_SCOPE=openid,profile,email \
  -e SKILLSGATEWAY_OIDC_ISSUER=https://idp.example.com/v2.0 \
  -e SERVER_FORWARDHEADERSSTRATEGY=framework \
  ghcr.io/skillsgateway/skillsgateway:<released-version>
```

With `config/application.yaml`:

```yaml
skills-gateway:
  roles:
    admins:
      - platform-admin@example.com
    claim: roles
    mappings:
      - claim-value: SkillsGateway.Admin
        role: admin
  estate:
    marketplaces:
      - name: corp-marketplace
        url: https://git.example.com/skills/corp.git
```

!!! tip "`--mount`, not `--tmpfs`, if you might be on Podman"

    Docker accepts `--tmpfs /tmp:uid=65532,gid=65532`; **Podman 6.1 rejects
    it** with `unknown mount option "uid=65532"`. The `--mount
    type=tmpfs,…,tmpfs-mode=1777` form above works on both, and the sticky
    `1777` mode makes it writable by the image's uid without naming it —
    which is also one less thing to update if that uid ever changes.

`--read-only` with a tmpfs at `/tmp` is the shape worth copying: the root
filesystem is sealed, `/data` holds the estate, `/config` is mounted read-only,
and scratch is a world-writable tmpfs the nonroot uid can use. Drop the
`--read-only` and the tmpfs becomes unnecessary — but so does most of the point
of a distroless image.

Pin a released version or a digest rather than a moving tag — see
[Container image](../reference/container-image.md).

## Egress

Two outbound destinations matter where egress is filtered:

| Destination | Used for | Required |
| --- | --- | --- |
| Each marketplace's git host, over HTTPS | Ingesting and syncing upstream content, as git smart HTTP | **Yes** — ingestion is the product |
| The forge's REST API host | Best-effort project name, description and last-update at registration | No — any failure resolves to empty and registration continues |
| Your identity provider | The OIDC token and JWKS endpoints | **Yes** |

Ingestion is a plain clone and fetch, so a rule for the **git host** is what it
needs — `github.com`, not `api.github.com`, and no archive host: nothing fetches
tarballs. Registering a marketplace performs no clone, so registration succeeds
with no git egress at all and only ingestion fails, which makes the two easy to
commission separately.

If your manifests declare [external plugin sources](../reference/compatibility.md),
those clone URLs are additional destinations. They are refused by default;
leaving `skills-gateway.ingestion.allow-external-sources` unset is what keeps
the outbound set bounded to the marketplaces you registered.
