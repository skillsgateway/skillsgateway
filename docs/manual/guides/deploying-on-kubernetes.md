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
to this chart. Which of them are even available to you is constrained by the
platform — see
[Storage options on serverless Kubernetes](#storage-options-on-serverless-kubernetes).

### `replicaCount` depends on the storage backend

On the default `filesystem` backend, storage is a plain filesystem written by
the ingestion pipeline, the approval step and the facade's own repacking.
**There is no cross-pod locking**, so two replicas writing the same volume can
interleave a fetch and a publish into a corrupt repository. The chart refuses to
render `replicaCount > 1` there rather than trusting you to know that.

Keep `replicaCount: 1`. A rolling update briefly runs two pods, so prefer the
`Recreate` strategy — or accept a moment's overlap — on volumes that allow only
one writer anyway.

On the `object-store` backend concurrent writers are safe by construction, and
more than one replica becomes possible — but only with the gateway's
uncoordinated background sweeps and pollers switched off on the scaled-out
deployment, which the chart also checks. See
[Running more than one replica](storage-backends.md#running-more-than-one-replica).

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

## Storage options on serverless Kubernetes

Serverless node pools — AWS Fargate and its equivalents — constrain storage
sharply, and the constraints interact. This is the short version of the choice,
for the case where the platform is already fixed:

| Option | Available? | Verdict for this workload |
| --- | --- | --- |
| Block storage (RWO) | **No** | Not a trade-off — there is no configuration that gets you one. |
| Network filesystem (RWX, e.g. NFS/EFS) | Yes, **static provisioning only** | Works. Good for a proof of concept; the substrate git is worst on. |
| Parallel filesystem (e.g. FSx for Lustre) | **No** | Unavailable on serverless pods. |
| Object storage | **Supported** | The production answer here: `storage.backend: object-store`, credentials from workload identity, and no volume required at all — see [the storage guide](storage-backends.md). |
| Block storage on managed nodes | Yes, if you can choose the platform | What today's storage was designed for. |

**Block storage is unavailable, not merely awkward.** On EKS, AWS states plainly
that *"You can't mount Amazon EBS volumes to Fargate Pods"*, and the reason is
structural: the EBS CSI *node* component is a DaemonSet, and *"Daemonsets aren't
supported on Fargate"* — the CSI controller can run there, the node component
cannot. There is no ReadWriteOnce volume to be had, so the single-writer
filesystem this gateway was built around has nowhere to live.

**A network filesystem works, with static provisioning only.** A Fargate pod
mounts EFS without any driver installation, but *"You can't use dynamic
persistent volume provisioning with Fargate nodes, but you can use static
provisioning"* — so the PersistentVolume and its access point must exist before
the pod does, which is what the worked example below creates by hand.

Two caveats worth understanding before choosing it:

- **Git is a bad fit for a network filesystem.** Git's on-disk format is a
  random walk over large packfiles: object lookup seeks into a pack, follows a
  delta chain, and does it again — plus constant small metadata operations
  (`stat` on loose objects, lock files, `fsync` on refs). On a local disk the
  page cache absorbs all of that. Over NFS each one is a network round trip.
  Expect clone and repack times in multiples, not percentages.
- **RWX does not enforce the single-writer assumption.** A network filesystem
  is ReadWriteMany by nature, so nothing stops a second pod from mounting the
  same volume and writing to it. Today's storage has no cross-pod locking, so
  `replicaCount: 1` holds by convention here rather than by construction — an
  RWO block volume would have refused the second writer for you.

**A parallel filesystem is not an option either**: FSx for Lustre is listed as
unavailable to Fargate pods.

**Object storage is the answer on serverless platforms specifically.** Not
because of scale — because the alternatives here are "impossible" and "the
substrate git is worst on". It is implemented: set
`storage.backend: object-store`, and the repositories live in a bucket with
local disk as a cache. Fargate has no instance metadata service, so credentials
come from workload identity — see
[Choosing and migrating the storage backend](storage-backends.md).

**If the platform choice is still open**, an ordinary managed node group with an
RWO block volume is what the current storage implementation was designed for,
and it enforces the single-writer property structurally rather than by
convention. Serverless is worth its constraints for plenty of workloads; a git
server on a network filesystem is not the case it is best at.

### Two operational notes that are easy to miss

- **No instance metadata service.** Serverless pods typically cannot reach IMDS,
  so cloud credentials come from workload identity bound to a *named* service
  account (IRSA on EKS). That is why the chart creates a ServiceAccount and lets
  you annotate it; on EKS the documented remedy for a Fargate pod that needs IAM
  credentials is exactly that.
- **Egress needs a NAT path.** Fargate pods run in private subnets only, with no
  direct route to an internet gateway. Ingestion fetches from upstream git over
  the network, so without a NAT gateway (or equivalent egress) registration and
  sync will hang rather than fail quickly. Inbound traffic likewise arrives
  through a load balancer you place in front of the pods.

## Worked example: a statically provisioned network filesystem

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

!!! warning "This is a proof-of-concept substrate"

    It works, and it is not what you want in production — see
    [the comparison above](#storage-options-on-serverless-kubernetes). It is a
    reasonable substrate for a proof of concept, a pilot, or a small internal
    estate. The production answer is the `object-store` backend, which stops
    treating a POSIX filesystem as the source of truth; moving to it is an
    offline, verified, reversible copy described in
    [Choosing and migrating the storage backend](storage-backends.md).

## Verifying the install

```bash
kubectl -n skills-gateway rollout status deploy/skills-gateway
kubectl -n skills-gateway logs deploy/skills-gateway | grep estate
```

Then log in to the portal and confirm the estate reconciled — `GET /api/estate`
reports the last run, and the audit ledger carries its entries under the
`config-reconciler` principal. See
[Declarative estate configuration](declarative-estate.md).
