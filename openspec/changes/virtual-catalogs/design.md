# Design: virtual-catalogs

## Context

What exists, read rather than remembered:

- **`CatalogService.rebuild`** takes an in-JVM `synchronized (rebuildLock)`,
  lists marketplaces sorted ascending by name, and for each one calls
  `storage.publishedIfServing(name)`, skipping anything not serving. For each
  serving constituent it fetches the published tip into `refs/catalog/<name>`
  (internal scaffolding, never advertised — `GW_FACADE_0016 — The facade advertises only
  the references it serves` and `GW_FACADE_0017 — The served catalog repository keeps no
  scaffolding references`), records the tip's tree id, parses the constituent's
  `.claude-plugin/marketplace.json`, and merges each plugin.
- **`CatalogService.mergePlugin`** is where the defect lives:

  ```java
  String prefixed = marketplace + "-" + plugin.path("name").asText("unnamed");
  String owner = pluginOwners.putIfAbsent(prefixed, marketplace);
  if (owner != null) {
      log.warn("catalog plugin name collision: '{}' from marketplace '{}' already"
              + " taken by '{}'; keeping the first", prefixed, marketplace, owner);
      return;
  }
  ```

  `pluginOwners` is a `LinkedHashMap<String,String>` from prefixed name to owning
  marketplace. The winner is decided entirely by iteration order, which is
  marketplace-name ascending, then upstream manifest array order.
- **`CatalogService.commitCatalog`** inserts the merged manifest blob, builds a
  root `TreeFormatter` of `.claude-plugin` plus one subtree per marketplace, and
  writes a `CommitBuilder` with **no parents**, message `"virtual catalog\n\n"`
  followed by one `"<marketplace> <sha40>\n"` line per constituent, then
  `RefTransitions.write(catalog, "refs/heads/main", commitId)` with force.
- **`CatalogService.served`** re-reads that commit message and parses the
  constituent lines back with `^[a-z0-9][a-z0-9_-]* [0-9a-f]{40}$`. It can only
  ever describe the revision `refs/heads/main` points to **now**.
- **Triggers**: `ApprovalService` and `RevetService` call `rebuildQuietly()`
  (swallowing failures, logging `POST /api/catalog/rebuild` as the repair path);
  `CatalogController` calls `rebuild()` and writes the `catalog-rebuilt` ledger
  event.
- **Serving**: the catalog is an ordinary published repository named `catalog`,
  resolved by `GitFacadeConfiguration.resolvePublished` like any marketplace,
  behind PAT Basic auth, subject to `AccessToken.permitsMarketplace`, with
  `info-refs` and `upload-pack` landing on `fetch_log` under the name `catalog`.
- **Registration**: `MarketplaceRegistrationService` validates
  `^[a-z0-9][a-z0-9_-]*$`, refuses the reserved catalog name with 422, and
  enforces the URL scheme allowlist.

## Goals / Non-Goals

**Goals**

- A consumer can never be served, under a catalog plugin name, content from a
  publisher other than the one that name denotes.
- Every collision the gateway resolves is on the ledger, on a metric, and on the
  API — reconstructible without reading application logs.
- The composition of any catalog revision named on the fetch ledger is
  answerable from the gateway's own records, after that revision stops being
  served and after its commit becomes unreachable.
- No new serving surface, no new authorization surface, no new estate object.

**Non-Goals**

- No filtering, no exclusions, no per-team catalogs, no entitlement, no
  multi-ref. Slices 2–5 per ADR 0017; each opens its own change.
- No change to the naming scheme itself. `<marketplace>-<plugin>` stays.
- No portal work. The API and the ledger carry this first.
- No change to the parentless-commit design. It is what keeps a retracted
  constituent unreachable and it is not being traded away for auditability — the
  audit record goes in the database instead.

## Decisions

### 1. Collision resolution is fail-closed: neither entry wins

The rule becomes: build the full map of `prefixed name → set of (marketplace,
plugin)` first, then publish only the entries whose set has exactly one member.
Everything with two or more members is a **collision**, and none of its members
appears in the merged manifest.

