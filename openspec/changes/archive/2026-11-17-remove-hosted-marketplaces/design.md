# Design: remove-hosted-marketplaces

See `proposal.md` for the reasoning. This document settles the decisions that
removing a shipped feature leaves open. Each was grilled against the code and
the compatibility page; the ones only the owner can make are at the end.

## Context

A hosted marketplace is one row with `origin = 'hosted'` and a null `url`, a
bare repository under `{data-dir}/hosted/{name}.git` (or the `HOSTED` role of
the object-store backend), and zero or more tokens whose `push_scopes` name it.
Ingestion copies from that repository instead of fetching a URL; everything
from the snapshot pin down is the same code. So the pipeline needs no
change: removal is deleting a source of content and the credential that fed it.

What makes removal more than a delete is that the hosted repository is the
*only* copy of that source. Quarantine and the served repository can be rebuilt
from an upstream; this one cannot be rebuilt from anything. The owner decided,
for a beta, that the upgrade does not stop for that: it deletes the database's
record of every hosted marketplace and leaves the repositories exactly where
they are, and the gateway tells its administrators where they are until they
are gone (Decisions 1, 13 and 14).

## Goals / Non-Goals

**Goals:** leave no code path, column, type, constraint, route, document or
requirement that exists only for hosting; never delete a hosted repository
itself; say loudly, to the administrator, that repositories remain and what to do
with them; refuse, rather than ignore, a client or an estate file that still asks
for hosting.

**Non-Goals:** converting a hosted marketplace to an upstream automatically (the
gateway cannot create a forge repository); a deprecation period; any button that
deletes a repository; touching the `ApprovalService` publishing of approved
content to the served repository, which shares a verb with `/publish` and nothing
else; rewriting the audit ledger.

## Decisions

### 1. The migration never refuses: it purges every hosted marketplace, and touches no repository

`V3__remove_hosted_marketplaces.sql` deletes the `marketplaces` row of **every**
marketplace with `origin = 'hosted'`, live or already removed, and everything that
depends on it, so that no row of any table refers to one afterwards and the
schema can drop `origin` and demand a `url` of every row. There is no separate
hosted table: hosted marketplaces are rows of `marketplaces`. It never raises and
it never touches storage: the bare repositories at `{data-dir}/hosted/{name}.git`
(or the object store's `hosted/` prefix) stay exactly where they are, and they
are the only copy of each publisher's source.

This is an owner decision for a beta, and it reverses two drafts: the first kept
a record of removed marketplaces with a nullable `url`, the second refused to
upgrade while a live one existed. Both bought safety with schema debt or an
upgrade that cannot proceed; the owner chose a clean schema and a loud notice.
AGENTS.md's rule that migrations meet live data is why it is stated here rather
than hidden: **a live hosted marketplace is deleted by the upgrade, with its
snapshots, approvals, vetting history and ledger entries, and it stops being
served at that moment** — its `/git/{name}` answers as any unknown name. The
source survives in its repository; the history does not. An operator who needs
the ledger as evidence must export it before upgrading (the guide says so), and
who wants no gap in service re-homes before upgrading (the guide says how).

The dependents were enumerated from the schema's foreign keys and its
`marketplace`/`snapshot` columns, not from memory:

| Reaches the marketplace by | Tables | How it goes |
| --- | --- | --- |
| `marketplace_id`, no cascade | `snapshots` | deleted explicitly first |
| `snapshot_id`, cascading from `snapshots` | `snapshot_closures` (and `snapshot_closure_members` through it), `snapshot_facts` (and `snapshot_plugin_names`), `vetting_runs` (and `vetting_verdicts`, `vetting_findings`), `snapshot_vetting_overrides`, `snapshot_revocation_reversals` | cascade from the snapshot delete |
| `marketplace_id`, cascading from `marketplaces` | `vetting_waivers`, `auto_approval_exemptions`, `role_grants`, `vetter_toggles`, `vetting_chain_modes`, `vetting_chain_orders` | cascade from the marketplace delete |
| `last_ingest_snapshot_id`, `ON DELETE SET NULL` | `marketplaces` (the same row) | nothing to do |
| `marketplace_id`, **no foreign key by design** | `fetch_log` | deleted by id |
| the marketplace's **name**, no foreign key | `fetch_log` rows with a null `marketplace_id`; `staging_ref_sightings` | deleted by name, only under the rule below |
| the name inside a serialized payload | `webhook_deliveries` | **left**: a delivery queue with no key to a marketplace, whose payload is text a subscriber has already been sent |

The order is: the ledger by id, the ledger and sightings by name where the rule
allows, `snapshots` (cascading), then `marketplaces` (cascading).

**The ledger purge is by id, and by name only where no one else has used the
name.** `fetch_log.marketplace` is a name and a removed marketplace's name can be
registered again, so deleting by name alone would delete the history of a living
successor. Entries with a `marketplace_id` of a purged marketplace go. Older
entries with a null `marketplace_id` go only when their name equals a purged
marketplace's name **and no marketplace row that survives the purge (live or
removed, upstream) has ever carried that name**. Where a survivor has used the
name, the null-id entries are left, because they cannot be told apart from the
survivor's own; the migration comment says so and a test pins it.
`staging_ref_sightings` is keyed by name alone and follows the same rule.

