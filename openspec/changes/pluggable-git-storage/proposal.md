# Proposal: pluggable-git-storage

Tracked as issue [#127](https://github.com/skillsgateway/skillsgateway/issues/127).

## Why

Every byte the gateway serves lives on one filesystem behind one pod.
`GitStorage` was written as the seam for exactly this moment — its javadoc
already says "A JGit-DFS implementation over object storage replaces this
interface on the roadmap (ARCHITECTURE.md §12) without touching callers" — and
`FilesystemGitStorage` is still its only implementation. Two consequences are
already costing us:

- **The default deployment silently loses served content.** The chart mounts
  `/data` as an `emptyDir` unless `persistence.existingClaim` is set. On pod
  restart the published repositories are gone while PostgreSQL still records
  those snapshots as `approved` — an estate that says it is serving content it
  cannot serve, with no rehydration path, because `ApprovalService.approve` is
  the only code allowed to publish and nothing re-runs it. The docs warn about
  this in prose; the chart still does it by default.
- **The gateway cannot scale past one pod.** The filesystem design assumes a
  single writer with no cross-pod locking, so `replicaCount` must stay 1.
  ADR 0008 made the facade the only serving surface and called its availability
  a security property — "developers cannot install and CI cannot build without
  it" — while leaving it a single point of failure by construction.

And the first real deployment target makes it sharper still:

- **On EKS Fargate there is no good filesystem to fall back to.** Fargate cannot
  mount EBS at all — the AWS documentation is explicit ("You can't mount Amazon
  EBS volumes to Fargate Pods"; the comparison table answers "Can use Amazon EBS
  storage with Pods: No"), and there is no workaround, because the EBS CSI node
  component is a DaemonSet and DaemonSets are unsupported on Fargate. The one
  supported filesystem is EFS, statically provisioned ("You can't use dynamic
  persistent volume provisioning with Fargate nodes, but you can use static
  provisioning") — that is, NFS. NFS is the substrate git is worst on: packfile
  access is a random walk over large binary files, which is fine in a page cache
  and slow over a network filesystem. So on serverless Kubernetes, object
  storage is not a scaling nicety; it is the only substrate that suits git.
- **EFS is also RWX, so the single-writer assumption stops being enforced.** An
  RWO volume at least makes "one writer" structural. On an RWX EFS volume it is
  held only by the convention `replicaCount: 1` — one careless scale-up and two
  pods force-update `refs/heads/main` through a network filesystem. The
  object-storage design makes concurrent writers safe by construction instead of
  by configuration discipline.

Object storage is what fixes all of this, and the seam to put it behind already
exists.

None of it blocks a proof of concept today: `persistence.existingClaim` is
backend-agnostic, so an EFS-backed PVC works with the chart exactly as it
stands. This proposal is about what production needs, not about unblocking the
first trial.

## What Changes

- **A selectable storage backend.** `skills-gateway.storage.backend` chooses
  `filesystem` (today's behavior, the default) or `object-store`. An
  unrecognised value, or an `object-store` selection missing its bucket or
  credentials, refuses to start — never a silent fallback to a filesystem the
  operator did not ask for.
- **An object-storage `GitStorage` implementation**, built on JGit's `dfs`
  extension points, whose source of truth is a write-ahead log of immutable
  objects in an S3-compatible bucket and whose ref visibility flips by a
  conditional write (compare-and-swap) on a small per-repository manifest. No
  coordination database, no leader election, no lock service.
- **The `unpublish` contract is backend-independent.** Removing
  `refs/snapshots/{sha}` unconditionally, and `refs/heads/main` only while it
  is still that SHA, is the same observable outcome on either backend — and on
  object storage it becomes one atomic manifest transition rather than two
  independent ref deletions.
- **A `none` persistence mode.** ~~The chart stops defaulting to ephemeral
  storage~~ — **done by #134 (GW_0120)** while this was in review: the chart
  already refuses to render without an explicit `persistence.mode`. What is left
  here is the third mode, `none`, valid only on `object-store`, for a deployment
  that keeps no durable volume because the bucket is the repository.
- **`replicaCount > 1` becomes possible, and gated.** The chart accepts more
  than one replica only on a backend that supports concurrent writers, and only
  while the gateway's uncoordinated background singletons (sync sweep,
  re-vetting sweep, retention compaction, webhook and audit-export pollers) are
  not thereby duplicated.
- **A migration path.** An operator on a PVC can move to object storage without
  re-approving anything: an offline one-shot copy of quarantine, hosted and
  published repositories, verified by comparing the resolved ref sets before
  the old volume is discarded.
- Requirements **GW_0111, GW_0112, GW_0114, GW_0115** with the matching SVCs.
  GW_0113 was reserved for "deployment never defaults to storage that loses
  served content" and is **dropped**: #134 landed exactly that as GW_0120 while
  this change was in review. The remaining ids are reserved here; their text
  lands in `docs/reqstool/requirements.yml` (the SSOT) with the implementation,
  as task 1 says.
- **Not in this change:** implementation. This is a proposal for review.

## Capabilities

### New Capabilities

- `git-storage`: the storage seam itself — backend selection and fail-closed
  startup, an object-storage backend whose ref transitions are atomic, the
  backend-independent repository-role and `unpublish` contracts, and a
  verified migration between backends.

### Modified Capabilities

- `release-packaging`: the chart must refuse a replica count the configured
  backend cannot support. (The "must not default to storage that loses served
  content" half of this is already shipped as GW_0120.)

## Impact

- **Backend**: new `dev.skillsgateway.server.storage` implementation and a
  `GitStorage` bean selected by configuration; `SkillsGatewayProperties` gains
  a `Storage` block. `FilesystemGitStorage` is unchanged and stays the default.
  No caller of `GitStorage` changes — that is the point of the seam.
- **Dependencies**: an S3 client (AWS SDK v2 or MinIO) — the first hard
  dependency on an object store; must stay optional at runtime and must not
  break the GraalVM native-image release profile. Credential resolution must
  work **without IMDS**, which Fargate pods do not have: IRSA / web-identity
  federation is a hard requirement, not a nice-to-have.
- **Helm**: a `none` case for the existing `persistence.mode`, a
  `storage.objectStore` block (the service account #134 already added is
  annotated for IRSA; `existingSecret` only as the fallback for stores with no
  role mechanism), and replica gating in the deployment template. No REST
  contract changes, so the API's major does not move, and the chart's
  fail-closed break already shipped with #134.
- **DB**: none. The manifest is in the bucket, deliberately not in PostgreSQL.
- **Testing**: Floci (`docker.io/floci/floci`, pinned `1.5.33`) as the local AWS
  emulator, through the Floci project's own Testcontainers module. Its
  `If-Match` fidelity is proved by a spike *before* any backend code exists — a
  double that ignores conditional writes would let a broken implementation pass
  the very concurrency tests that justify the design. The Arconia Floci dev
  service is the intended end state and is deferred to task 6.8 for a measured
  reason recorded in the design.
- **Portability**: conditional-write support is the hard boundary. The set of
  object stores that implement it is the set this backend can run on; there is
  no degraded mode, and the gateway refuses to start where a startup probe
  fails.
- **Docs** (same PR as the implementation, not this one):
  `reference/configuration.md`, `concepts/lifecycle.md` (the layout table is
  filesystem-shaped), `guides/local-development.md`, a new operations page for
  choosing and migrating backends, and `architecture.md` §12.
- **Trust boundary**: the published repository is the served surface and
  `ApprovalService` is the only publisher — a storage backend that could lose,
  resurrect or reorder a ref transition would break the invariant the whole
  product rests on. old-coder discipline, adversarial tests required.
