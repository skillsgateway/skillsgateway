# Proposal: virtual-catalogs

## Why

Issue [#11](https://github.com/skillsgateway/skillsgateway/issues/11) asks for
four things in three lines: federated namespaced catalogs, per-team
entitlements, per-plugin/skill limiting, and multi-ref publication. The
federation half shipped — `GW_FACADE_0003 — Global virtual catalog`,
`GW_FACADE_0004 — Catalog freshness on publication changes` and
`GW_FACADE_0005 — Catalog provenance, audit, and the reserved name`. The rest has been
assessed twice on the issue and never opened, because "what to build" was not
decided.

[ADR 0017](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0017-virtual-catalogs-are-derived-views.md)
decides it. It states the invariant — a catalog is derived content, and a view
**may omit but may never present content under a name that denotes something
else** — and decomposes #11 into five slices, of which two are deferred on
product grounds and one is ruled out of the issue entirely.

**This change is slice 1**, and it is not a new feature. It closes two defects
in the shipped catalog that every later slice would inherit and amplify:

1. **The catalog's namespace map is not injective, and its collision rule
   substitutes content across a publisher boundary.**
   `CatalogService.mergePlugin` builds a catalog plugin name as
   `marketplace + "-" + plugin`, and marketplace names may themselves contain
   `-`. Marketplace `a` with plugin `b-c` and marketplace `a-b` with plugin `c`
   both produce `a-b-c`; constituents are iterated in ascending marketplace-name
   order and the first one wins. A developer installing `a-b-c` expecting
   marketplace `a-b`'s plugin gets marketplace `a`'s instead. The only signal is
   a `log.warn`: no ledger event, no metric, no API field. The winner is decided
   by lexicographic order, so registering an unrelated marketplace re-decides an
   existing install name at the next rebuild.

2. **A catalog revision named on the fetch ledger may no longer be
   describable.** A revision's composition is recorded only in its commit
   message, and every rebuild force-updates `refs/heads/main` to a new parentless
   commit — correct, because it is what makes a retracted constituent
   unreachable, but it also makes the previous composition unreachable. The
   ledger keeps the SHA; the gateway loses the ability to say what it contained.
   "What exactly did we serve, and when" is the question every later slice exists
   to make someone ask.

Neither is a filtering feature, and both are prerequisites for one. A filtered
catalog has a different plugin set per audience, so an order-dependent collision
winner becomes a *different* winner per audience — the same install name
resolving to different publishers' content for different teams. Filtering cannot
be built on an ambiguous namespace, and it cannot be audited without a durable
record of what each revision contained.

## What Changes

- **Collisions become specified, fail-closed behaviour (GW_0218).** Two
  constituents mapping to the same catalog plugin name is a collision. On
  collision **neither** entry is published — "first in marketplace-name order
  wins" is removed. A silent substitution becomes a loud absence, which is the
  fail-closed direction: an absent plugin is a support ticket, a substituted one
  is an incident. A duplicate plugin name *within* one marketplace is a manifest
  defect in that marketplace and is reported the same way rather than silently
  de-duplicated.

- **A collision is audible on every surface the gateway has (GW_0219).** A ledger
  event names both `(marketplace, plugin)` pairs and the catalog it occurred in;
  a metric counts collisions; `GET /api/catalog` reports the collisions in the
  served revision. Today the only record is a line in the application log, which
  is the one place nobody looks until afterwards.

- **Registration warns on a name that can collide (GW_0220).** Registering a
  marketplace whose name is a prefix of, or is prefixed by, an existing one is
  the only collision source a registrant controls. It **warns** and succeeds; it
  does not refuse. Refusing would reject `acme` beside `acme-tools`, which is an
  ordinary pair of names, and would fail a declarative-estate deploy over a
  collision that may never occur — the reconciler is additive and never
  destructive.

- **Every catalog revision's composition is persisted, keyed by its commit SHA
  (GW_0221).** At synthesis the gateway writes an immutable row: catalog name,
  commit SHA, timestamp, the ordered `(marketplace, snapshot SHA)` constituents,
  and the collisions detected. The commit message stays — it is what makes the
  catalog self-describing to a plain git client — but it stops being the only
  record. A ledger `sha` now joins to a description that survives the commit
  becoming unreachable.

- **A revision is answerable by SHA (GW_0222).** `GET /api/catalog/revisions`
  and `GET /api/catalog/revisions/{sha}` answer for revisions no longer served.
  `GET /api/catalog` is unchanged in shape and gains the collision list.

## Capabilities

### Modified Capabilities

- `virtual-catalog`: gains specified collision semantics (fail-closed, audited,
  reported) and a durable per-revision provenance record addressable by commit
  SHA. Composition rules, freshness triggers, the reserved name and the served
  ref allowlist are unchanged.
- `marketplace-ingestion`: registration gains a non-blocking warning when a
  proposed name can produce a catalog collision. The registration gate itself —
  name pattern, reserved catalog name, URL scheme allowlist, gateway-pinned ref
  — is unchanged, and no registration that succeeds today begins to fail.

### New Capabilities

_None._ This change adds no serving surface, no authorization surface and no
estate object type.

## Requirement ids

Reserved by this change: **GW_0218 – GW_0222**, plus **GW_0223 – GW_0225** held
for slice 2 (per-plugin exclusions) so the two changes do not race for ids.

The entries are **not written into `docs/reqstool/requirements.yml` by this
change.** A requirement with no `@Requirements` annotation and no SVC test is
incomplete, and adding one for unimplemented behaviour turns the traceability
gate red on `main`. The implementing PR adds the requirement, the SVC and the
annotated code together, which is the order `CLAUDE.md` asks for. The spec deltas
in `specs/` name the ids so the reservation is visible and reviewable.

## Out of scope (named, so the boundary is explicit)

Deferred to later slices per ADR 0017, and to be opened as their own changes:

- **Per-plugin exclusions** (slice 2) — the sixth declarative estate object
  type, `skills-gateway.estate.catalog-exclusions`, keyed `(marketplace, plugin
  name)`, pruning both the manifest entry and the vendored source tree.
- **Skill-level exclusion** — a separate decision, because the
  `…/skills/*/SKILL.md` discovery rule lives inside `SnapshotContentService`
  bound to the quarantine repository, and removing a skill means rewriting a
  plugin's tree rather than omitting a path.
- **Per-team catalogs** (slice 4) — a named catalog over a chosen constituent
  and exclusion set. Blocked behind two engineering prerequisites ADR 0017 names:
  rebuild is `O(catalogs × marketplaces)` per publication change on one in-JVM
  lock, and `CatalogService.vendor` fetches from
  `published.getDirectory().getAbsolutePath()`, which is `null` on the
  object-store backend's `DfsRepository`.
- **Entitlement as a facade deny** (slice 5) — the only genuinely new trust
  boundary in #11, gated on a deployment with two real audiences.
- **Multi-ref publication** — ruled out of #11 by ADR 0017. It re-decides
  `GW_INGEST_0006 — Gateway-pinned ingestion ref`, which `CLAUDE.md` names as part of
  the registration trust boundary, and needs its own ADR and its own issue.
- **Portal surfaces** for collisions and revisions. The API and the ledger carry
  them first.

## Impact

- **DB**: one new append-only table for catalog revisions and their constituents
  (one Flyway migration). No existing table changes.
- **Backend**: `CatalogService` (collision detection and reporting, revision
  persistence), a new revision repository, `CatalogController` (two new read
  endpoints, one enriched response), `MarketplaceRegistrationService` (the
  prefix warning), `AdminAuditLogger` (one new event type), `GatewayMetrics` (one
  counter), `MachineApiRegistry` (the two new `/api/**` routes must be
  classified or the build fails).
- **API**: additive — two new paths under `/api/catalog/revisions`, and new
  optional fields on the existing `CatalogInfo`. No path, DTO or field is removed
  or changed, so the breaking-change gate has nothing to fail on.
  `src/main/frontend/openapi.json` and `src/api/types.gen.ts` regenerate.
- **Behaviour change on the serving surface**: in an estate that already has a
  collision, one plugin that is published today stops being published. This is
  intended — the published one may be the wrong one — and is why the fail-closed
  rule and the reporting ship together rather than separately.
- **Trust boundary**: this touches the catalog synthesis path that
  `ApprovalService` and `RevetService` call, and it changes what the facade
  advertises in a merged manifest. Adversarial and negative tests are part of the
  definition of done — a constructed collision pair, a collision introduced by a
  *later* registration, a collision that disappears when one marketplace is
  revoked — and `evidence.md` ships with the change.
- **Docs** (same PR): `guides/virtual-catalog.md` (the "Known limit" section is
  replaced by specified behaviour), `reference/api/` for the new endpoints,
  `guides/registering-a-marketplace.md` for the warning,
  `reference/observability.md` for the metric, `architecture.md` §13.

## Status

**Design only.** This PR ships ADR 0017 and this proposal. No implementation, no
requirement entries, no migration. `tasks.md` is the implementing PR's work.