The alternatives and why they lose:

- **First in marketplace-name order** (status quo) — the winner is decided by an
  ordering nothing in the product controls, it changes when an unrelated
  marketplace is registered, and it publishes one publisher's content under
  another's expected name. This is the defect.
- **Last wins / newest registration wins** — same substitution, different
  arbiter.
- **Fail the whole rebuild** — lets one marketplace deny the catalog to the whole
  estate, and a rebuild is triggered by every approval, so it would let one
  registration block publication estate-wide. Rejected.
- **Publish both under disambiguated names** — invents names nobody asked for,
  and the disambiguation itself is order-dependent.

Publishing neither is the only option where a consumer cannot receive the wrong
thing. It costs availability of a plugin that is served today, in an estate that
already has a collision. That is deliberate: the currently-served one is as
likely to be the wrong one as the right one, and there is no way to tell which
from inside the gateway.

Note the two-pass shape matters. `putIfAbsent` decides on the first sight of a
name and cannot retract the winner when a second one arrives; the collision
detection therefore happens over the complete constituent set before any entry
is written to the manifest array.

**Within one marketplace**, the same rule applies for the same reason — a
manifest declaring `deploy` twice is a defect in that marketplace, and picking
one silently hides it. It is reported against that marketplace rather than as a
cross-marketplace collision, because the fix is upstream and the report should
say so.

### 2. The vendored subtree of a colliding constituent stays

A collision removes *manifest entries*, not constituents. The marketplace's
subtree is still vendored and it is still listed in `CatalogInfo.constituents`.
Two reasons: the constituent may have other non-colliding plugins that must keep
working, and pruning a subtree because one plugin in it collided would make the
catalog's tree diverge from its constituent record for a reason unrelated to
approval. The manifest is the advertisement; the tree is the vendored content.
Only the advertisement is withheld.

### 3. The revision record is a table, not a longer commit message

Persisting composition in the commit message was the obvious cheap option and it
is what exists. It cannot be extended to solve this, because the problem is not
what the message says — it is that the commit stops being reachable one rebuild
later, by design.

So: an append-only table keyed by catalog commit SHA, written inside
`rebuild()` **after** the ref transition succeeds. Two tables in practice — a
revision header and its constituent rows — following the existing
`snapshot_closures` / `snapshot_closure_members` shape, which is the codebase's
precedent for "a parent record and its ordered members".

Written after the ref moves, not before, because a revision row for a commit the
facade never served would be a record of something that did not happen. A ref
transition that fails raises and the row is not written; a row that fails to
write after a successful transition is logged and leaves the revision
undescribed — which is exactly the status quo, so the failure mode is not worse
than what exists.

The commit message keeps its constituent lines unchanged. It is what makes the
catalog self-describing to a plain `git log` with no gateway running, and
`served()` keeps parsing it. The table is the durable record; the message is the
in-band one. They are written from the same in-memory composition, so they
cannot disagree at write time.

### 4. Collisions are recorded on the revision, not only as events

A ledger event is the right thing for "this happened at this moment with this
actor". It is the wrong thing for "was this revision's manifest complete", which
is a property of the revision. So the collision list is stored on the revision
row **and** announced as a ledger event.

The event's actor is the system actor already used for machine-initiated ledger
entries, matching how `ForgeMirrorService` records `mirror-updated` and
`mirror-push-failed` under a declared system actor. A collision detected during
an approval-triggered rebuild is not the approver's act.

The event is emitted once per collision per rebuild, not once per rebuild. A
collision that persists across rebuilds is re-announced each time, which is
correct: each rebuild is a fresh decision to withhold, and de-duplicating would
mean the ledger stops recording the state of a revision it does record.

### 5. Registration warns, and the warning is a response field

`MarketplaceRegistrationService` already refuses a name matching the reserved
catalog name. The prefix check is different in kind — it is advisory, so it must
not use the same mechanism.

The warning rides on the registration response as an optional field and is
written to the ledger alongside the registration event. It is not an error, not a
`4xx`, and not a blocking confirmation step. Two reasons this must not become a
refusal:

