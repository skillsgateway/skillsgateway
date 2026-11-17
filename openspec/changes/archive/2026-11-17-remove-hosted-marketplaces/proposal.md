# Proposal: remove-hosted-marketplaces

## Why

> **Read this first: upgrading deletes the gateway's record of every hosted
> marketplace and stops serving it.** Their snapshots, approvals, vetting history
> and ledger entries are deleted by migration `V3` (an owner decision for a
> beta). The repositories at `{data-dir}/hosted/{name}.git` are **not** touched and
> are the only copy of each publisher's source; consumers get no content from those
> names until a forge copy is registered under the name and its first snapshot is
> approved. Administrators are told, in the portal, until the folders are gone.

The gateway can host a marketplace itself: one registered with `origin: "hosted"`
and no clone URL, pushed to on `/publish/{name}` by a token carrying
`pushScopes`, landing in `{data-dir}/hosted/{name}.git` and ingested from there.
`docs/decisions/0007-first-party-hosting-and-the-publish-endpoint.md` records the
decision to build it. The owner does not want the gateway to be a git host, and
this change is the stop rule in `AGENTS.md` applied to a feature that already
shipped: before proposing new scope, look for an existing surface that can be
removed.

An organisation that authors its own skills keeps them on a forge — GitHub,
Gitea, GitLab — and registers that repository as an upstream. A forge hosts
better than a gateway can (review, branch protection, CI, backup, access
control, a history people already know how to read), and private forges already
work through upstream credentials
(`docs/manual/guides/private-upstreams.md`). What hosting costs the gateway is
the part that matters for a security product:

- **A write credential.** The push scope is the only write path the product has.
  Everything else it holds is read-only by construction, and the facade's claim
  "receive-pack is disabled by construction" had to be narrowed to make room.
- **A second system of record.** A hosted marketplace's `hosted/{name}.git` is
  the only copy of that source. The gateway then owns its backup, its retention
  and its restore, none of which a quarantine or served repository needs (both
  are rebuildable from an upstream).
- **Surface that nothing else shares.** A second servlet and filter chain, a
  third storage role on every backend, two enum types, three constraints, a token
  column, five audit events, and an exemption in the auto-approval rules.

## What Changes

- **BREAKING (API).** `origin`, `pushPolicy` and `publishPath` leave the
  marketplace request and response schemas; `pushScopes` leaves the token
  schemas and the create-token request; `hosted` leaves the auto-approval
  `allowedBy` enum. `POST /api/v1/marketplaces` and `POST /api/v1/tokens` refuse
  a request that still asks for the removed behaviour (`origin: "hosted"`, a
  non-empty `pushScopes`) with a `400` that names the removal, instead of
  ignoring the field. See `design.md`, Decision 4.
- **BREAKING (endpoint).** `/publish/**` stops being routed: no servlet, no
  filter chain. It answers as any path the gateway does not serve.
- **BREAKING (estate).** `skills-gateway.estate.marketplaces[].origin: hosted` and
  `push-policy` are refused with an entry failure naming the removed feature.
- **BREAKING (schema), destructive by decision.** A new Flyway migration `V3`
  drops `marketplaces.origin`, `marketplaces.push_policy`,
  `access_tokens.push_scopes`, the two enum types and the constraints that exist
  only for hosting, and restores `marketplaces.url NOT NULL`. It **never
  refuses**: it purges every hosted marketplace, live or already removed, with
  everything that depends on it (snapshots, vetting history, waivers, grants) and
  its ledger entries by marketplace id, and applies the name-reuse rules in
  `design.md` so a successor that reused a name keeps its history. It touches no
  repository on disk or in object storage. This is an owner decision for a beta;
  AGENTS.md's rule about migrations and live data is why the destruction of
  live hosted marketplaces is stated here and in the guide rather than hidden.
  `V1` and `V2` are untouched.
- **A temporary administrator notice.** One read-only, administrator-only route,
  `GET /api/v1/storage/former-hosted-repositories`, one temporary class
  (`FormerHostedRepositories`) and one Overview banner list the repositories the
  upgrade left behind, until the operator has copied and deleted them. The
  stop rule asks why this is not absorbed by `docs/manual/capability-map.md`: it
  is a consequence of one upgrade, not a capability, and both are scheduled for
  deletion under one searchable condition, **`REMOVE-AFTER: two minor releases after the release that ships migration V3__remove_hosted_marketplaces`** (the owner
  chose two minor releases), written in the class's javadoc. Nothing is stored; the list is read from storage on each request.
- **Tokens stay usable.** `push_scopes` is dropped with the column; every token
  keeps exactly the fetch scopes it had and loses a grant nothing can use.
- Backend: `HostedPushHook` and `GitPublishConfiguration` are deleted; the
  `HOSTED` storage role and `hosted()`/`hostedIfPresent()` go from the storage
  seam, both backends and `StorageMigration`; hosted branches go from
  registration, ingestion, sync, removal, internal-source and auto-approval code.
- Portal: the "publishes to" line of the token list's Reach column, the
  hidden URL edit for hosted marketplaces and the "Hosted on this gateway"
  auto-approval label go.