**Audit sinks and ids.** A sink keeps the id of the last ledger entry it was
handed (`audit_sinks.cursor_position`). The export reads `id > cursor ORDER BY
id LIMIT n` (`FetchLogRepository`), so a gap in the ids, which a rolled-back
`BIGSERIAL` insert already produces, is harmless; ids are never reused, so a
cursor above the new head stays valid. The only visible effect is the sink's
reported lag, computed as head minus cursor, which counts the gap. Entries
already exported to a SIEM stay there.

Nothing may lack a URL any more, so the migration restores `url TEXT NOT NULL`
and drops `marketplaces_upstream_has_url` without a replacement. Dropped:
`marketplaces.origin`, `marketplaces.push_policy`,
`marketplaces_hosted_is_on_demand`, the two enum types (after their columns),
`access_tokens.push_scopes`. `scope_lists_are_never_empty` is dropped and
re-created without its `push_scopes` clause, in the same migration.

Alternatives: (a) *convert* hosted rows to upstreams — impossible, there is no
URL to convert to; (b) *refuse while a live one exists* — the second draft:
nothing is lost, but an upgrade that cannot proceed until an operator has done a
procedure under the old version is its own outage, and the owner chose a beta's
clean break over it; (c) *leave the columns* and only hide the feature — keeps the
schema debt the stop rule exists to prevent; (d) *keep removed rows* with a
nullable `url` and a conditional check, the first draft — keeps the exception in
the schema for the sake of records of things that no longer exist.

### 2. Tokens that carry push scopes

`push_scopes` goes with the column. A token keeps exactly the fetch scopes it
had (`scopes`) and its `api_scopes`; it loses only a grant nothing can use any
longer. No token's capability widens: the fetch default (`NULL` means every
marketplace) already applied to it. A token issued only to publish therefore
becomes an ordinary fetch token for every marketplace; an operator should revoke
such tokens, and the guide says so. The ledger keeps its past `token-created` entries,
which name push scopes in their detail; the ledger is append-only and is not
rewritten.

### 3. Compatibility: a declared break, not a deprecation

This is a removal under `reference/compatibility.md` → "The API contract": a
field and an enum value leave the response, a request field leaves the request,
an endpoint stops existing. Per that page: no new prefix is added (the surface
being removed is not replaced by a successor, so keeping `/api/v1` is the honest
choice — nothing is renamed, a feature is gone), the PR title carries `!` or
`BREAKING CHANGE`, and the `⚠️ BREAKING CONTRACT` label is applied by the owner.
Title: `refactor(api)!: remove hosted marketplaces and the publish endpoint`.
The release version is derived from the squash commit subject; no version is
bumped by hand. The page says the estate schema is covered by review, not oasdiff;
its note in "What this promise covers" is extended with this removal, and the
page gains a `!!! warning "Hosted marketplaces were removed"` block in the
style of the two before it.

### 4. The API refuses the removed fields; it does not ignore them

