# Design: pluggable-git-storage

Tracked as issue [#127](https://github.com/skillsgateway/skillsgateway/issues/127).

## Context

`GitStorage` (`src/main/java/dev/skillsgateway/server/storage/GitStorage.java`)
is the whole storage seam. It hands callers open JGit `Repository` handles
across three repository roles, plus one behavioral method:

| Role | Written by | Read by | Served? |
| --- | --- | --- | --- |
| `quarantine(name)` | ingestion (sole writer) | approval, vetting, preview | never |
| `hosted(name)` / `hostedIfPresent(name)` | authenticated publisher push | ingestion only | never |
| `published(name)` / `publishedIfServing(name)` | `ApprovalService.approve` only | the facade | yes |

and `unpublish(name, sha)`, whose contract is exact and load-bearing: delete
`refs/snapshots/{sha}` unconditionally, delete `refs/heads/main` **only** while
it still resolves to that SHA, and return whether that deletion is what stopped
the marketplace serving. Nothing else moves; quarantine is untouched.

`FilesystemGitStorage` is the only implementation: three directories under
`skills-gateway.data-dir`, bare repositories opened through `RepositoryBuilder`,
refs updated through `RefUpdate`. That design carries two properties the rest of
the system quietly depends on, and neither is written down as a constraint:

1. **One writer.** There is no cross-pod, and no cross-process, locking beyond
   what `RefDirectory` does on a local filesystem. `replicaCount` must be 1.
2. **Durability is the volume's problem.** `helm/skills-gateway/values.yaml`
   sets `persistence.existingClaim: ""` and `templates/deployment.yaml` falls
   back to `emptyDir: {}`. The documented consequence
   (`reference/configuration.md`) is that "approved published refs live only on
   that volume" — so the default deployment loses every published repository on
   pod restart while PostgreSQL still reports those snapshots `approved`. The
   estate becomes internally inconsistent, and because `ApprovalService.approve`
   is the sole publisher there is no mechanism that would ever rebuild it.

ADR 0008 makes both of these sharper than they look: the embedded facade is the
only serving surface, and its availability is explicitly a security property.
A single-replica, single-volume serving path is at odds with that.

### The deployment target removes the filesystem option

The first real deployment target is **EKS Fargate**, and that changes this from
an improvement into a constraint. Per the AWS EKS documentation:

- "You can't mount Amazon EBS volumes to Fargate Pods." The comparison table
  answers "Can use Amazon EBS storage with Pods: **No**."
- There is no workaround: the EBS CSI **node** component is a DaemonSet, and
  DaemonSets are unsupported on Fargate.
- EFS is supported and auto-mounts, but only statically: "You can't use dynamic
  persistent volume provisioning with Fargate nodes, but you can use static
  provisioning."
- The EC2 instance metadata service (**IMDS**) is not available to Fargate pods.
- Fargate pods run in private subnets only; egress needs NAT.

So the only filesystem available to `FilesystemGitStorage` on Fargate is EFS —
NFS. That is the substrate git is worst on, and for the exact reason the walgit
design notes single out: packfile access is a random walk over large binary
files, which is fine against a page cache and slow against a network filesystem.
Every `upload-pack` the facade serves is that access pattern. Object storage is
therefore not a scaling nicety on serverless Kubernetes; it is the only
substrate that suits git.

EFS also being **RWX** takes away the last structural guard. On an RWO volume,
"one writer" is enforced by the volume. On EFS it is held by the convention
`replicaCount: 1` and nothing else — one careless scale-up and two pods
force-update `refs/heads/main` over NFS, with no cross-pod locking anywhere in
`FilesystemGitStorage`. The compare-and-swap design below makes concurrent
writers safe by construction rather than by configuration discipline.

None of this blocks a proof of concept today. `persistence.existingClaim` in the
chart is backend-agnostic, so an EFS-backed statically provisioned PVC works
with the chart exactly as it stands. This design is about what production needs,
not about unblocking the first trial.

The reference architecture for fixing it is public. [walgit](https://github.com/tobi/walgit)
(MIT, Rust) implements the "Continuity" shape from Cursor's engineering post
[Git at any scale](https://cursor.com/blog/git-at-any-scale) (18 Aug 2026):
"make a write-ahead log in object storage the source of truth, and make every
on-disk repository a cache"; "a push is stored as an immutable object in the
bucket and becomes visible only when a tiny manifest is rewritten with a
compare-and-swap. That CAS *is* the consensus — no election, no quorum, no
primary"; "any machine that runs walgit is a disposable cache; the bucket is the
repository."

**walgit is a reference, not a dependency, and cannot be adopted here.** It is a
standalone Rust server that owns its own wire protocol; `GitStorage` hands Java
callers live JGit `Repository` handles that ingestion, vetting, preview,
approval and the facade all use directly; and ADR 0008 decided the serving
surface stays the embedded facade rather than a separate server the gateway
proxies. What transfers is the *architecture* — a write-ahead log of immutable
objects, visibility by compare-and-swap on a small manifest, local disk as
disposable cache — not the code.

## Goals / Non-Goals

**Goals**

- One configuration property selects the storage backend, and a wrong or
  incomplete selection fails startup rather than degrading to something else.
- An object-storage backend that satisfies the existing `GitStorage` contract —
  including `unpublish` — with no change to any caller.
- Ref transitions on the published repository are atomic and linearizable
  without a coordination database, a lock service or leader election.
- Stop the chart from defaulting to storage that loses served content.
- Make multi-replica *serving* possible, and be explicit about what still is
  not safe to replicate.
- A migration from an existing PVC deployment that re-approves nothing.

**Non-Goals**

- Replacing `FilesystemGitStorage`. It stays, it stays the default, and a
  single-node or air-gapped deployment should keep using it.
- Serving from a forge, or from any surface other than the facade — ADR 0008.
- Making `ApprovalService` not the sole publisher, or changing what publication
  means. This change moves bytes, not the gate.
- Leader election, distributed locks, or making the background sweeps
  cluster-safe. This change *unblocks* multi-replica serving; making the
  singletons safe is separate work, and is gated on — not delivered by — this.
- Cross-region or multi-bucket replication.
- OCI-registry or content-addressed distribution (Phase 3 roadmap).

## Decisions

### 1. Backend selection is a configuration property with no fallback

`skills-gateway.storage.backend` — an enum, `filesystem` (default) or
`object-store` — resolved to a single `GitStorage` bean. Rules:

- Absent → `filesystem`. An upgrade changes nothing.
- Unrecognised value → **startup fails**, naming the accepted values. Spring's
  relaxed binding to an enum already does this; the point is that it is
  asserted, not that it is new.
- `object-store` without a resolvable bucket, endpoint or credentials →
  **startup fails**, before any request is served.
- There is no "try object storage, fall back to disk". A gateway that quietly
  serves from a filesystem the operator believed was a bucket is the same class
  of defect as the `emptyDir` default: a system that reports healthy while
  serving from storage nobody chose.

*Alternative rejected — auto-detection* (use object storage if a bucket is
configured, otherwise disk). It makes the backend a property of which
environment variables happen to be set, so a typo silently changes where
published content lives. The whole value of naming the backend is that the
name, not an inference, is what fails.

*Alternative rejected — a Spring profile* (`--spring.profiles.active=s3`).
Profiles compose with the deployment's other profiles and are easy to lose in a
chart template; this is a storage decision and it belongs in the storage
namespace next to `data-dir`.

### 2. The object-storage backend is a JGit DFS implementation we write

JGit 7.7.1 ships the DFS **framework** and nothing that talks to a bucket.
Verified against `org.eclipse.jgit-7.7.1.202607240634-r.jar`,
`org/eclipse/jgit/internal/storage/dfs/`: `DfsRepository`, `DfsObjDatabase`,
`DfsRefDatabase`, `DfsReftableDatabase`, `DfsPackDescription`,
`DfsOutputStream`, `ReadableChannel`, `DfsInserter`, `DfsReader`,
`DfsBlockCache`, `DfsGarbageCollector`, `DfsPackCompactor` — all extension
points. The **only** concrete repository in that package is
`InMemoryRepository`, which is JGit's own test double.

The classes that mention S3 are not in that package at all:
`org.eclipse.jgit.transport.AmazonS3` and `TransportAmazonS3`, which sit beside
`WalkRemoteObjectDatabase`. That is the legacy "dumb" walk transport — a client
that fetches loose objects and packs over plain GETs, with no upload-pack
negotiation and no ref-advertisement semantics a smart-HTTP facade can serve
from. It is a transport for pushing a mirror *into* a bucket, not a storage
backend to *serve from*. Using it here would be a category error.

So the implementable surface is: extend `DfsRepository`, implement
`DfsObjDatabase` (pack lifecycle — `newPack`, `listPacks`, `openFile`, `writeFile`,
`commitPackImpl`, `rollbackPack`) and a ref database over the bucket, and let
JGit's existing `DfsReader` / `UploadPack` path do the git.

*No reusable implementation exists to adopt, and that is worth stating because
it is the obvious reviewer question.* Google's own DFS backend — the reason this
package exists in JGit at all — was never open-sourced. The only public
JGit-DFS-over-object-storage project is `johnny0917/jgit-aws`: six stars, last
pushed in 2015, no license, self-described as "fairly naive… not properly
tested", and coupled to DynamoDB, which decision 3 rejects on portability
grounds anyway. Gerrit's `lfs-storage-s3` plugin is sometimes offered as a
counter-example; it stores Git LFS objects, not repositories, and shares none of
the ref-consistency problem. So the choice is not build-versus-adopt. It is
build, or do not do this.

*Alternative rejected — mount the bucket as a filesystem* (s3fs, Mountpoint, a
CSI driver). It keeps `FilesystemGitStorage` unchanged, which is genuinely
tempting, and it is wrong for the same reason it is tempting: git's on-disk
format assumes POSIX rename atomicity, `flock`, and cheap partial reads for ref
locking. On an object store those are emulated at best. Two pods force-updating
`refs/heads/main` through a bucket-backed FUSE mount is precisely the lost
update this design exists to prevent.

*Alternative rejected — a filesystem cache with the bucket as backup* (periodic
sync out, restore on boot). It has no atomic visibility point: a snapshot's
objects and its ref can be backed up at different instants, so a restore can
produce a ref pointing at objects that were never uploaded — a published
marketplace that resolves to a commit the facade cannot serve. It also gives
multi-replica nothing.

### 3. Ref state is one small manifest per repository, flipped by compare-and-swap

Per repository (`{prefix}/{role}/{marketplace}/`):

- `objects/pack/{name}.pack` / `.idx` — immutable, content-named, written once
  and never mutated. Uploaded and durable *before* anything references them.
- `wal/{seq}` — an immutable append entry per accepted transition (`publish
  sha`, `unpublish sha`, `ingest sha`), naming the packs it made durable and the
  ref edits it asserts.
- `manifest` — small: the current ref map, the WAL sequence it reflects, and the
  set of live packs.

A ref update is: upload packs → write the WAL entry → **conditionally** rewrite
the manifest, expecting the exact previous version. If the conditional write
fails, the writer re-reads the manifest, re-checks its precondition, and
retries. That compare-and-swap is the only serialization point in the system.

The mechanism is object-store-native and needs no lock table: `If-Match` on the
object's ETag (and `If-None-Match: *` for creation) is supported on S3 and on
S3-compatible stores. Where it is not, the deployment is not a supported target
and startup must say so rather than degrade to last-writer-wins.

**This is what makes `unpublish` exact rather than best-effort.** On the
filesystem, `unpublish` is two `RefUpdate.delete()` calls with a read of
`refs/heads/main` between them; the "is main still this SHA" test and the delete
are not one operation. That has been tolerable only because there is exactly one
writer. On the object store the entire contract — `refs/snapshots/{sha}` removed
unconditionally, `refs/heads/main` removed only while it equals `sha`, and the
boolean saying whether this call is what stopped the marketplace serving — is
evaluated against one manifest and committed as one conditional write. A losing
racer re-reads and re-evaluates. Two concurrent unpublishes of the same SHA
cannot both return `true`, and a revocation cannot take down a marketplace that
a later approval has already moved on.

*Alternative rejected — ref state in PostgreSQL.* The database is right there,
it has real transactions, and the temptation is obvious. Rejected because it
splits the source of truth: objects in the bucket, refs in the database, and a
restore, a failover or a point-in-time recovery that disagrees between the two
produces exactly the inconsistent estate this change exists to fix. Keeping the
manifest beside the objects means one bucket is a complete, self-describing
repository — the property that makes a replica disposable.

*Alternative rejected — DynamoDB or a lock service for refs.* Same split, plus a
second hard infrastructure dependency, plus a cloud-specific one. Requiring only
conditional-write object storage keeps MinIO and on-prem S3-compatible stores in
scope, which for an air-gap-friendly product matters more than using the
nicest available primitive.

*Alternative rejected — a distributed lock over the PostgreSQL we already run
(e.g. Spring Integration's `JdbcLockRegistry`).* Stated at length because it is
the most predictable suggestion this design will meet — "we already have a
database, why not lock?" — and because the answer is not obvious.

**As a substitute for the conditional write it is unsafe, and no amount of
tuning fixes it.** A lock is a lease. A writer takes it, reads the manifest, and
then stops — a stop-the-world pause, CPU throttling, a partition. It cannot
renew, because it is stopped. The lease expires, a second pod legitimately takes
the lock and publishes, and the first pod resumes and issues its write believing
it is still exclusive. That is a lost update: precisely the defect this design
exists to prevent. Checking "do I still hold the lock?" immediately before
writing does not close it either — the lease can lapse between the check and the
write.

The standard remedy is a **fencing token** that the *storage system* validates at
write time, and plain S3 has exactly one such mechanism: `If-Match`. So the lock
scheme needs the conditional write anyway — at which point the conditional write
alone is already sufficient and the lock has added nothing to correctness.
Spring Integration is worth naming precisely here: as of 7.0 it offers
`DistributedLock` with a custom TTL and `RenewableLockRegistry.renewLock`, and
its own documentation frames the guarantee as holding "as long as the underlying
database supports the serializable isolation level in its transactions". None of
that is a fencing token, and serializable isolation is a statement about
correctness *inside* the database. The fencing problem exists exactly because the
lock lives in one system and the data in another.

Three further costs, any one of which would be disqualifying on its own:

- **It only binds participants.** A lock constrains writers that take it. A
  migration command, a manual repair, an older gateway version mid-rollout, or a
  second gateway misconfigured onto the same bucket would clobber silently — and
  this change's own migration tooling would be the first non-participant. A
  conditional write is enforced by the store against every writer, including ones
  that have never heard of us.
- **It couples storage correctness to database availability.** Lock-only means a
  PostgreSQL outage halts publication while the bucket is perfectly healthy, and
  a failover that double-grants the lock row corrupts rather than halts.
- **It moves correctness out of the bucket.** The self-describing property above
  survives only if the lock is purely additive; if the lock *replaces* the
  conditional write, the bucket's integrity depends on an out-of-band convention
  recorded nowhere in the bucket — losing through the back door exactly what the
  "refs in PostgreSQL" alternative was rejected to preserve.

**As an addition on top of the conditional write** a lock is safe but, here,
unmotivated: it buys fewer wasted retries under contention, and this system's
write rate is human-paced approvals against per-marketplace manifests. Losers
fail cleanly with 412 having written nothing. If the conflict and retry metrics
task 9.1 calls for ever show real contention, revisit it then — with evidence,
and as an optimisation rather than as the guarantee.

*Alternative rejected — treating JGit's reftable stack as the consistency
mechanism.* `DfsReftableDatabase` exists and may well be the encoding the ref
database uses; what is rejected is relying on reftable stacking *for atomicity*.
The conditional write on a single small object is what provides it; reftable, if
used, is an encoding underneath.

### 4. Local disk is a cache, and cold start is a real cost

Every replica keeps a local pack cache; nothing in it is authoritative and
deleting it is always safe. Two layers:

- JGit's own `DfsBlockCache`, sized by configuration — the in-process block
  cache the DFS reader is built around.
- A bounded on-disk pack cache under `data-dir`, keyed by the immutable pack
  name. Because pack objects are immutable and content-named, a cached pack is
  never stale and needs no invalidation; only the manifest is re-read.

Cold start on a fresh replica is therefore: read one small manifest, then fetch
pack ranges on demand. The first fetch of a large marketplace pays object-store
latency; later fetches are local. Marketplace repositories here are skill
repositories, not monorepos, so the absolute cost is small — but it is a real
regression against a warm PVC and the docs must say so rather than imply object
storage is free.

Compaction is the maintenance obligation the write-ahead log brings with it
(Cursor's post is explicit that a full restore replays every entry, so entries
must be compacted). A gateway that publishes a snapshot a day accumulates
slowly, but slowly is not never: periodic compaction of WAL entries into the
manifest, and of small packs via `DfsPackCompactor` / `DfsGarbageCollector`, is
in scope for the implementation — and compaction must itself be a
compare-and-swap, so a compactor that loses a race loses harmlessly.

### 5. Credentials must work without IMDS; static keys are the fallback, not the path

Fargate pods have no instance metadata service, so any design that leans on the
EC2 instance-profile leg of the SDK's provider chain does not run on the first
target. **Web-identity federation (IRSA) is the primary credential mechanism and
a design requirement**, not an option to add later: the pod's service account is
annotated with a role ARN, the projected service-account token is exchanged for
credentials, and the gateway holds no secret at all.

The configuration surface has to be able to express that explicitly rather than
only implicitly:

`skills-gateway.storage.object-store.*` — `endpoint`, `region`, `bucket`,
`prefix`, plus `credentials.mode`: `default` (the SDK provider chain),
`web-identity` (IRSA / Workload Identity, with the token file and role ARN taken
from the standard environment), or `static` (access key and secret key from a
mounted secret). Naming the mode means an IRSA deployment that is misconfigured
fails at startup saying so, instead of silently walking down the chain to an
IMDS endpoint that does not answer and timing out mid-approval.

The chart's default is `web-identity`, with the service account annotated for
IRSA; `existingSecret` stays for MinIO and on-prem stores that have no role
mechanism — the same shape the chart already uses for the PostgreSQL password
and the OIDC client secret. Access keys are never logged, never audited and
never echoed by any API, exactly as declared webhook secrets are.

Two Fargate-specific consequences follow. Egress is through NAT from a private
subnet, so an S3 **VPC endpoint** is the sensible topology and the endpoint
setting must accommodate one. And credentials from web identity expire and are
refreshed by the SDK, so a long-running `upload-pack` must tolerate a refresh
mid-stream — worth an explicit test rather than an assumption.

The bucket wants a narrow policy (object read/write/delete under the prefix, no
bucket administration) and is a new place approved content lives, so it inherits
the encryption and access-logging expectations of the volume it replaces. Worth
saying plainly in the docs: with object storage, anyone with write access to the
bucket can rewrite the manifest, which is publishing without
`ApprovalService`. The bucket becomes part of the trust boundary in the way the
volume already was, and `concepts/trust-boundaries.md` has to say so.

### 6. Migration is offline, verified, and reversible by keeping the volume

No dual-write, no live cutover, no backend that reads one and writes the other.
The gateway is not a high-availability system today (one replica, one volume),
so a brief maintenance window is affordable and far cheaper to make correct.

Sequence: scale to zero → run a migration command that copies every repository
in all three roles into the bucket → the command re-reads both sides and
compares the resolved ref sets per repository, refusing on any mismatch → switch
`storage.backend` to `object-store` → start → verify the served estate against
what the database says is approved. The volume is retained, not deleted, and is
the rollback: switching the property back and scaling up restores the previous
state exactly, because nothing wrote to the volume in between.

*Alternative rejected — dual-write with a read-preference flag.* It doubles the
window in which the two can disagree and needs its own reconciliation story; for
a one-replica system it buys nothing a maintenance window does not.

### 7. The chart's ephemeral default — already fixed; this change only adds `none`

**Superseded in part by #134 (GW_0120), merged while this was in review.** The
chart no longer defaults to `emptyDir`: `persistence.mode` is explicit and must
be `existingClaim` (with `persistence.existingClaim` set — the EFS
static-provisioning path on Fargate, and any RWO claim elsewhere) or `ephemeral`,
and anything else fails template rendering with a message that spells out the
data-loss consequence. The defect this change was going to fix is fixed, and the
requirement it was going to raise is GW_0120's.

What is left for this change is one mode: **`none`**, valid only when
`storage.backend` is `object-store`, for a deployment that keeps no durable
volume because the bucket is the repository. Short of that a local pack cache
still wants a volume, and `ephemeral` is the correct choice for it — nothing in
that cache is authoritative — so `none` is genuinely a third case rather than a
rename of one that already exists.

This change's reserved requirement id for the ephemeral-default defect is
therefore dropped; see the proposal.

### 8. Multi-replica: what becomes possible, and what still must not be assumed

On a filesystem backend the gate is unconditional, and on EFS that matters more
than it looks: an RWX volume will happily let a second pod mount and write, so
nothing but the replica count stands between the estate and a lost ref update.
The chart must refuse `replicaCount > 1` on `filesystem` outright rather than
trusting the operator to know that.

The object-storage backend removes the *storage* obstacle to `replicaCount > 1`:
readers are safe by construction (immutable packs, manifest re-read), and
concurrent writers are serialized by the conditional write. Facade fetches,
portal requests and API reads can be spread across replicas.

What it does **not** make safe is everything in the gateway that is a scheduled
singleton with no coordination: the upstream sync sweep, the re-vetting sweep,
retention deletion and compaction, the webhook dispatch poller and the
audit-export poller. Running those on N replicas means N concurrent sweeps, N
webhook deliveries, and N exporters advancing the same cursor. None of that is
storage's problem and none of it is fixed here.

So the chart gates the two together: `replicaCount > 1` is accepted only when
the backend is `object-store` **and** the background pollers are off on the
scaled-out replicas — they already have switches (`sync.enabled`,
`vetting.revet.enabled`, `retention.enabled`, `webhooks.enabled`,
`auditExport.enabled`). The honest shape is one worker deployment with the
sweeps on and one serving deployment scaled out with them off, sharing the
bucket. Leader election that removes that split is separate, later work, and
this design should not pretend otherwise.

### 9. Conditional-write support is the portability boundary, and the test double must be proved to have it

Decision 3 buys portability by requiring only one exotic primitive — a
conditional write — instead of a lock service or DynamoDB. The price of that
choice has to be stated as a constraint rather than left as an open question:
**the set of object stores that implement conditional writes is exactly the set
of stores this backend can run on.** There is no degraded mode. A store without
`If-Match` cannot be supported by weakening the model, because last-writer-wins
on the manifest is precisely the lost update the design exists to prevent.

Support, with the evidence behind each row:

| Store | Conditional write | Status |
| --- | --- | --- |
| Floci 1.5.33 (`docker.io/floci/floci`) | `If-Match` / `If-None-Match` on PUT | **Verified here** by the task 2.1 spike — all five assertions pass, and two mutations confirm the spike would have caught a store that ignores preconditions |
| AWS S3 | `If-Match` / `If-None-Match` on PUT | Documented; the primary target. **Still not exercised by us** — no account. Deferred out of this change and tracked as [#151](https://github.com/skillsgateway/skillsgateway/issues/151) |
| MinIO `RELEASE.2025-07-23T15-54-02Z` | `If-Match` / `If-None-Match` on PUT | **Probed and passing** — all five assertions, three consecutive runs, and the same two mutations fail it. Kept as evidence that the primitive is portable beyond one emulator; **not adopted as a test store**, because MinIO stopped publishing free images around October 2025 |
| Google Cloud Storage | generation preconditions, not `If-Match` | Would need an adapter; **out of scope** |
| Ceph RGW, on-prem S3 gateways | varies by version | **Unverified** |

The gateway probes the configured bucket at startup (decision 1) and refuses to
run where the probe fails, so an unsupported store is a startup error rather
than a corruption discovered during an approval. The supported-store list is a
documentation obligation of this change, not a footnote.

**The local test double is Floci** (`docker.io/floci/floci`), an open-source AWS
emulator positioned as an alternative to LocalStack Community, reached through
the Floci project's own `FlociContainer` Testcontainers module, pinned to
`1.5.33`.

**Through the Arconia Floci dev service**, per the project rule that a
container-backed test uses a dev service wherever one exists, so that one
container serves both `bootRun` and the suite.
`io.arconia:arconia-dev-services-floci` has existed since Arconia 0.27.0 and is
in the 0.29.0 BOM this project already imports. The spike takes its `S3Client`
straight from the context: Spring Cloud AWS auto-configures it from the
`AwsConnectionDetails` that Arconia's `FlociAwsContainerConnectionDetailsFactory`
publishes, and Arconia's own `S3ClientCustomizer` supplies path-style
addressing. The test names no image, no port and no URL.

**A correction, recorded because the wrong answer was believed for a while.** An
earlier revision of this design claimed the dev service was unusable — that
putting Spring Cloud AWS on the classpath made awspring's
`CredentialsProviderAutoConfiguration` fail every Spring context in the project
for want of an `AwsRegionProvider`, at a measured cost of the entire gateway
suite, and that the only escapes were to configure Spring Cloud AWS for real or
to exclude its auto-configurations. That was wrong, and the error was a
dependency choice, not an incompatibility.

`FlociDevServicesAutoConfiguration` is
`@ConditionalOnClass(io.awspring.cloud.autoconfigure.core.AwsConnectionDetails,
software.amazon.awssdk.awscore.AwsClient)`. `AwsConnectionDetails` lives in
`spring-cloud-aws-autoconfigure`, so adding exactly that artifact satisfies the
condition and looks correct — but it does not bring `spring-cloud-aws-core`, so
awspring's `RegionProviderAutoConfiguration`
(`@ConditionalOnClass(io.awspring.cloud.core.region.StaticRegionProvider, …)`)
is silently skipped, while `CredentialsProviderAutoConfiguration` — conditioned
only on AWS SDK classes — still activates and takes `AwsRegionProvider` as a
constructor parameter. The bean never exists, and every context fails. The
failure names awspring rather than the dev service, which is why it read as an
incompatibility.

With a starter (`spring-cloud-aws-starter-s3`) both halves are present and the
problem does not arise. Verified by running the full suite both ways: green with
the starter, and green with **no** AWS configuration at all — no static region,
no credentials. Region resolution never even happens, because
`DefaultAwsRegionProviderChain` only resolves when `getRegion()` is called and
nothing here builds a client that calls it; the bean merely has to exist.
Reported upstream as
[arconia-io/arconia#281](https://github.com/arconia-io/arconia/issues/281),
suggesting the docs name the artifact and that the dev service condition on a
`spring-cloud-aws-core` class so an incomplete classpath leaves it inactive
rather than breaking the application.

The dependency is `test` scope rather than `optional` like the Postgres dev
service, for the one reason that survives from the earlier analysis: no
application code touches object storage yet, so there is no `bootRun` consumer
to share a container with. Flipping it to `optional` belongs with the backend
that creates one.

But a test double is exactly the wrong thing to trust here, and this is not
ordinary test-double caution. The entire correctness argument of this design
reduces to one behaviour: a conditional PUT whose precondition no longer holds
must fail with `412 Precondition Failed` rather than succeeding. An emulator
that accepts every PUT and ignores `If-Match` would make a *broken*
implementation pass its concurrency tests — including the lost-update test that
is the whole point — and would do so silently and green. The test double would
be certifying the one property it does not implement.

So the first task of the object-store work is a fidelity spike against Floci,
before any backend code exists, asserting:

- a conditional PUT with a matching ETag succeeds and returns a new ETag;
- a conditional PUT with a stale ETag returns **412**, and the stored object is
  unchanged;
- `If-None-Match: *` creates exactly once and returns 412 on the second attempt;
- under N concurrent writers from the same base ETag, exactly one succeeds — the
  first-writer-wins property the manifest transition depends on.

**Outcome of the spike (task 2.1, run against Floci 1.5.33).** All five
assertions pass, repeatably. A conditional PUT with the current ETag succeeds and
returns a new one; a stale ETag is refused with `412` *and the stored object is
byte-for-byte unchanged afterwards*; `If-None-Match: *` creates exactly once;
eight threads racing off one barrier from a single base ETag produce exactly one
winner and seven `412`s, with no other error; and the ETag a conditional PUT
returns is the one the next conditional PUT must present, across five successive
generations, with each superseded ETag ceasing to be accepted.

Because a green test against a double proves nothing on its own, the spike was
also mutated twice to confirm it can fail. Removing `If-Match` from the stale
write makes assertion 2 fail (`Expecting code to raise a throwable`), proving the
`412` is caused by the precondition rather than by the emulator refusing writes
generally. Removing `If-Match` from the concurrent writers makes assertion 4
report **8 winners instead of 1** — precisely the signature of a store that
ignores preconditions, and precisely what the suite must catch. The double
discriminates.

So the fallback in task 2.2 is **not** triggered: the conditional-write contract
stays in the default `verify`, the offline build stays offline, and no upstream
contribution is needed. Had any assertion failed, the fallback was to run the
contract as a separate tagged suite against real S3 and to consider contributing
the behaviour upstream. What was never acceptable was running the concurrency
suite against a double that cannot fail it.

One caveat worth keeping honest: this verifies *Floci*, not AWS S3. The spike
raises confidence that the design is implementable and that our tests are
meaningful; it does not by itself certify the production target. Exercising the
same contract against real S3 remains open, deferred to [#151](https://github.com/skillsgateway/skillsgateway/issues/151).

**Task 2.3, half-closed: MinIO verified, AWS S3 still not.** The five assertions
were re-run, unchanged in substance, against MinIO
`RELEASE.2025-07-23T15-54-02Z` — a real MinIO server, not an emulator of one.
All five pass, three consecutive runs, and the two mutations that discriminated
Floci discriminate MinIO identically: dropping `If-Match` from the stale write
turns the expected `412` into `200`, and dropping it from the eight concurrent
writers turns one winner into eight. So the MinIO row moves from believed to
verified, and it is now the *second* independent store the design's one exotic
primitive has been shown to hold on.

The AWS S3 row stays open, and deliberately so: verifying it needs an AWS
account, and acquiring one is out of scope for this change. That row closes when
someone with credentials runs the same assertions; until then the supported-store
list names S3 as documented-but-unexercised rather than verified.

Two consequences worth recording:

- **The MinIO run stays a probe, and MinIO is not adopted as a second in-build
  store.** It was an out-of-band program driving the same five assertions
  against a hand-started container, and it stays that way. The reason is not the
  result — the result was good — but the supply: **MinIO stopped publishing free
  container images around October 2025**, so any pin would be to a frozen tag
  with no upgrade path and no guarantee the tag remains hosted. Building a gate
  on an image whose distribution has already stopped buys a test that fails
  later, without warning, for reasons unrelated to this code. There is also no
  Arconia dev service for MinIO in the 0.29.0 or 0.30.0 BOMs, so adopting it
  would additionally have meant either a hand-rolled container or writing a dev
  service against Arconia's registrar — both viable, neither worth doing for a
  store on a frozen image.
- **Floci is the single in-build store.** It has a shipped dev service, so one
  container serves `bootRun` and the suite. The portability question that a
  second store would have answered is answered instead by **real AWS S3** in
  [#151](https://github.com/skillsgateway/skillsgateway/issues/151), which is the store that actually matters and the row that keeps the
  supported-store list unpublishable until it passes.

One inconsistency the probe did surface, and it is a client bug rather than a
store one: the first MinIO run failed assertion 5 with
`NoHttpResponseException` — a connection the Apache client had pooled during the
eight-way concurrency test and MinIO had since closed. Rebuilding the `S3Client`
per assertion fixed it. It is worth remembering for the backend: a long-lived
`S3Client` against a store that closes idle connections needs a connection TTL
below the store's idle timeout, or the first request after a quiet period fails
in a way that will read as a storage fault.

### 10. The ref database is a plain `DfsRefDatabase` over the manifest — accepted

Task 2.4's open question — `DfsReftableDatabase` over the bucket, or a plain
`DfsRefDatabase` over the manifest — was researched against the JGit 7.7.1
classes actually on the classpath
(`org.eclipse.jgit-7.7.1.202607240634-r.jar`, and the matching sources jar).
**Accepted: the plain `DfsRefDatabase`.** Reviewed independently before
acceptance; that review confirmed the JGit claims below against the classpath,
corrected three of them (the withdrawn staleness argument at point 3, the
garbage-collector coupling at point 5, and the `DfsObjDatabase` method names in
decision 2), and added the last five consequences at the end of this decision. The reasoning is below, as
is what it costs, because the decision is not free in either direction and the
owner should see both halves before any backend is built on it.

**What each option actually is, in this JGit version.**

`DfsRefDatabase` is an abstract class with exactly three abstract methods:

```java
protected abstract RefCache scanAllRefs() throws IOException;
protected abstract boolean compareAndPut(Ref oldRef, Ref newRef) throws IOException;
protected abstract boolean compareAndRemove(Ref oldRef) throws IOException;
```

That is a compare-and-swap interface. It is the same shape as the manifest
transition decision 3 already commits to, down to the boolean return meaning
"your precondition still held". `scanAllRefs` is one GET of the manifest;
`compareAndPut` and `compareAndRemove` are one conditional PUT of it.

`DfsReftableDatabase` extends that same class and implements the three methods
in terms of reftables — and the important discovery is *where reftables live*.
`DfsReftableBatchRefUpdate.applyUpdates` does:

```java
DfsPackDescription pack = odb.newPack(PackSource.INSERT);
try (DfsOutputStream out = odb.writeFile(pack, REFTABLE)) { ... }
odb.commitPack(Collections.singleton(pack), prune);
odb.addReftable(pack, prune);
```

Reftables are **pack files** (`PackExt.REFTABLE`), so choosing reftable does not
move ref state out of our code — it moves it out of the manifest and into the
pack list, and the conditional write then has to guard `commitPackImpl` instead.
The consistency mechanism is unchanged; only what it protects moves.

**Why the plain `DfsRefDatabase` wins here.**

1. *The interface is the design.* `compareAndPut(old, new) -> boolean` is the
   manifest transition, expressed in JGit's own vocabulary. With reftable there
   is an encoding, a stack, a compaction policy and a pack-list commit between
   the gateway's intent and the conditional PUT, and every one of those is a
   place a correctness argument can hide.
2. *The manifest stays self-describing.* Decision 3's stated property — "one
   bucket is a complete, self-describing repository", the thing that makes a
   replica disposable — is much weaker if reading the current refs means
   decoding a stack of binary reftable blocks. On the manifest, an operator
   answering "what is this marketplace serving right now, and is it the SHA the
   database says was approved?" does one `GetObject` and reads JSON. On a trust
   boundary whose whole guarantee is *the served ref is the approved SHA*, that
   is not a convenience; it is the auditability of the guarantee.
3. *Staleness is a wash, and an earlier revision of this decision claimed
   otherwise.* Both options cache — `DfsRefDatabase` holds an
   `AtomicReference<RefCache>`, `DfsObjDatabase` a cached `PackList` — so a
   facade read path needs a freshness policy either way. This decision
   previously argued that reftable made the check a bucket `LIST` while the
   manifest made it an `O(1)` conditional `GET`. **That was a false dichotomy:**
   nothing in JGit forces `listPacks()` to be a bucket listing, and since the
   manifest already holds the live pack set, a reftable implementation over the
   same manifest would refresh with the same single conditional GET. The
   argument is withdrawn; the recommendation does not need it, and keeping a
   weak argument invites a reviewer to discount the strong ones.
4. *Our ref population is tiny, so reftable's advantage does not apply.* The
   gateway's namespaces are `refs/heads/main` and `refs/snapshots/<sha>` (plus
   `refs/quarantine/incoming` and `refs/catalog/*`, neither of them served).
   Reftable exists for Gerrit-scale repositories with millions of refs, where a
   whole-ref-map rewrite per transition is untenable. Rewriting a few hundred
   refs as one small object per transition is not. Choosing reftable buys
   scaling we have no use for and pays for it in machinery.
5. *Compaction machinery is not lost by choosing the manifest.*
   `DfsGarbageCollector`'s reftable machinery (`setReftableConfig`,
   `convertToReftable`, `writeReftable`, `hasGcReftable`) is **opt-in**, gated
   on a `reftableConfig` that is null by default — exactly like the compactor's.
   An earlier revision of this decision said GC "has no reftable coupling at
   all", which is simply wrong; the operative conclusion is unchanged, but the
   claim was not. And
   `DfsPackCompactor`'s reftable compaction is opt-in — guarded by
   `reftableConfig != null && !srcReftables.isEmpty()`. Task 6.6 keeps both
   either way.
6. *Reftable adds a second compaction obligation.* Every ref transition writes a
   new reftable file. Without stack compaction the read path degrades with the
   number of transitions — a distinct failure mode from the pack-count one task
   6.6 already has to handle, on a different schedule, with its own metric.

**What choosing it costs, stated plainly.**

The one real thing reftable gives away for free is atomic multi-ref
transactions, and this is the strongest argument for the other side. Today's
`RefDirectory` returns `performsAtomicTransactions() == true`;
`DfsReftableDatabase` returns `true`; `RefDatabase`'s default — what a plain
`DfsRefDatabase` inherits — is `false`. `ReceivePack` advertises the `atomic`
push capability only when the ref database reports `true`, and
`GitPublishConfiguration` already sets `receivePack.setAtomic(true)` on the
hosted-marketplace push path. So a naive plain `DfsRefDatabase` would silently
stop advertising `atomic` to publishers pushing to a hosted marketplace on the
object-store backend: an observable, client-visible difference between the two
backends, which is exactly what the task-3 contract suite exists to forbid.

That is a cost, not a blocker, and the fix is small and belongs in the plan
rather than being discovered later: our subclass overrides
`performsAtomicTransactions()` to `true` and `newBatchUpdate()` to return a
batch update that evaluates the whole `ReceiveCommand` list against one manifest
read and commits it as one conditional write. That is the same single-transition
mechanism `unpublish` already needs (task 6.4), applied to N refs instead of
two — which also answers task 2.6, and answers it the same way whichever option
is chosen.

Worth noting, because it removes the tempting assumption that reftable would
have handled this for us: it would not have.
`ReftableBatchRefUpdate.execute` serialises its precondition check and its write
with `lock.lock()` on a plain in-JVM `ReentrantLock`. Across pods that is
nothing. Reftable's `performsAtomicTransactions() == true` is a statement about
*all-or-nothing within one process*, not about cross-writer isolation. Under
reftable the conditional write in `commitPackImpl` would still be the only real
serialization point, and a conflict would surface *after* a reftable had already
been written and would need pruning. Under the manifest, a conflict is a
re-read, a re-evaluation, and a retry, with nothing written to clean up. For a
system whose whole point is uncoordinated concurrent writers, that is the more
honest structure.

**Consequences to carry into implementation** (task 6.3), if the recommendation
stands:

- Our `DfsRefDatabase` subclass overrides `performsAtomicTransactions()` and
  `newBatchUpdate()`; a contract-suite case asserts the `atomic` capability is
  advertised identically on both backends, so this cannot regress unnoticed.
- `scanAllRefs()` reads the manifest; the returned `RefCache` carries the
  manifest ETag so a `compareAndPut` knows precisely which version it is
  swapping from.
- Symbolic `HEAD -> refs/heads/main` is part of the manifest, not a separate
  object, so repository creation is one conditional `If-None-Match: *` PUT — the
  create-exactly-once primitive assertion 3 of the fidelity spike verified on
  both Floci and MinIO.
- Retry on a lost CAS is ours to write and ours to bound, with the
  conflict/retry metrics task 9.1 already calls for.

- **The precondition JGit hands us is per-ref; the one the store enforces is
  per-repository. That mismatch must be absorbed inside `compareAndPut`, not
  leaked to the caller.** `compareAndPut(oldRef, newRef)` asks "is this ref still
  what I read?"; the manifest ETag asks "is this *repository* still what I read?"
  Two writers touching different refs — an approval writing `refs/snapshots/A`
  and `main` while retention deletes `refs/snapshots/B` — both hold valid
  ref-level preconditions, but one loses the ETag race. Returning `false` there
  is wrong: `DfsRefUpdate.doUpdate` turns it into `LOCK_FAILURE`, JGit does not
  retry, and the caller sees a spurious failure for an update that should have
  succeeded. So `compareAndPut` re-reads the manifest on 412, re-checks the
  *ref-level* precondition against the fresh state, and re-PUTs; it returns
  `false` only when the ref itself moved. Bounded, and with the retry counted.

  This is also why the contract suite must assert **result codes**, not merely
  end states. `FilesystemGitStorage.deleteRef` ignores the `RefUpdate.delete()`
  result today; carried onto the object store, a swallowed `LOCK_FAILURE`
  becomes a silent unpublish failure — on the revocation path, which is the last
  place a silent failure belongs.

- **Cross-replica revocation needs a stated bound, and today has none.**
  `DfsRefDatabase` caches its `RefCache` until `clearCache()`. With one pod and
  `RefDirectory` that was invisible; with N replicas over a shared bucket, an
  unpublished snapshot stays advertised and fetchable from every replica that has
  not re-read the manifest. Revocation latency therefore becomes a property of
  the cache policy, and an unstated policy is an unbounded one. The freshness
  check is a conditional GET of the manifest (`If-None-Match` on the cached ETag)
  before serving a ref advertisement — `O(1)`, and cheap enough to do per
  advertisement. The bound must be written down and a test must assert that a
  snapshot revoked on one replica stops being served by a *different* replica
  within it. This is a trust-boundary property, not a performance tuning knob.

- **Pack deletion needs a grace period.** GC and compaction delete pack objects;
  a replica part-way through `upload-pack` streaming from a pack that has just
  been deleted gets a 404 mid-stream. Delete only packs unreferenced by any
  manifest version for longer than the maximum plausible fetch duration.

- **Approve-time ordering between PostgreSQL and the bucket must be chosen and
  named.** A crash between the database write and the manifest flip leaves either
  "approved but never served" or "served but not recorded" — the same estate
  inconsistency this change criticises elsewhere, in miniature. Pick the order,
  and name the reconciliation that repairs it (the startup check that verifies
  the served estate against the database generalises to this).

- **What the fidelity spike does *not* cover**, so the backend's own concurrency
  suite does: a conditional PUT racing a DELETE (unpublish deletes), read-after-CAS
  visibility from a *different* client and connection (the multi-replica read
  path), agreement between `headObject` and `getObject` ETags (the spike reads
  the current ETag with `headObject`), and ETag semantics under SSE-KMS or bucket
  versioning on real S3 — where the ETag is not a content hash. Chaining the
  returned ETag stays correct there; ever *computing* an expected ETag would not.

**Reversibility.** This is not a one-way door. Both options implement the same
three abstract methods behind the same `GitStorage` seam, and the task-3
contract suite is written before either. Moving to reftable later would be a
rewrite of the ref database and a bucket-layout migration — real work, but
confined behind the seam and covered by a suite that already exists.

*Recommendation only.* This decision is deliberately not implemented in this
pass; task 6.3 stays blocked on the owner accepting or rejecting it.

## Risks / Trade-offs

- **A wrong DFS implementation corrupts the one thing the product guarantees**
  (served content is the approved SHA and nothing else) → the trust-boundary
  path: adversarial and negative tests written and proved failing before
  implementation, concurrent publish/unpublish races asserted against the
  manifest, and the existing facade and approval SVC suites run unchanged
  against both backends from one parameterized contract test. `unpublish` gets
  explicit tests for both branches of the "is main still this SHA" question
  under contention.
- **Eventual consistency, or a store without conditional writes** → strong
  read-after-write and conditional PUT are stated preconditions, probed at
  startup against the configured bucket; a store that fails the probe refuses to
  start rather than the gap being discovered during an approval.
- **Cold start and per-fetch latency regress against a warm volume** → bounded
  local pack cache plus `DfsBlockCache`; measured and published rather than
  claimed, and `filesystem` stays the default and the recommendation for
  single-node deployments.
- **WAL and small-pack accumulation degrade restore and read performance** →
  compaction from the start, itself conditional-write-guarded, with metrics for
  WAL depth and pack count.
- **A new hard dependency (an S3 SDK) in a GraalVM native image** → a
  well-trodden native-image target, but it must be proved in the native profile
  in the same PR as the implementation, not assumed. The web-identity credential
  provider is the part most likely to need reflection configuration, and it is
  the part Fargate cannot do without.
- **Credential expiry mid-request on Fargate** (web-identity credentials are
  short-lived and refreshed by the SDK) → an explicit test that a long
  `upload-pack` survives a refresh, rather than discovering it as an
  intermittent facade failure.
- **The DFS extension points are `internal` JGit API, and Renovate will keep
  upgrading JGit under us.** `org.eclipse.jgit.internal.storage.dfs` is not a
  stability-guaranteed public API — the `internal` in the package name is real,
  and JGit changes it between releases in ways that never affect ordinary
  `Repository` / `UploadPack` use. We are on 7.7.1 (the current release), the
  version is a plain `<jgit.version>` property in `pom.xml`, and
  `.github/renovate.json5` means bumps arrive as automated PRs. A subtly broken
  DFS backend after an unattended upgrade is a realistic failure mode →
  mitigation on both sides: a Renovate `packageRules` entry that separates
  `org.eclipse.jgit*` from the general dependency stream so a JGit bump is
  always a deliberate, individually reviewed PR rather than part of a group;
  **and** a backend test suite (task 3's shared contract plus the concurrency
  suite) thorough enough that such a PR goes red loudly instead of passing.
  Pinning alone is not the mitigation — a pin only defers the upgrade, it does
  not tell us when the upgrade breaks us.
- **A test double that does not implement conditional writes would certify a
  broken backend** → decision 9: the emulator's `If-Match` fidelity is proved
  by a spike before any backend code is written, with a named fallback.
- **The bucket becomes a publishing path that bypasses `ApprovalService`** →
  documented as a trust boundary with a narrow bucket policy prescribed; the
  gateway cannot enforce this, and the docs must not imply it can.
- **Two backends is twice the surface to keep correct** → one shared contract
  test suite is both the mitigation and the acceptance criterion: a backend that
  does not pass it is not a backend.

## Migration Plan

1. Ship the backend with `filesystem` still the default — an upgrade is a no-op.
2. Ship the chart change (explicit `persistence.mode`) with release notes
   calling out the deliberate break and the one-line fix.
3. Operators moving to object storage: scale to zero, run the migration command,
   verify, flip `storage.backend`, start, verify the served estate.
4. Rollback at any point before the volume is discarded: flip the property back.
5. Only once a deployment is on `object-store` does `replicaCount > 1` become
   available, under the gate in decision 8.

## Open Questions

- **Answered by decision 10, pending the owner.** The ref database sits on a
  plain `DfsRefDatabase` over the manifest rather than on
  `DfsReftableDatabase`. Researched against the JGit 7.7.1 classes on the
  classpath, not from memory. Task 6.3 stays blocked until this is accepted or
  rejected.
- WAL granularity: one entry per ref transition, or per publication? Affects
  compaction frequency, not correctness.
- **Largely answered by decision 10.** The hosted-marketplace `receive-pack`
  path needs no mechanism beyond the same conditional write: the whole
  `ReceiveCommand` list is evaluated against one manifest read and committed as
  one conditional PUT. Task 2.6 remains open only as the *demonstration* of
  that, which belongs with the backend.
- The conditional-write table in decision 9 now has two verified rows, Floci and
  MinIO. **AWS S3 and Ceph RGW are still believed rather than probed**, and S3
  is the production target — verifying it needs an account this change does not
  have and must not acquire. Until that row closes, the supported-store list is
  not publishable as verified.
- Is the migration command a subcommand of the same jar, or a separate
  entrypoint? Preference is the former (one artifact), unconfirmed against the
  native-image packaging.
