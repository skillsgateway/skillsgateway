## Why

`ApprovalService.approve` moves `refs/heads/main` with `RefUpdate.forceUpdate()`
and throws the returned `Result` away. `forceUpdate` does not throw on refusal —
`LOCK_FAILURE`, `IO_FAILURE` and `REJECTED` are returned values — so a refused
publication falls through the success path and is **masked end to end**: the
snapshot row is `approved`, the ledger records `snapshot-approved`, waiver use is
recorded, and the `snapshot.approved` webhook fires at external subscribers,
while the served ref never moved. The gateway claims to serve content it is not
serving, which is the inverse of the guarantee the approval gate exists to
provide ([#149](https://github.com/skillsgateway/skillsgateway/issues/149)).

It is worse than "the ref never moved". The publish first fetches
`+refs/snapshots/<sha>:refs/snapshots/<sha>` into the **published** repository,
and that ref is advertised by upload-pack in its own right — `GitStorage.unpublish`'s
contract says so, and the facade sets no `RefFilter` or `AdvertiseRefsHook`. So a
refused update leaves the snapshot *partially served*: fetchable by SHA, not
reachable through `main`. `decide()` additionally clears `revoked_at`,
`revoked_by` and `violation`, so a failed re-publish of a revoked snapshot erases
the revocation stamps from the row while the ledger keeps them.

Three other sites discard a ref-update result the same way, and two of them are
not benign:

- `RetentionService` purge order is `removePin` → database `purge` → the
  `snapshot-purged` ledger entry. A refused pin delete leaves the pin while the
  row is gone, so the pass never revisits it: content is retained **forever**,
  garbage collection reclaims nothing, and the ledger asserts a deletion that did
  not happen.
- `CatalogService.pruneInternalRefs` deletes `refs/catalog/*` from the catalog
  repository, which lives in *published* storage and is served by the ordinary
  facade. A refused delete leaves those refs advertised, and after a later
  revocation can keep a revoked tip fetchable from the catalog repository even
  though `unpublish` scrubbed the marketplace's own repository. The method's own
  javadoc states the invariant the discarded result fails to enforce.
- `IngestionService` pins `refs/snapshots/<sha>` in quarantine, which is never
  served. A refused pin leaves a vetted, reviewable row whose approval will fail
  at fetch time, and whose objects garbage collection can later reclaim.
- `CatalogService.rebuild` moves the catalog `main`. A refused move makes
  `POST /api/catalog/rebuild` report a sha the facade does not serve; it
  self-heals on the next successful rebuild, so it is reported rather than
  guarded by a new requirement.

The checked pattern already exists in-tree — `StorageMigration` and
`FilesystemGitStorage.deleteRef` both validate the result against an allowed set
and throw — so the codebase is inconsistent rather than uniformly wrong. This
change makes the checked pattern the rule and names the guarantee, which nothing
currently states.

`0.1.0` is tagged but unpublished, so there is no deployed consumer of the
current behavior.

## What Changes

The narrow fix — check the result at each site — detects the inconsistency but
leaves the window that creates it. Publication is not a seam operation at all:
callers take a raw `Repository` from `published(name)` and perform their own
`git.fetch()` and `RefUpdate`, so putting a snapshot on the wire is two
unsynchronized ref writes, while taking the same pair off is one atomic manifest
transaction inside `unpublish`. This change closes that asymmetry.

- **Publication becomes a `GitStorage` operation, the exact inverse of
  `unpublish`.** `publish(marketplace, sha)` lands `refs/snapshots/<sha>` and
  `refs/heads/main` as one all-or-nothing transition, so both refs appear or
  neither does. Partial serving stops being a state the system can reach, rather
  than one it detects afterwards. On the object-store backend this gives
  publication the compare-and-swap retry and atomicity that revocation already
  has; `GW_0112`'s existing clause — a transition that did not take effect is
  raised rather than reported as done — then covers publication too, and the
  storage contract suite gains it as a tested transition on both backends.
- **The facade advertises an explicit allowlist.** `UploadPack` currently gets no
  `RefFilter` and no `AdvertiseRefsHook`, so it advertises every ref the
  repository happens to hold. Advertisement becomes `refs/heads/main` and
  `refs/snapshots/*` and nothing else. This is what lets object transfer stage
  into an unadvertised namespace before the atomic transition, and it
  independently closes the `refs/catalog/*` leak class below.
- **`ApprovalService` calls the seam and repairs the row on failure.** With the
  transition atomic there is no copied ref to unwind, so the failure path reduces
  to returning the snapshot row to the state it held before the attempt — via a
  new state-guarded transition that restores the `revoked_at`, `revoked_by` and
  `violation` values `decide()` clears. Deliberately not `revoke()`: a failed
  publish is not a policy revocation and must not be recorded as one.
- **The remaining raw ref updates are checked.** `CatalogService.pruneInternalRefs`,
  `CatalogService.rebuild`, `IngestionService`'s pin and `RetentionService`'s pin
  delete keep operating on their own repositories, but through a shared checked
  helper that states the allowed-result sets once — extracted from the two
  in-tree copies in `StorageMigration` and `FilesystemGitStorage.deleteRef` — so a
  fifth call site cannot reintroduce the bug quietly.
- **New requirements** in `docs/reqstool/`, one per affected capability, with
  adversarial verification cases: `GW_0132` (publication is one atomic, verified
  transition on every backend), `GW_0133` (an approval reports success only when
  publication took effect, and a refusal leaves nothing published and the row as
  it was), `GW_0134` (the facade advertises only the refs it serves), `GW_0135`
  (the served catalog repository retains no internal scaffolding refs), `GW_0136`
  (a purge reports deletion only when the pin is gone) and `GW_0137` (ingestion
  reports a pinned snapshot only when it is pinned).

Not breaking: every change is a refusal where the system previously reported a
false success, or a narrowing of what upload-pack advertises to what the facade
already documents as served. No API shape, configuration key or payload changes.

## Capabilities

### New Capabilities

None. This change adds requirements to existing capabilities rather than
introducing a new one.

### Modified Capabilities

- `git-storage`: `GW_0132` — publication joins the seam as the inverse of
  revocation, one atomic verified transition with the same guarantee on both
  backends.
- `snapshot-approval`: `GW_0133` — an approval reports success only when
  publication took effect; a refusal leaves nothing published and no row claiming
  otherwise.
- `git-facade`: `GW_0134` — advertisement is an explicit allowlist rather than
  whatever the repository happens to hold.
- `virtual-catalog`: `GW_0135` — the served catalog repository retains no internal
  scaffolding refs.
- `snapshot-retention`: `GW_0136` — a purge reports deletion only when the pin is
  gone.
- `marketplace-ingestion`: `GW_0137` — ingestion reports a pinned snapshot only
  when it is pinned.

## Impact

**Code**

- `storage/GitStorage.java` — the new `publish` operation on the seam
- `storage/FilesystemGitStorage.java`, `storage/objectstore/ObjectStoreGitStorage.java`
  — its two implementations, and the object-store manifest transaction
- `facade/GitFacadeConfiguration.java` — the advertisement allowlist
- `approval/ApprovalService.java` — calls the seam; failure repairs the row
- `persistence/SnapshotRepository.java` — a new state-guarded transition back to
  `held` that restores the revocation stamps
- `retention/RetentionService.java`, `catalog/CatalogService.java`,
  `ingestion/IngestionService.java` — checked through the shared helper
- the storage contract and concurrency suites — publication as a tested transition
- `docs/reqstool/requirements.yml`, `software_verification_cases.yml`

**Behavior**

An approval that would previously have reported success while publishing nothing
now fails with the snapshot still held. Operators of a filesystem single-writer
deployment are unlikely to have seen the masked failure; on the object-store
backend a lost compare-and-swap is an ordinary outcome, which is why this matters
more there.

**Documentation**

`docs/manual/guides/approving-snapshots.md` gains the failure mode; the storage
guide's multi-replica section gains the ordinary-conflict case.

**Explicitly out of scope**

`GitFacadeConfiguration` hardcodes `refs/heads/main` in the fetch-audit line even
when the `want` was a snapshot ref, so the by-SHA fetches this change is
concerned with are misrecorded in the fetch log. It is a real defect on a
different surface (audit fidelity, not publication integrity) and gets its own
issue rather than a drive-by fix here — the same reasoning that kept #149 out of
the concurrency change that found it.