Jackson here ignores unknown request properties, so removing `origin` from
`RegisterRequest` would make `{"name":"x","origin":"hosted"}` fail only as "url
required", and `{"name":"x","url":"…","origin":"hosted"}` (never valid before)
register an upstream silently. Neither reflects what the caller asked for.

`RegisterRequest` keeps `origin` and `pushPolicy`, and `CreateRequest` keeps
`pushScopes`, as `@Schema(hidden = true)` components that exist only to be
refused: `origin` other than null or `upstream`, a non-null `pushPolicy` and a
non-empty `pushScopes` answer `400` with a `ProblemDetail` saying that hosted
marketplaces were removed and naming the guide. (`origin: "upstream"` stays
accepted, because it was valid and means what the request now always means.)
`DeclaredAutoApproval.exemption` is the precedent: a field kept so it can be
refused. Hidden fields do not appear in `openapi.json`, so the contract shows
the removal; the refusal is documented in the endpoint reference.

### 5. The estate refuses the removed keys

`DeclaredMarketplace` keeps `origin` and `pushPolicy` for the same reason: Spring
binds unknown keys by ignoring them, and an ignored `origin: hosted` becomes a
confusing url failure. With them kept, the reconciler fails the entry (as it
does for an `exemption`) with a message naming the removed feature and the
guide. `origin: upstream` is accepted. `ConfigSurfaceBudgetTests` does not walk
list elements, so its budget does not move.

### 6. `/publish/**` is simply unrouted

Deleting `GitPublishConfiguration` removes the servlet; deleting `publishChain`
removes the filter chain. A request then falls through to whatever answers an
unknown top-level path, including for an unauthenticated caller, and the test
asserts equality of status and body class with a path that never existed
(`/nothing-here/x`), not a specific code. `BrowserNotFoundAdvice` drops
`/publish/` from its API-prefix list. There is deliberately no tombstone
response: a path the gateway never serves should not advertise that it once did.

### 7. Storage seam

`GitStorage.Role` loses `HOSTED`; `hosted()` and `hostedIfPresent()` go from the
interface and both backends; `StorageMigration` stops iterating a role that no
longer exists (it loops `Role.values()`, so this is automatic, and its test
stops expecting three). Repositories on disk and object-store data under a
`hosted` prefix are left alone by the migration and by the gateway: the operator
copies and deletes them, and Decision 13 is how the operator is told they exist.

### 8. Auto-approval and internal sources

A hosted marketplace was internal by construction (`InternalSources.isInternal(Marketplace)`
returned true for it). That branch goes: internal means under an operator
prefix, nothing else, which is also what `NotInternalException`'s message must
say. `AllowedBy.HOSTED` is removed, which removes `hosted` from the `allowedBy`
enum; it is computed at read time, never stored, so no data migrates. A response
can no longer say `hosted` — a consumer that matched on it never sees it again.

### 9. Audit events

`marketplace-pushed`, `marketplace-lineage-rewritten`, `marketplace-push-ingest-failed`
and `marketplace-push-scopes-removed` are no longer emitted. They stay in the
ledger forever and `reference/api/audit.md` lists them as historical. The
`origin=hosted` detail on `marketplace-registered` is dropped from new entries;
`GW_RETENTION_0010` is revised so its protection of "first-party publication"
entries no longer promises a kind that cannot be written — and nothing in the
retention code is relaxed: the trimmable set is a closed list of facade read
events, so an event kind that is no longer emitted is simply never trimmed.

### 10. Reqstool

