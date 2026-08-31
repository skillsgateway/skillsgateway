## Context

`ApprovalService.approve` is the sole publisher and a trust boundary. Its publish
sequence today is three steps with no relationship between them:

```java
Snapshot decided = snapshotRepository.decide(snapshotId, Snapshot.APPROVED, reviewer);  // committed
git.fetch().setRefSpecs(new RefSpec("+refs/snapshots/" + sha + ":refs/snapshots/" + sha)).call();
RefUpdate main = published.updateRef("refs/heads/main");
main.setNewObjectId(ObjectId.fromString(sha));
main.forceUpdate();   // Result discarded
```

`forceUpdate` returns a `Result`; `LOCK_FAILURE`, `IO_FAILURE` and `REJECTED` are
returned, not thrown. A refusal therefore returns normally, and everything
downstream of a returning `approve` fires: `AdminController` writes the
`snapshot-approved` ledger entry, records waiver use, and emits the
`snapshot.approved` webhook to external subscribers. The failure is masked end to
end, not merely un-ledgered.

Three facts make the blast radius larger than the returned code suggests.

1. **The fetch publishes.** `refs/snapshots/<sha>` is copied into the *published*
   repository before `main` moves, and `UploadPack` is constructed with no
   `RefFilter` and no `AdvertiseRefsHook`, so every ref the repository holds is
   advertised and — under the default `RequestPolicy.ADVERTISED` — is a legal
   `want`. `GitStorage.unpublish`'s contract states this explicitly, which is why
   revocation removes both refs. So a refused `main` update leaves the snapshot
   *partially served*: fetchable by SHA, unreachable through `main`.
2. **`decide()` erases revocation.** It clears `revoked_at`, `revoked_by` and
   `violation`. A failed re-publish of a previously revoked snapshot therefore
   erases the revocation stamps from the row while the ledger keeps them.
3. **Publication is the only ref transition outside the seam.** `unpublish` is a
   `GitStorage` method: on the object-store backend it is one manifest
   transaction editing both refs, retried on compare-and-swap conflict, and the
   storage contract suite tests it on both backends. Its inverse is not on the
   seam at all — callers take a raw `Repository` from `published(name)` and do
   their own work. Putting a snapshot on the wire is two unsynchronized writes;
   taking the same pair off is one atomic transaction.

`GW_0112` already requires that a reference transition which did not take effect
be raised rather than reported as done. It is scoped to the storage seam, and
publication is the one transition that escapes it.

Three further sites discard a ref-update result, differing in consequence:
`RetentionService`'s pin delete (purge reports deletion, pin survives, row is
gone, so the pass never revisits it — content retained forever and the ledger
lies), `CatalogService.pruneInternalRefs` (`refs/catalog/*` left advertised in a
repository the facade serves), `IngestionService`'s pin (quarantine only, never
served, but the snapshot's objects lose their retention anchor), and
`CatalogService.rebuild`'s `main` move (misreports a sha the facade does not
serve; self-heals next rebuild).

The checked pattern already exists twice in-tree: `StorageMigration.writeRefs`
against a `WRITTEN` set and `FilesystemGitStorage.deleteRef` against a `DELETED`
set, both throwing on refusal. The codebase is inconsistent, not uniformly wrong.

`0.1.0` is tagged but unpublished, so there is no deployed consumer of the
current behavior.

## Goals / Non-Goals

**Goals:**

- Make partial serving unreachable rather than detected: both published refs land
  or neither does.
- Bring publication under the seam, so `GW_0112` and the storage contract suite
  cover it on both backends and the object-store backend gets the same
  compare-and-swap retry revocation already has.
- Make the served surface an explicit allowlist rather than whatever a repository
  happens to hold.
- Leave no row, ledger entry or webhook asserting a publication that did not
  happen.
- State both guarantees as requirements, verified adversarially.

**Non-Goals:**

- Changing what publication *means*, who may publish, or the approval state
  machine. `ApprovalService` stays the sole publisher.