- Requirements removed: `GW_FACADE_0006 — Gateway-hosted marketplace origin`,
  `GW_FACADE_0007 — Authenticated publication over a separate write endpoint`,
  `GW_INGEST_0017 — Pushed content traverses the unchanged pipeline`, with
  `SVC_GW_FACADE_0006`, `SVC_GW_FACADE_0007`, `SVC_GW_FACADE_0035`,
  `SVC_GW_INGEST_0017`. Eight requirements lose a clause about hosting.
- New: `GW_AUTH_0090 — The removed hosting surface is refused, never silently
  reinterpreted`, with `SVC_GW_AUTH_0110` (the upgrade) and `SVC_GW_AUTH_0111`
  (the API, estate and path refusals); `GW_AUTH_0091 — Administrators are told
  which former hosted repositories remain`, with `SVC_GW_AUTH_0112` (the route)
  and `SVC_GW_AUTH_0113` (the portal notice).
- **ADR 0024** (Proposed when written, accepted by the owner on 2026-11-17) supersedes ADR 0007; ADR 0007 is marked superseded.
- A new operator procedure (after the upgrade: copy each listed repository, push it
  to a forge, register it as an upstream under the old name, approve it, delete the
  folder; before it, optionally avoid the gap) in `docs/manual/guides/`, and the
  compatibility page's breaking-change notes.

This is an owner-approved removal of a feature, not a weakening of a test to
make a change pass: the removed requirements' verification cases are removed
with them, each named in `design.md`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `marketplace-ingestion`: `GW_FACADE_0006` and `GW_INGEST_0017` removed;
  `GW_INGEST_0035`, `GW_INGEST_0066` and `GW_INGEST_0070` lose their hosted
  clauses; gains `GW_AUTH_0090`.
- `git-facade`: `GW_FACADE_0007` removed.
- `git-storage`: `GW_FACADE_0011` keeps two roles, not three.
- `release-packaging`: `GW_FACADE_0008`'s refusal names the repositories it
  actually holds.
- `snapshot-approval`: `GW_APPROVAL_0044.2` — internal means under a prefix,
  nothing else.
- `snapshot-retention`: `GW_RETENTION_0010` no longer protects a
  "first-party publication" entry kind (existing entries stay, forever).
- `admin-api`: gains `GW_AUTH_0091`, the listing route and its portal notice
  (temporary).
- `admin-portal`: `GW_AUTH_0080` loses the publish-to clause.

## Impact

- **Backend** (inventory re-verified by grep): `facade/` (2 files deleted),
  `admin/MarketplaceRegistrationService`, `admin/AdminController`,
  `admin/MarketplaceRemovalService`, `auth/TokenService`,
  `auth/TokenController`, `auth/SecurityConfig`, `persistence/{Marketplace,
  MarketplaceRepository,AccessToken,TokenRepository}`,
  `config/SkillsGatewayProperties`, `estate/EstateReconciler`, `storage/*`,
  `ingestion/IngestionService`, `sync/SyncService`,
  `approval/{InternalSources,AutoApprovalSettingService,AutoApprovalService,NotInternalException}`,
  `api/BrowserNotFoundAdvice`.
- **Database**: `V3__remove_hosted_marketplaces.sql`, tested against real
  PostgreSQL schemas staged at `V1` and at `V2` holding live and removed hosted
  marketplaces with their history, a name reused by an upstream, unrelated
  marketplaces and every token kind.
- **API contract**: `openapi.json` and `types.gen.ts` regenerated. oasdiff
  will classify this as breaking; the PR title declares it with `!`, and the
  `⚠️ BREAKING CONTRACT` label is the owner's second act
  (`reference/compatibility.md` → "How it is enforced").
- **Estate schema**: removed keys, declared under the same rule.
- **Tests**: five wholly hosted classes deleted, about 25 edited, new tests for
  the migration, the refusals and the administrator listing (all watched failing
  first).
- **Docs** (same PR): ADR 0007 and `guides/publishing-first-party-skills.md`
  gone or superseded; `reference/api/{tokens,marketplaces}.md`,
  `concepts/{trust-boundaries,glossary,lifecycle}.md`,
  `reference/{configuration,portal,git-facade,compatibility}.md`,
  `guides/registering-a-marketplace.md`, `capability-map.md`, `mkdocs.yml`.
- **Stop-rule ratchets**: `ConfigSurfaceBudgetTests` does not move (the estate
  marketplace element is not walked); `ContextBudgetTests` is re-measured and
  lowered if the deleted test classes held a context of their own.
- **Capability map**: the row "First-party publishing" is removed, and the
  capability map is what documents that an organisation's own skills belong on
  a forge registered as an upstream. Nothing is added to the map.
- **Trust boundary**: this removes the only write path to a git endpoint, but it
  also adds a migration that destroys data if misused, so the
  `.claude/skills/old-coder` discipline applies to the guard.
- **Declarative estate**: removing API-managed state; `estate.*` loses `origin`
  and `push-policy` in the same change.