Removed with the feature, each a deliberate owner-approved removal listed in the
PR: requirements `GW_FACADE_0006 — Gateway-hosted marketplace origin`,
`GW_FACADE_0007 — Authenticated publication over a separate write endpoint`,
`GW_INGEST_0017 — Pushed content traverses the unchanged pipeline`; SVCs
`SVC_GW_FACADE_0006 — A hosted marketplace registers without an upstream and is
never fetched from one`, `SVC_GW_FACADE_0007 — Only an explicitly push-scoped
token publishes, and only the single lineage, forward`, `SVC_GW_FACADE_0035 —
Only an administrator or the marketplace's approver is given a push scope`,
`SVC_GW_INGEST_0017 — A push is quarantined, vetted, held, and served only once
approved`. Revised (clause removed, `revision` bumped): `GW_FACADE_0008`,
`GW_FACADE_0011`, `GW_RETENTION_0010`, `GW_INGEST_0035`, `GW_INGEST_0066`,
`GW_APPROVAL_0044.2`, `GW_INGEST_0070`, `GW_AUTH_0080`, and the SVCs
`SVC_GW_FACADE_0036`, `SVC_GW_AUTH_0018`, `SVC_GW_FACADE_0011`,
`SVC_GW_INGEST_0035`, `SVC_GW_INGEST_0063`, `SVC_GW_INGEST_0066`,
`SVC_GW_APPROVAL_0044.2`, `SVC_GW_AUTH_0098` where their text names hosting.
No SVC is removed except with its requirement, and no assertion in a surviving
SVC's test is weakened: where a surviving test used a hosted marketplace as a
fixture, the fixture changes and the assertion about the *other* behaviour stays.

New: `GW_AUTH_0090` with `SVC_GW_AUTH_0110` (the upgrade, run against databases
at `V1` and `V2`) and `SVC_GW_AUTH_0111` (the API, estate and path refusals);
`GW_AUTH_0091` (the administrator-only listing and notice of the former hosted
repositories) with `SVC_GW_AUTH_0112` (the route) and `SVC_GW_AUTH_0113` (the
portal notice). Ids come from the range this change owns.

### 11. Tests deleted, and why the behaviour no longer exists

