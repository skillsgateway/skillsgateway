# Global virtual catalog

First slice of GitHub issue #11 (per the design sketch pinned in its comments);
stacked on `feat/upstream-sync-modes` (PR #60).

## Why

Every governed marketplace is its own facade URL, so a consumer wanting the
whole governed estate runs one `claude plugin marketplace add` per marketplace
and learns of new marketplaces by word of mouth. One synthesized catalog —
"everything currently approved, at one URL" — is the single biggest consumer-UX
win available, and it can be built without touching the approval gate.

## What Changes

- A **synthesized catalog repository** served by the existing facade at
  `/git/catalog` (name configurable, default `catalog`): its tree vendors the
  currently *served* snapshot of every marketplace under a namespaced
  subdirectory, with one merged `.claude-plugin/marketplace.json` whose plugin
  names are prefixed `<marketplace>-` and whose sources are all relative paths
  into the vendored subtrees.
- The catalog is **derived content only**: assembled exclusively from what each
  marketplace's published repository is serving right now. Nothing held,
  rejected, or revoked can appear; the approval gate is untouched.
- **Regeneration on every publication change**: a successful approve rebuilds
  it; a revocation's unpublish rebuilds it, so retracted content leaves the
  catalog with no operator action. Rebuild is idempotent; a manual rebuild
  endpoint exists for ops.
- **Provenance**: the catalog commit records its constituent
  `(marketplace, sha)` pairs; `GET /api/catalog` returns them. Facade fetches
  of the catalog land on the ledger like any marketplace (no facade change
  needed — it already resolves and audits by name).
- The catalog name becomes a **reserved marketplace name**: registration
  rejects it (it would collide on the facade path).
- Out of scope (later slices of #11): per-team catalogs and entitlements,
  per-plugin/skill filtering, multi-ref publication.

## Capabilities

### New Capabilities

- `virtual-catalog`: the synthesized catalog, its freshness on publication
  changes, and its provenance/audit story (GW_0061–GW_0063).

### Modified Capabilities

<!-- none: registration gains a reserved-name refusal, but GW_0001's
     requirement text is unchanged — the refusal is part of the new
     GW_0063 requirement -->

## Impact

- **Backend**: new `catalog` package (`CatalogService`, `CatalogController`);
  `ApprovalService.approve` and the re-vetting unpublish path trigger rebuilds;
  `AdminController.registerMarketplace` refuses the reserved name;
  `SkillsGatewayProperties.Catalog` (enabled default true, name default
  `catalog`).
- **No facade change**: `publishedIfServing("catalog")` already serves and
  audits it.
- **API**: `GET /api/catalog`, `POST /api/catalog/rebuild`. OpenAPI snapshot
  and generated TS types regenerate.
- **Docs**: new guide, config reference, API reference, consuming-skills and
  concepts touches, ARCHITECTURE note.
- **Traceability**: GW_0061–GW_0063 + SVC_GW_0061–SVC_GW_0063.
- **Known limit (documented)**: plugin-name prefixing uses
  `<marketplace>-<plugin>`; a contrived pair of names can still collide, in
  which case the rebuild keeps the first (deterministic marketplace-name order)
  and logs the collision.
