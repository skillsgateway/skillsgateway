# Choosing and migrating the storage backend

The gateway holds its git repositories on one of two backends. The choice is
named in configuration, never inferred, and it is the same choice in the chart
and in the application.

| | `filesystem` | `object-store` |
| --- | --- | --- |
| Where repositories live | Bare repositories under `skills-gateway.data-dir` | Immutable packs plus one reference manifest per repository, in an S3-compatible bucket |
| Writers | Exactly one. There is no cross-pod locking of any kind | Any number. Every reference transition is one conditional write |
| `replicaCount` | Must be 1 | May exceed 1 — see [Running more than one replica](#running-more-than-one-replica) |
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
small object. That single compare-and-swap is the whole serialization of a
reference transition: the transition needs no lock service and no leader to
perform it, and the store itself is the only party that arbitrates. There is no
degraded mode without it, because last-writer-wins on the reference manifest is
precisely the lost update the design exists to prevent. (The gateway does keep a
coordination table in its own database, but for
[the scheduled passes](#running-more-than-one-replica) — never for a reference
transition.)

The gateway probes the configured bucket at startup and refuses to run where the
probe fails, so an unsupported store is a startup error rather than a corruption
discovered during an approval.

| Store | Status |
| --- | --- |
| Floci `1.5.33` | **Verified.** Every build runs the backend's contract and concurrency suites against it |
| MinIO `RELEASE.2025-07-23T15-54-02Z` | **Verified by probe.** All the conditional-write assertions pass, repeatably. Not part of the build, because MinIO stopped publishing free container images around October 2025 |
| AWS S3 | **Verified against a real bucket.** All the conditional-write assertions pass, and both negative controls discriminate. Verified on a plain bucket — see the note below for what that leaves open. Not part of the build, because it needs an account no contributor should have to have |
| Google Cloud Storage | **Out of scope.** It has generation preconditions rather than `If-Match`, which would need an adapter |
| Ceph RGW and other on-prem S3 gateways | **Unverified.** Varies by version; the startup probe is what will tell you |

!!! note "What the AWS S3 row covers, and what it does not"

    The assertions were run against a scratch bucket created with defaults: **no
    versioning and no SSE-KMS.** On a bucket with either of those an ETag is not
    a content hash, and while the gateway never *computes* an expected ETag — it
    only chains the one the store returned — that case has not been run. If your
    bucket uses either, [run the suite against it](verifying-an-object-store.md)
    rather than reading this row as covering it. Doing so needs no code change
    and takes a few minutes.

    The row also says nothing about your account: a bucket policy, an object
    lock or a service control policy can refuse writes the store itself would
    have accepted. That is what the startup probe is for, and what the guide's
    two negative controls will tell you apart.

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

Reads and facade fetches spread across replicas safely on the object-store
backend, and concurrent writers are serialized by the conditional write. What
was left was everything the gateway runs on a schedule: each of those passes
enumerates rows the whole estate shares, so N replicas meant N sync passes, N
webhook deliveries and N exporters advancing the same cursor.

Each pass now takes a **lease** before it runs
(GW_FACADE_0030 — A scheduled background pass runs on one replica at a time).
The lease is one conditional upsert on a row in the gateway's own database,
keyed by the pass's name: the replica that takes it runs the pass, and a replica
that does not **skips that tick and returns**. Nothing blocks and nothing
queues. That is deliberate — all the passes share one scheduler thread, so a
replica waiting its turn on the six-hourly re-vetting lease would stall the
five-second webhook poll behind it.

The lease is also **never released early**. It lapses on its own after the
pass's own interval, which is what turns "one pass per replica per interval"
into "one pass per interval, estate-wide", and it means a replica that dies
mid-pass costs exactly what one that finished costs: the next tick. There is no
unlock to leak and no stale lock to clear by hand.

**No configuration was added, and none is needed.** A lease lasts its pass's own
interval, so the interval you already set is the lease. The row also records a
`holder` — the pod hostname, which under Kubernetes is the pod name — so *which*
replica ran a pass stays answerable afterwards.

### What each pass leases

| Pass | Lease key | Lease lasts | Runs when |
| --- | --- | --- | --- |
| Upstream sync | `sync` | `skills-gateway.sync.poll-interval` (10m) | `skills-gateway.sync.enabled`, on by default |
| Continuous re-vetting | `revet` | `skills-gateway.vetting.revet.interval` (6h) | `skills-gateway.vetting.revet.enabled`, on by default |
| Retention evaluation | `retention-evaluate` | `skills-gateway.retention.poll-interval` (1h) | `skills-gateway.retention.enabled`, off by default |
| Retention compaction | `retention-compact` | twice `skills-gateway.retention.compaction-interval` (6h) | `skills-gateway.retention.enabled`, off by default |
| Waiver expiry | `waiver-expiry` | `skills-gateway.vetting.waiver-sweep-interval` (1h) | always; there is no switch |
| Webhook dispatch | `webhook-dispatch` | `skills-gateway.webhooks.poll-interval` (5s) | `skills-gateway.webhooks.enabled`, on by default |
| Mirror reconciliation | `mirror-drift` | `skills-gateway.mirror.sweep-interval` (15m) | `skills-gateway.mirror.sweep-enabled`, with a mirror configured |
| Audit export | `audit-export` | `skills-gateway.audit-export.poll-interval` (30s) | `skills-gateway.audit-export.enabled`, on by default |

Compaction is the one exception to "the lease is the interval": it takes twice
its interval, because it runs `git gc` and a pass that legitimately outlasts one
period must not have a second replica start behind it.

Two passes carry a second line of defence of their own, because a lease bounds
how many replicas run a pass and not what a single pass does with a row it read.
Waiver expiry stamps the waiver as expired before it writes the
`waiver-expired` entry and writes the entry only if it won that stamp, so one
lapse is one ledger entry (GW_VETTING_0011 — Waiver lifecycle is audit-logged).
Audit export advances its cursor with a compare-and-set against the value it
read. That does not deduplicate a delivery — the lease is what stops there being
two exporters — but it stops the cursor being clobbered, and in particular stops
an export pass silently undoing an operator's replay rewind
(GW_AUDIT_0004 — Audit export sinks with at-least-once delivery,
GW_AUDIT_0005 — Audit export cursor replay).

### What is still single-replica

The `filesystem` backend, and the chart still refuses `replicaCount > 1` there
(GW_FACADE_0014 — Deployment refuses a storage and replication shape the gateway
cannot honour). That refusal is about reference transitions on the serving path,
not about the scheduled passes: two pods on one volume can interleave a fetch
and a publish into a corrupt repository, and a lease on a sweep does nothing
about it. Scaling out means moving to `object-store` first.

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

!!! note "Revocation takes effect on the next advertisement, on every replica"

    A replica caches the reference map it last read, so a revocation performed
    elsewhere would otherwise stay invisible to it. Every reference
    advertisement is therefore preceded by a conditional `GET` of the
    repository manifest — `O(1)`, and no body when nothing has changed — which
    makes the bound on how long a revoked snapshot stays advertised "the next
    advertisement".

    There is no setting for this. `ref-freshness` used to let an operator
    lengthen that bound; it was removed, and setting it now refuses startup —
    see [Configuration](../reference/configuration.md).

## Migrating an existing deployment

The migration is **offline, verified and reversible**. There is no dual write
and no live cutover: the deployment you are migrating away from is by definition
the single replica on a single volume the `filesystem` backend requires, so a
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

Run the published container image, with the same volume and the same identity
the gateway itself runs with, and append the arguments to the entrypoint:

```bash
kubectl run skills-gateway-migration \
  --image=ghcr.io/skillsgateway/skillsgateway@sha256:… \
  --restart=Never --attach --rm \
  --overrides='{"spec":{"serviceAccountName":"skills-gateway",
    "volumes":[{"name":"data","persistentVolumeClaim":{"claimName":"skills-gateway"}}],
    "containers":[{"name":"skills-gateway-migration","volumeMounts":[{"name":"data","mountPath":"/data"}]}]}}' \
  -- \
  --spring.main.web-application-type=none \
  --skills-gateway.data-dir=/data \
  --skills-gateway.storage.backend=filesystem \
  --skills-gateway.storage.object-store.bucket=skills-gateway \
  --skills-gateway.storage.object-store.region=eu-north-1 \
  --skills-gateway.storage.object-store.credentials.mode=web-identity \
  --skills-gateway.storage.migration.enabled=true \
  --skills-gateway.storage.migration.to=object-store
```

The same arguments work against the jar the release attaches, for a migration
run outside the cluster:

```bash
java -jar skills-gateway-server-<version>.jar \
  --spring.main.web-application-type=none \
  --skills-gateway.data-dir=/data \
  … the same arguments …
```

!!! note "This used to be documented against the native binary"

    Earlier releases published a GraalVM native image, and this guide told you to
    run it. Both `--spring.main.web-application-type=none` and the migration
    runner's own switch were fixed when that image was built, so the flags were
    accepted and the process started an ordinary server instead — a migration
    that could report having finished without having copied anything. The release
    artifact is now a JVM container ([ADR 0012](../reference/decisions.md)) and
    the migration runner reads its switch at runtime on every packaging, so the
    procedure above does what it says.

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

Then scale back up. The gateway itself compares the served estate against what
the database records as approved before it serves its first request, so a
migration that lost a reference shows up as `publication-repaired` on the ledger
and one serving something no approval covers as
`publication-served-not-approved` — on a clean copy, neither appears. See
[When the repair fails too](approving-snapshots.md#when-the-repair-fails-too).

### Rolling back

Switch `storage.backend` back to `filesystem` and scale up. Nothing wrote to the
volume while the gateway was on the bucket, so the previous state returns
exactly. If the volume is already gone, the same command with `backend` and
`migration.to` swapped copies the estate back onto a fresh one.

## See also

- [Verifying conditional writes against a real bucket](verifying-an-object-store.md)
- [Configuration reference — Git storage](../reference/configuration.md#git-storage)
- [Deploying on Kubernetes](deploying-on-kubernetes.md)
- [Trust boundaries](../concepts/trust-boundaries.md)
- [Observability](../reference/observability.md)