- `acme` and `acme-tools` are an ordinary pair, and refusing the second would
  reject an estate that has done nothing wrong;
- `EstateReconciler` applies declared marketplaces through the same registration
  path, and its contract is that a typo in one entry never prevents startup. A
  refusal here would fail a deploy over a collision that may never occur, since
  a *possible* prefix relation between names does not mean any actual plugin
  names collide.

The check is symmetric — a new name that is a prefix of an existing one is as
much a risk as the reverse — and it names the specific existing marketplace, so
the warning is actionable rather than atmospheric.

### 6. Revisions are readable by SHA, under the existing authorization model

`GET /api/catalog/revisions` (most recent first, paged) and
`GET /api/catalog/revisions/{sha}`.

Authorization matches what is already there rather than inventing a rule: the
catalog read surface is part of the browsing surface open to any authenticated
session (`GET /api/catalog` requires no role today), while
`POST /api/catalog/rebuild` requires admin. A revision listing is a read of the
same kind, so it stays session-open and gains no role requirement. For machine
credentials it joins the existing `marketplaces:read` API scope rather than
minting a new scope value — `MachineApiRegistry` fails the build on a mapped
`/api/**` route that is in neither its allowlist nor its unreachable list, so
this is a required decision, not an optional one.

A revision row is not a directory of anything a session cannot already see: the
constituents are marketplaces and approved snapshot SHAs, both already
enumerable through `GET /api/marketplaces` and the snapshot endpoints.

### 7. Retention

Revision rows are small, append-only, and written once per publication change.
They are audit records of what was served, so they follow the ledger's retention
posture rather than the snapshot retention story: a revision row outliving the
commit it describes is the entire point, and deleting one destroys the answer to
"what did we serve". No purge path is added by this change.

## Risks / Trade-offs

- **A plugin served today can stop being served.** Only in an estate that already
  contains a collision. Mitigated by shipping the reporting in the same change,
  so the reason is visible on the API, the ledger and a metric the moment the
  behaviour changes — and by the release note saying so explicitly rather than
  leaving it to be discovered.
- **Re-announcing a persistent collision every rebuild is noisy.** Accepted:
  approval-triggered rebuilds are infrequent (one per approval or revocation),
  and a collision that keeps recurring should keep being visible. If it proves
  too noisy, the fix is a report, not silence.
- **A revision row can fail to write after a successful ref transition.** Leaves
  the revision undescribed, which is the current behaviour for every revision, so
  the change cannot regress here. Logged loudly; `POST /api/catalog/rebuild`
  produces a fresh described revision.
- **Two-pass merging holds the whole manifest set in memory before writing.** It
  already does — `pluginOwners` and the `plugins` array are both accumulated
  across all constituents before `commitCatalog` runs. The change does not
  increase the peak.
- **This does not make the catalog work on the object-store backend.**
  `CatalogService.vendor` fetches from
  `published.getDirectory().getAbsolutePath()`, and a `DfsRepository` has no
  directory. That defect is out of scope here, named in ADR 0017 as a
  prerequisite of the per-team slice, and belongs to whatever change owns
  storage-backend fidelity. Recording it rather than quietly widening this
  change.

## Migration Plan

One forward-only Flyway migration adding the revision tables. No backfill: the
compositions of revisions that already happened are gone, and inventing rows for
them would fabricate audit records. The first revision written after the upgrade
is the first one described.

Nothing in the migration touches an existing table, so a rollback is a matter of
not reading the new tables; the previous version continues to work against the
same schema.

## Open Questions

- **Should a collision be visible to a consumer in-band?** The merged manifest
  could carry a comment or a sibling file listing withheld names. Rejected for
  now because the manifest schema is upstream's and young (architecture §14,
  open question 3), and a sibling file is a convention no client reads. The
  commit message is the in-band channel that already exists. Reopen if consumers
  ask.
- **Should the prefix warning also fire when a *plugin* name creates a
  collision on the next rebuild?** That is knowable only after ingestion, not at
  registration, so it belongs to the rebuild path — where it is already reported
  as a collision. Named here so the asymmetry is deliberate.
