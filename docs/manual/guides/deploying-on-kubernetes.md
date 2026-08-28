# Deploying on Kubernetes

The chart in `helm/skills-gateway/` installs the gateway as a single Deployment,
a Service, and — optionally — an Ingress, a ServiceAccount and a ConfigMap of
application configuration. This guide covers what the chart needs from you
before it will install, what it deliberately refuses to guess, and a worked
example on serverless Kubernetes.

## Prerequisites

| What | Why | Notes |
| --- | --- | --- |
| A PostgreSQL database | Snapshots, the audit ledger, tokens and grants live there | The chart does **not** bring one. Create the database and a Secret with key `password`. |
| An OIDC client | The whole web surface authenticates with OIDC | Client id, client secret, and the three endpoint URIs. See [Identity providers](identity-providers.md). |
| Access to the image | `ghcr.io/skillsgateway/skillsgateway`, or your own mirror | For a private mirror, create a `kubernetes.io/dockerconfigjson` Secret and name it in `imagePullSecrets`. |
| A storage decision | The chart will not install without one | See [Storage](#storage) — this is the one value that has no default. |

## Installing

```bash
helm install skills-gateway ./helm/skills-gateway -f my-values.yaml
```

A minimal `my-values.yaml`:

```yaml
image:
  repository: ghcr.io/skillsgateway/skillsgateway
  tag: "1.0.0"

postgresql:
  host: postgres.example.com
  existingSecret: skills-gateway-db      # key: password

oidc:
  clientId: skills-gateway
  existingSecret: skills-gateway-oidc    # key: client-secret
  authorizationUri: https://idp.example.com/oauth2/v2.0/authorize
  tokenUri: https://idp.example.com/oauth2/v2.0/token
  jwkSetUri: https://idp.example.com/discovery/v2.0/keys
  issuer: https://idp.example.com/v2.0

persistence:
  mode: existingClaim
  existingClaim: skills-gateway-data
```

## Configuring the application

The chart's own keys cover the database, the identity provider and storage.
**Everything else** the gateway reads — see
[Configuration](../reference/configuration.md) — is set through two
passthroughs, so no chart change is needed to reach a setting.

!!! danger "Role enforcement is off by default"

    `skills-gateway.roles.enabled` defaults to `false`, and while it is false
    every authorization check passes for every authenticated principal. A
    gateway installed and left alone therefore grants full administrative
    access to anyone who can complete an OIDC login. **Turn it on, and name at
    least one config admin, before the first person logs in.**

### Structured configuration: `config`

Anything under `config` is rendered verbatim into a ConfigMap, mounted at
`/etc/skills-gateway/application.yaml`, and layered over the `application.yaml`
built into the image through `SPRING_CONFIG_ADDITIONAL_LOCATION`. The image
keeps the defaults; this file overrides them.

```yaml
config:
  skills-gateway:
    roles:
      enabled: true
      admins:
        - platform-admin@example.com
    estate:
      marketplaces:
        - name: internal-skills
          url: https://git.example.com/skills/internal.git
      grants:
        - principal: skills-reviewers
          role: approver
          marketplace: internal-skills
```

This is the place for the [declarative estate](declarative-estate.md), which is
nested lists and expresses poorly as environment variables. It matters more
than it looks: **the admin API requires an interactive OIDC session**, so estate
YAML is currently the only way to configure a gateway from automation at all
([#128](https://github.com/skillsgateway/skillsgateway/issues/128) tracks
changing that).

The estate is reconciled at startup, before the web surface serves its first
request, so the declaration is in force from the first login. The chart hashes
`config` into a pod annotation, which means editing it and upgrading rolls the
pods — without a restart a changed ConfigMap would be refreshed on disk and
never read.

!!! warning "A ConfigMap is not a Secret"

    Never put a webhook secret, an audit-sink credential or anything else
    sensitive in `config`. Use `extraEnv` with `valueFrom.secretKeyRef`, or
    `extraEnvFrom` with a `secretRef`, and reference the environment variable
    from the estate declaration as described in
    [Declarative estate configuration](declarative-estate.md).

### Single settings and secrets: `extraEnv`, `extraEnvFrom`

```yaml
extraEnv:
  - name: SKILLSGATEWAY_RETENTION_ENABLED
    value: "true"
  - name: SGW_ESTATE_CI_BOT_SECRET
    valueFrom:
      secretKeyRef:
        name: skills-gateway-estate
        key: ci-bot-secret

extraEnvFrom:
  - secretRef:
      name: skills-gateway-extra
```

Environment names are Spring's relaxed-binding form of the property:
`skills-gateway.roles.enabled` is `SKILLSGATEWAY_ROLES_ENABLED`.

## Storage

The gateway keeps three sets of repositories on disk under `/data`: the
quarantine, the published content the facade serves, and hosted first-party
marketplaces. PostgreSQL records which snapshots exist and which are approved.

Those two halves are one estate, and only one of them survives a restart onto an
empty volume. A gateway that comes back with an empty `/data` still reports its
snapshots as published, still lists them in the catalog, and can serve none of
them — with no rehydration path, because a published snapshot is a pinned
history, not something re-fetchable from upstream.

So the chart **has no storage default and refuses to render without a choice**:

```yaml
persistence:
  mode: existingClaim          # durable
  existingClaim: skills-gateway-data
```

```yaml
persistence:
  mode: ephemeral              # emptyDir; everything is lost on restart
```

Any other value — including leaving `mode` empty — stops `helm install` with a
message explaining what is lost. Choosing `ephemeral` is fine for a demo or a
throwaway environment; it is a deliberate act either way.

The claim must already exist in the release namespace. The chart does not create
a PersistentVolumeClaim, because the interesting decisions — storage class,
access mode, size, static or dynamic provisioning — belong to the cluster, not
to this chart.

### `replicaCount` must stay 1

Storage today is a plain filesystem, written by the ingestion pipeline, the
approval step and the facade's own repacking. **There is no cross-pod locking**,
so two replicas writing the same volume can interleave a fetch and a publish
into a corrupt repository. Scaling out is not a configuration change; it needs a
storage backend that arbitrates writers, which is
[#127](https://github.com/skillsgateway/skillsgateway/issues/127).

Keep `replicaCount: 1`. A rolling update briefly runs two pods, so prefer the
`Recreate` strategy — or accept a moment's overlap — on volumes that allow only
one writer anyway.

## Ingress and TLS

The Ingress is off by default:

```yaml
ingress:
  enabled: true
  className: nginx
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt
  hosts:
    - host: skills-gateway.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: skills-gateway-tls
      hosts:
        - skills-gateway.example.com
```

!!! danger "TLS is not optional in production"

    The git facade authenticates clients with personal access tokens over HTTP
    Basic — see [the facade reference](../reference/git-facade.md). Every clone
    and every fetch puts a long-lived credential on the wire. Without TLS,
    anything on the path can read it and then act as that client indefinitely.

    Terminate TLS at the Ingress or at a load balancer in front of it, and never
    expose the container's HTTP listener directly.

Both the portal and `/git/**` are served by the same container on the same port,
so one host and one `/` path is the normal configuration.

## Identity and security context

The chart creates a ServiceAccount for the release and runs the pod under it,
because workload identity binds to a *named* account:

```yaml
serviceAccount:
  create: true
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::000000000000:role/skills-gateway
```

By default the container runs as the image's non-root user (uid 65532), with all
capabilities dropped, privilege escalation disabled and a read-only root
filesystem; the chart mounts an `emptyDir` at `/tmp` so the runtime keeps
somewhere to write. `podSecurityContext.fsGroup` is what makes the mounted data
volume writable by that user.

## Worked example: serverless Kubernetes

Serverless node pools (AWS Fargate and equivalents) constrain storage sharply,
and the constraints interact:

- **Block storage cannot be attached.** The EBS CSI *node* component is a
  DaemonSet, and serverless pods do not run DaemonSets — so there is no
  ReadWriteOnce block volume available at all.
- **Network filesystem storage works, but only statically provisioned.** Dynamic
  provisioning needs a controller that can create access points on demand;
  serverless pods get an EFS mount handled by the platform, so the
  PersistentVolume and its access point must exist before the pod does.
- **The instance metadata service is unavailable**, so AWS credentials reach the
  workload through a web-identity role bound to the ServiceAccount (IRSA), which
  is why the chart lets you annotate it.
- **Pods run in private subnets**, so ingress arrives through a load balancer
  you place in front of them.

Create the access point and the PersistentVolume by hand (ids below are
placeholders):

```yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: skills-gateway-data
spec:
  capacity:
    storage: 100Gi           # ignored by the EFS driver; required by the API
  volumeMode: Filesystem
  accessModes:
    - ReadWriteMany
  persistentVolumeReclaimPolicy: Retain
  storageClassName: ""       # static binding: no class, no provisioner
  csi:
    driver: efs.csi.aws.com
    volumeHandle: fs-0123456789abcdef0::fsap-0123456789abcdef0
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: skills-gateway-data
  namespace: skills-gateway
spec:
  accessModes:
    - ReadWriteMany
  storageClassName: ""
  resources:
    requests:
      storage: 100Gi
  volumeName: skills-gateway-data
```

Give the access point a POSIX owner of `65532:65532` so the non-root container
can write to it, then install with `persistence.mode: existingClaim` and
`persistence.existingClaim: skills-gateway-data`.

!!! warning "A network filesystem is a poor substrate for git"

    This works, and it is not what you want in production. Git's on-disk format
    is a random walk over large packfiles: object lookup seeks into a pack,
    follows a delta chain, and does it again. On a local disk the page cache
    absorbs that. Over NFS every one of those seeks is a network round trip, and
    the metadata operations git performs constantly — `stat` on loose objects,
    lock files, `fsync` on refs — are exactly what a network filesystem is worst
    at. Expect clone and repack times measured in multiples, not percentages.

    It is a reasonable substrate for a proof of concept, a pilot, or a small
    internal estate. The production answer is an object-storage backend that
    stops treating a POSIX filesystem as the source of truth, tracked as
    [#127](https://github.com/skillsgateway/skillsgateway/issues/127).

## Verifying the install

```bash
kubectl -n skills-gateway rollout status deploy/skills-gateway
kubectl -n skills-gateway logs deploy/skills-gateway | grep estate
```

Then log in to the portal and confirm the estate reconciled — `GET /api/estate`
reports the last run, and the audit ledger carries its entries under the
`config-reconciler` principal. See
[Declarative estate configuration](declarative-estate.md).