- Making the approval flow transactional across PostgreSQL and git. The two
  stores stay separate; this change picks the order and names the repair.
- The fetch-audit ref mislabelling in `GitFacadeConfiguration` (a `want` of a
  snapshot ref is logged as `refs/heads/main`). Real, different surface, its own
  issue.
- Verifying conditional-write fidelity against real AWS S3 (#151).

## Decisions

### 1. Publication becomes a `GitStorage` operation, the exact inverse of `unpublish`

`boolean publish(String marketplace, String sha)`, returning whether this call is
what *started* the marketplace serving — mirroring `unpublish`'s return, which
says whether the call is what stopped it, so the caller can say so on the ledger
without re-deriving it.

The two refs are set as one all-or-nothing transition. On the object-store
backend that is one `ManifestStore.transact` with two edits, which is precisely
the shape `unpublish` already uses. On the filesystem backend it is a batched ref
update, with the result of each checked.

*Why over checking the result in `ApprovalService`.* Checking the result detects a
refusal after the snapshot ref is already advertised, and then has to unwind it —
a compensating delete that can itself be refused, on the serving surface, with no
one to compensate the compensation. Atomicity removes the state instead of
repairing it. It also puts the transition where the backend knows how to retry
it: on the object store a lost compare-and-swap is an ordinary outcome that the
manifest layer already retries internally, so the caller-side check would convert
a correctly-detected, retryable conflict into a failed approval.

*Cost.* A new seam method is a new obligation on every future backend, and the
contract suite has to grow. That is the point: the suite is what makes a backend
substitutable, and publication was missing from it.

### 1b. Publication is also *broken* on the object-store backend, which settles where object transfer belongs

Found while implementing decision 1, and it strengthens it. `doApprove` builds
its fetch remote from `quarantine.getDirectory().getAbsolutePath()`. A
`DfsRepository` has no working directory — probed directly against the
object-store backend, `getDirectory()` returns `null` — so real approval on that
backend raises `NullPointerException` before it reaches the ref update at all.

Nothing caught this because **no test anywhere calls `ApprovalService.approve` on
the object-store backend**: the storage contract suite is written against the seam
and simulates publication by writing refs directly, and the object-store suites do
the same. Publication was the one transition with no cross-backend coverage,
which is precisely how it also became the one transition outside the seam.

So object transfer cannot be a path-based fetch performed by the caller. Each
backend has to own how objects move between its own repositories, which is an
independent argument for decision 1 and decides decision 2's shape.

### 2. Object transfer is separated from the ref transition, and stages out of sight

The refspec `+refs/snapshots/<sha>:refs/snapshots/<sha>` cannot be used as the
object-transfer mechanism any more, because it creates the advertised ref as its
side effect — the very thing decision 1 exists to prevent.

Objects are fetched into an unadvertised staging namespace (`refs/staging/<sha>`),
then the atomic transition creates `refs/snapshots/<sha>`, moves
`refs/heads/main`, and drops the staging ref. Object transfer stays generic JGit
across both backends; only the transition is backend-specific.

*Alternatives.* Transferring objects with no ref at all — `ObjectInserter` or
direct pack copy — avoids the staging namespace but replaces a tested JGit path
with bespoke code on a trust boundary. Rejected. Fetching into
`refs/snapshots/<sha>` and relying on it being harmless-if-orphaned is what the
current code effectively assumes, and decision 1 exists because it is not.

*Crash residue.* A staging ref left by a crash is unadvertised and unreachable,
so it serves nothing; it holds objects from garbage collection until removed. The
next publication of the same sha overwrites it, and retention already walks the
published repositories. Sweeping stale staging refs is a task, not a new
mechanism.

### 3. The facade advertises an allowlist: `refs/heads/main` and `refs/snapshots/*`

A `RefFilter` on the fetch facade's `UploadPack`, so advertisement is a stated
set rather than a consequence of what the repository holds.

This is load-bearing for decision 2 (staging must be invisible) and independently
fixes the `refs/catalog/*` exposure: the catalog repository is served by the
ordinary facade under a reserved name, and its own javadoc says "only main (and
nothing else) stays advertised" — an invariant currently enforced only by a
prune whose result is discarded. Under an allowlist, a refused prune stops being
an exposure and becomes only untidiness.