| Test class | Behaviour it covered | Why it no longer exists |
| --- | --- | --- |
| `HostedPushTests` | A real `git push` to `/publish/{name}`: token scoping, single lineage, no deletes, append-only vs allow-rewrite, the facade still refusing pushes | There is no publish endpoint. The last clause (the facade accepts no push) survives in `SmartHttpOnlyTests`, which already asserts it |
| `PushScopeMintingTests` | Who may be granted a push scope (administrator or the marketplace's approver) | There is no push scope |
| `HostedMarketplaceTests` | Registering with `origin: hosted`, no URL, fixed refresh strategy, the origin and publish location in the description | There is no hosted origin; replaced by the refusal test |
| `HostedLifecycleTests` | A pushed commit through quarantine, vetting, hold and approval; removal and re-registration of a hosted name | Content no longer arrives by push. Removal and re-registration of an *upstream* are covered by `MarketplaceRemovalTests` |
| `ClaimMappedPushScopeTests` | A claim-mapped approver being granted a push scope | There is no push scope |

Partly hosted classes (about 25) change a fixture or lose a case that names
hosting; each such change is listed in `tasks.md` by class.

### 12. Frontend

Only what exists is removed: the "publishes to" line in `pages/tokens.tsx`
(its test and stories), the hosted branch in `pages/marketplace-detail.tsx`,
the `hosted` label in `components/auto-approval.tsx`, and the fixtures in
`msw-handlers.ts`. The change is a deletion of a line of text in the Reach column
and a label; whether anything is visible enough to photograph is decided from
the real before/after (a token with a push scope exists only in the "before"
fixture). `/impeccable audit` runs on the pages changed.

### 13. One temporary route and one temporary class list what the upgrade leaves behind

Because the migration deletes the database's record and leaves the repositories,
an administrator needs to be told they are there. `GET
/api/v1/storage/former-hosted-repositories` (administrator only, read-only,
unreachable by every machine credential, an empty list when there is nothing)
answers from `FormerHostedRepositories`, a single class marked **temporary** in
its javadoc, in a form a later change can search for: **`REMOVE-AFTER: two minor releases after the release that ships migration V3__remove_hosted_marketplaces`** —
the owner chose two minor releases (X = 2). The class, its route, the portal
banner, `GW_AUTH_0091` and the guide's notice paragraph all go together then.

- **Filesystem backend.** It lists the direct children of `{data-dir}/hosted`
  that are directories, not symbolic links, and are named `<marketplace-name>.git`
  (the marketplace-name rule). A stray file, a symlink, a directory with another
  name and anything deeper is ignored and never followed; nothing is opened for
  write and nothing is deleted. Each entry reports its name, its absolute
  location, and, read-only through JGit and tolerating any failure, the date and
  short sha of `refs/heads/main`'s commit.
- **Object-store backend.** It lists the repository manifests under the
  `hosted/` key prefix of the gateway's own prefix: **names and prefix only**. A
  repository there cannot be opened without a role the seam no longer has, and
  the last commit is not worth rebuilding the role for; the guide says the
  operator copies it with the storage tooling they used to provision the bucket.
- **Nothing is stored.** There is no table, flag or marker: the list is read from
  storage on each request, so the notice disappears the moment the folders do.

The stop rule asks why `docs/manual/capability-map.md` cannot absorb it: it is
not a capability of the gateway but a one-off consequence of one upgrade, and the
map describes what the gateway does, not what an upgrade left on a disk. The
route and class are scheduled for deletion, which is what lets them be added.

### 14. The portal tells administrators, and only administrators

A banner on the Overview page, alert-styled, rendered only for a session that
holds the administrator role and requesting the route only for that session (the
query is disabled otherwise, so a non-administrator causes no request). It reads
"This gateway no longer hosts marketplaces. N repositories from the former
hosting feature are still in its data store; copy each one (git clone
<location>) and then delete the folder.", lists each repository with its name,
location, and the last commit's date and short sha where there is one, has no
delete control, and renders nothing when the list is empty. Design-conventions
and `/impeccable audit` apply; the guide is linked from the banner.

## Risks / Trade-offs

- **A deployment's hosted marketplaces vanish at upgrade** → accepted by the
  owner for a beta; the source is not touched; the banner lists each repository;
  the guide, the PR and the proposal say so before anything else. The way to
  avoid the gap is to register a forge copy under a temporary name and re-point
  consumers before upgrading.
- **The history of those marketplaces is gone** (snapshots, approvals, vetting,
  ledger entries) → an owner decision for a beta; an operator who needs the ledger
  exports it first, which the guide says in its first paragraph.
- **Push-only tokens become ordinary unscoped fetch tokens** (null `scopes`
  means every marketplace, which they already effectively were for fetch) →
  nothing widens, but an operator should revoke tokens issued only to publish; the
  guide says so.
- **No rollback after V3** → documented: take a database backup first; the old
  version cannot run against a schema without `origin`.
- **Hidden refused fields look like leftovers** → their javadoc says what they
  are, and `GW_AUTH_0090` is what keeps them from being deleted as cruft.
- **A purged name that was reused** → its null-id ledger entries stay (they
  cannot be told from the successor's); the successor's own history is
  untouched, and a test seeds exactly this.
- **The listing reads a directory an operator controls** → only direct, non-link
  directories with a conforming name are listed, and JGit reads fail soft.

## Migration Plan

1. Optional, on the old version: export the audit ledger if it is evidence; to
   avoid a gap, register a forge copy of each hosted repository under a temporary
   name and re-point consumers.
2. Back up the database; upgrade. V3 applies, deleting the hosted marketplaces'
   records; their repositories stay.
3. After: the portal's administrator banner lists each repository. Copy it (`git
   clone <path>`), push it to a forge, register the forge repository as an
   upstream under the old name (free after the purge), approve its first snapshot,
   then delete the folder; the banner goes when the last one is gone.

## Decisions for the owner (only these)

1. **The purge deletes live hosted marketplaces' approval history too**, decided
   for a beta. Recommended: export the audit ledger first if compliance needs it.
2. **Consumers lose those marketplaces at upgrade**, and see an outage until the
   forge copy's first approval, decided and accepted.
3. **The object-store limits**: the notice lists names and the key prefix only.
4. **Whether this ships as a major.** The compatibility page says a break moves
   the prefix and ships as a major. This change declares the break and does not
   move the prefix (nothing replaces what is removed); applying the
   `BREAKING CONTRACT` label is the owner's act.
5. **Acceptance of ADR 0024**, which supersedes ADR 0007. Accepted by the owner on 2026-11-17 (it was Proposed when this change was written).
