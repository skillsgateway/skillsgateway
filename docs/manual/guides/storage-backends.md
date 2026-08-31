# Choosing and migrating the storage backend

The gateway holds its git repositories on one of two backends. The choice is
named in configuration, never inferred, and it is the same choice in the chart
and in the application.

| | `filesystem` | `object-store` |
| --- | --- | --- |
| Where repositories live | Bare repositories under `skills-gateway.data-dir` | Immutable packs plus one reference manifest per repository, in an S3-compatible bucket |
| Writers | Exactly one. There is no cross-pod locking of any kind | Any number. Every reference transition is one conditional write |
| `replicaCount` | Must be 1 | May exceed 1, under [the gate below](#running-more-than-one-replica) |
| Durability | The volume's problem | The bucket's problem |
| Local disk | The source of truth | A cache, safe to delete at any moment |
| Read latency | Page cache | First fetch of a pack pays object-store latency; later reads are local |
| Default | Yes | No |

**`filesystem` stays the default and stays the recommendation for a single-node
or air-gapped deployment.** It is simpler, it is faster once warm, and nothing
about it is deprecated. The reason to move is that one pod with one volume is
also one point of failure, and that on some platforms there is no good volume to
have.

## When the platform makes the choice for you

On serverless Kubernetes — EKS Fargate is the worked example — object storage
is not a scaling nicety. It is the only substrate that suits git:

- **EBS cannot be mounted at all.** The EBS CSI node component is a DaemonSet,
  and DaemonSets are unsupported there, so there is no workaround.
- **EFS is supported, statically provisioned only** — that is, NFS. Git's
  on-disk format is a random walk over large packfiles, which is exactly the
  access pattern a network filesystem is worst at.
- **EFS is ReadWriteMany**, so a second pod can mount and write the same volume.
  On the filesystem backend `replicaCount: 1` is then held by convention alone.
- **There is no instance metadata service**, so credentials must come from
  workload identity. See [credential modes](#credentials).

An EFS-backed claim does work, and it is a reasonable substrate for a pilot.
It is not the production answer.

## Which object stores work

The backend needs exactly one primitive that not every store implements: a
**conditional write** (`If-Match`, and `If-None-Match: *` for creation) on a
small object. That single compare-and-swap is what makes a reference transition
atomic without a lock service, a coordination database or leader election —
and there is no degraded mode without it, because last-writer-wins on the
reference manifest is precisely the lost update the design exists to prevent.

The gateway probes the configured bucket at startup and refuses to run where the
probe fails, so an unsupported store is a startup error rather than a corruption
discovered during an approval.

| Store | Status |
| --- | --- |
| Floci `1.5.33` | **Verified.** Every build runs the backend's contract and concurrency suites against it |
| MinIO `RELEASE.2025-07-23T15-54-02Z` | **Verified by probe.** All the conditional-write assertions pass, repeatably. Not part of the build, because MinIO stopped publishing free container images around October 2025 |
| AWS S3 | **Documented, not exercised by this project.** AWS documents `If-Match` and `If-None-Match` on `PutObject`, and it is the primary target — but nobody here has run the assertions against a real account, so it is believed rather than verified |
| Google Cloud Storage | **Out of scope.** It has generation preconditions rather than `If-Match`, which would need an adapter |
| Ceph RGW and other on-prem S3 gateways | **Unverified.** Varies by version; the startup probe is what will tell you |

!!! warning "The AWS S3 row is not a verified row"

    It is the production target and it is the row that has never been run. If
    you are the first to run the gateway against a real S3 bucket, the startup
    probe is doing real work for you rather than confirming something already
    known.

    Closing that row is tracked as
    [issue #151](https://github.com/skillsgateway/skillsgateway/issues/151).

## Credentials

`skills-gateway.storage.object-store.credentials.mode` names how credentials are
resolved, for the same reason the backend is named: a misconfigured deployment
should fail at startup saying so, rather than walking down a provider chain to a
metadata endpoint that never answers and timing out in the middle of an approval.

`web-identity` is the primary mechanism and the chart's default. The pod's
service account carries the role annotation, the projected token is exchanged
for credentials, and the gateway holds no secret at all:

```yaml
serviceAccount:
  create: true
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::000000000000:role/skills-gateway

storage:
  backend: object-store
  objectStore:
    bucket: skills-gateway
    region: eu-north-1
    credentials:
      mode: web-identity
```

`static` is the fallback for stores with no role mechanism. The keys come from a
Secret that already exists, with keys `access-key-id` and `secret-access-key`;
there is deliberately nowhere in `values.yaml` to type one.

```yaml
storage:
  objectStore:
    credentials:
      mode: static
      existingSecret: skills-gateway-object-store
```

!!! warning "Write access to the bucket is publication"

    The served reference map is an object in the bucket. Anyone who can write it
    can put content on the wire without `ApprovalService` ever seeing it. Give
    the gateway a narrow policy — object read, write and delete under its own
    prefix, no bucket administration — and inherit the encryption and
    access-logging expectations of the volume it replaces. The gateway cannot
    enforce this for you.

## What it costs

A replica that has never seen a repository reads one small manifest and then
fetches packs on demand; a pack is fetched whole into the local cache on first
open and read from disk after that. So the first clone of a marketplace on a
fresh replica pays object-store latency, and everything after it does not.

Marketplace repositories are skill repositories rather than monorepos, so the
absolute cost is small — but it is a real regression against a warm volume, and
it is worth measuring on your own store before deciding the trade is free.

Two levels grow with use and are held down by compaction: the write-ahead log,
and the number of live packs. Both are published as metrics, along with
conditional-write conflicts and retries and per-request latency — see
[Observability](../reference/observability.md).

## Running more than one replica

Removing the storage obstacle is not the same as making the gateway
cluster-safe. Reads and facade fetches spread across replicas safely on the
object-store backend, and concurrent writers are serialized by the conditional
write. What is *not* safe is everything in the gateway that is a scheduled
singleton with no coordination: the upstream sync sweep, the re-vetting sweep,
retention, the webhook dispatch poller and the audit-export poller. N replicas
would mean N sweeps, N webhook deliveries and N exporters advancing the same
cursor.

The chart gates both together. `replicaCount > 1` is refused outright on
`filesystem`, and on `object-store` it is refused while any of those switches is
still on — the refusal names each one. The honest shape is two deployments
sharing one bucket:

```yaml
# The worker: one replica, the sweeps on.
replicaCount: 1
storage:
  backend: object-store
  objectStore: { bucket: skills-gateway, region: eu-north-1 }
```

```yaml
# The serving deployment: scaled out, the sweeps off.
replicaCount: 3
storage:
  backend: object-store
  objectStore: { bucket: skills-gateway, region: eu-north-1 }
config:
  skills-gateway:
    sync: { enabled: false }
    vetting: { revet: { enabled: false } }
    retention: { enabled: false }
    webhooks: { enabled: false }
    audit-export: { enabled: false }
```

Leader election that removes the split is separate, later work.

!!! note "Publication is one transition, and a lost race is ordinary"

    Putting a snapshot on the wire moves two references — the served tip and the
    snapshot's own pinned reference — and it does so as a single all-or-nothing
    transition, so an observer never sees one without the other. On this backend
    that is one conditional write of the reference manifest, retried internally
    when it loses.

    With more than one replica, or any concurrent revocation, losing that race is
    an expected outcome rather than an error. What matters is that a transition
    which did not take effect is raised rather than reported as done: a refused
    publication fails the approval, publishes nothing, and leaves the snapshot to
    be approved again. See
    [Approving snapshots](approving-snapshots.md#when-an-approval-fails).

!!! note "Revocation is not instantaneous across replicas"

    A replica serves the reference map it last read. `ref-freshness` (10 s by
    default) is the upper bound on how long a replica that did not perform a
    revocation may still advertise the revoked snapshot. Lower it if that
    matters more than the extra conditional `GET` per advertisement.

## Migrating an existing deployment

The migration is **offline, verified and reversible**. There is no dual write
and no live cutover: the gateway runs one replica on one volume today, so a
short maintenance window is affordable and far cheaper to make correct.

The old volume is the rollback. Keep it until you are satisfied.

### 1. Stop the gateway

```bash
kubectl scale deployment/skills-gateway --replicas=0
```

### 2. Run the migration

The migration is the same artifact, started as a migration instead of as a
service. The source is whatever `storage.backend` names; `storage.migration.to`
names the destination, so the migration and its rollback are the same
configuration with two values swapped.

```bash
skills-gateway-server \
  --spring.main.web-application-type=none \
  --skills-gateway.data-dir=/data \
  --skills-gateway.storage.backend=filesystem \
  --skills-gateway.storage.object-store.bucket=skills-gateway \
  --skills-gateway.storage.object-store.region=eu-north-1 \
  --skills-gateway.storage.object-store.credentials.mode=web-identity \
  --skills-gateway.storage.migration.enabled=true \
  --skills-gateway.storage.migration.to=object-store
```

It builds the destination through the same validation and the same startup probe
a serving start would use, so a bucket the gateway would refuse to serve from is
one it refuses to migrate into — found before the copy rather than after it. It
then copies every repository in all three roles, streaming rather than loading
whole repositories into memory.

### 3. Read the exit status, not the log

After copying, the migration re-reads **both** sides and compares, per
repository, the resolved reference set and the branch the head reference names.
The process exits `0` only when every repository verified, and `1` otherwise,
naming each repository that did not match. Nothing is re-approved and nothing is
re-ingested — the bytes move, the decisions do not.

!!! danger "Do not discard the old volume on a non-zero exit"

    A failed verification means the destination must not be adopted. The source
    is untouched either way, so the recovery is to fix the cause and run it
    again, not to reconcile a half-migrated estate.

### 4. Switch the backend and start

```yaml
storage:
  backend: object-store
  objectStore:
    bucket: skills-gateway
    region: eu-north-1
persistence:
  mode: ephemeral   # the local pack cache; or `none` to keep no volume at all
```

Then scale back up and check the served estate against what the database records
as approved.

### Rolling back

Switch `storage.backend` back to `filesystem` and scale up. Nothing wrote to the
volume while the gateway was on the bucket, so the previous state returns
exactly. If the volume is already gone, the same command with `backend` and
`migration.to` swapped copies the estate back onto a fresh one.

## See also

- [Configuration reference — Git storage](../reference/configuration.md#git-storage)
- [Deploying on Kubernetes](deploying-on-kubernetes.md)
- [Trust boundaries](../concepts/trust-boundaries.md)
- [Observability](../reference/observability.md)