Scoped to `GitFacadeConfiguration` alone. `GitPublishConfiguration` builds its own
`UploadPack` over a hosted marketplace's *origin* repository, where a publisher
legitimately fetches their own branches; that chain keeps advertising everything
it does today.

*Alternatives.* `uploadpack.hiderefs` in each repository's git config is
per-repository state to write and keep written, differs across backends, and is
invisible in the code that serves. An `AdvertiseRefsHook` can do the same job;
`RefFilter` is the narrower tool for the narrower question.

### 4. The row is written before publication, and repaired if publication fails

Order stays `decide()` → publish. On failure the row is returned to the state it
held before the attempt, restoring the revocation stamps `decide()` cleared, via a
new state-guarded transition (`WHERE id = :id AND state = 'approved'`).

*Why not `@Transactional` on the approval.* `doApprove` deliberately writes
refusal entries to the ledger *before* raising — the cooling-off refusal and the
four-eyes conflict both do, because "a control that turns approvals away invisibly
cannot be audited". A transaction spanning the method would roll those writes back
on exactly the paths that exist to record them. Wrapping a trust boundary in a
transaction to fix a ref-update bug would silently disable an audit control.

*Why not publish first, then record.* Content served before the row records it
breaks the invariant the whole system exists for — nothing is served unless
approved. Recording first and failing to serve is an availability and consistency
defect; serving first and failing to record is a trust breach. Given a choice of
which inconsistency to risk, take the one that cannot lie about what is approved.

*Why not `revoke()` as the repair.* `revoke()` is the retroactive-quarantine
transition and stamps `revoked_at`/`revoked_by`/`violation`. A failed publish is
not a policy revocation; recording it as one would put a fabricated revocation in
front of an auditor.

*If the repair itself fails*, both causes are raised together and logged: the
estate is then genuinely inconsistent (row `approved`, nothing published), which
is the case the startup check that verifies the served estate against the database
is for. Named here rather than left implicit.

### 5. The remaining raw ref updates share one checked helper

`CatalogService` (both sites), `IngestionService` and `RetentionService` keep
operating on their own repositories — none of them is a publication and none
belongs on the seam — but each checks its result through one helper that states
the allowed-result sets once. `StorageMigration`'s `WRITTEN` and
`FilesystemGitStorage`'s `DELETED` move into it, so there is one definition rather
than three.

*Why not put these on the seam too.* The seam is the storage-role boundary, not a
general ref API. Widening it to cover catalog scaffolding and quarantine pins
would oblige every backend to implement operations that have nothing to do with
storage substitutability.

### 6. Requirements

Six requirements, one per affected capability, so each is owned by exactly one
spec: `GW_0132` (git-storage — publication is one atomic, verified transition on
every backend), `GW_0133` (snapshot-approval — an approval reports success only
when publication took effect, and a refusal leaves nothing published and the row
as it was), `GW_0134` (git-facade — the facade advertises only the refs it
serves), `GW_0135` (virtual-catalog), `GW_0136` (snapshot-retention) and
`GW_0137` (marketplace-ingestion).

`GW_0112`'s existing clause then covers publication by construction once it is on
the seam, so the storage contract and concurrency suites gain publication cases
rather than a parallel suite.

Verification is adversarial, per the repo's discipline for trust boundaries: a
refused transition must not produce an `Approved` result, must leave nothing
fetchable — by `main` *or* by SHA — and must leave the row as it was. Each new
verification case is proved to fail against the unfixed code before it is trusted,
following `theStalenessCheckCanActuallyFail`.

## Risks / Trade-offs

- **The allowlist hides a ref something legitimately fetches** → enumerate every
  namespace in every served role and its consumer before landing; cover clone,
  fetch-by-SHA and the catalog with real-client tests; leave `/publish/**`
  untouched.
- **A new seam method is a new obligation for every backend, including future
  ones** → it is the inverse of one that already exists, tested by the same
  contract suite in the same shape; a backend that cannot do it atomically cannot
  correctly revoke either.
- **The object-store transaction now covers two refs on the publish path as well**
  → `unpublish` already edits two refs in one transaction; this is the same
  mechanism, and the concurrency suite gains the publish-races-revoke interleaving
  that surfaced #149 in the first place.
- **Approvals begin failing where they previously reported success** → that is
  the fix. The failure is loud, the snapshot stays held, and the operator can
  retry; the alternative is the gateway lying about what it serves.
- **Staging refs accumulate on repeated crashes** → unadvertised and overwritten
  by the next publication of the same sha; swept by a retention task.
- **Scope is larger than #149 asks for** → accepted deliberately (owner decision):
  the narrow fix detects a state this one makes unreachable, and the object-store
  backend makes that state ordinary rather than rare.

## Migration Plan

No data migration. Published repositories are unchanged on disk and in the
bucket; the staging namespace is new and empty until first use. The allowlist
narrows advertisement to what `docs/manual/reference/git-facade.md` already
documents as served, so no client that follows the documented surface changes
behavior.

Rollback is a revert: nothing in this change writes a new persistent format, and
the new `held` transition is additive.

Deploy order is irrelevant — there is no released version and no deployed
consumer.

## The served namespaces, enumerated (task 1.1)

`/git/**` resolves through `GitFacadeConfiguration.resolvePublished`, which calls
`storage.publishedIfServing` and nothing else. So the fetch facade serves the
**published** role only — including the virtual catalog, which is a published-role
repository under a reserved name. The hosted role is reached exclusively through
`/publish/**` and `GitPublishConfiguration`, which builds its own `UploadPack`
over the publisher's origin repository. Design open question 2 is therefore
settled: the allowlist needs no hosted namespace, and `/publish/**` is untouched.

| Namespace | Roles that hold it | Consumer | Advertised on `/git/**`? |
| --- | --- | --- | --- |
| `refs/heads/main` | published, catalog, hosted | the served tip: facade `SERVED_REF`, `AdoptionService`, `SnapshotPreviewService`, `Marketplace.LINEAGE_REF`, catalog rebuild | **yes** |
| `refs/snapshots/<sha>` | quarantine (pin), published (approved) | fetchable by name in its own right; `unpublish` removes it | **yes** |
| `refs/quarantine/incoming` | quarantine | `IngestionService` staging; quarantine is never served | no |
| `refs/catalog/*` | catalog (a published-role repository) | rebuild scaffolding; its own javadoc says only main stays advertised | no |
| `refs/staging/<sha>` | published | object transfer ahead of the atomic transition (new) | no |

`refs/heads/master` appears in `IngestionService` only as a preferred branch of
the *upstream* remote, and in `FilesystemGitStorage` as the JGit default that
repository creation corrects to `main`; neither is a served ref. Published
repositories acquire no other namespace, because publication fetches only the one
refspec.

## Open Questions

- **Settled: no active sweep in this change.** A staging reference is unadvertised,
  so it serves nothing — asserted against a real client. A staging reference for a
  snapshot that is later published is reused and cleared by the publication that
  completes — asserted on both backends. What remains is a staging reference for a
  snapshot that is *never* published, which holds objects garbage collection would
  otherwise reclaim.

  Sweeping those needs to know which SHAs are abandoned, and that is database
  knowledge the seam deliberately does not have; retention, which does have it,
  garbage-collects quarantine rather than published repositories. So the sweep is a
  retention change with a snapshot-row query, not a storage change, and inventing a
  mechanism for it inside a trust-boundary fix would be the drive-by this change
  exists to avoid. Filed as a follow-up.
