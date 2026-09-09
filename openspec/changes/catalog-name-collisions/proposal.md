# Proposal: catalog-name-collisions

## Why

The catalog names a plugin by joining its marketplace name and its plugin name
with a hyphen. **A hyphen is legal inside a marketplace name**, so the join is
not injective: marketplace `a` with plugin `b-c` and marketplace `a-b` with
plugin `c` both ask for `a-b-c`.

The rule that stood published the first claimant in marketplace-name order and
logged the rest. That did not merely drop the loser — it served **the winner's
tree under the name the loser's consumers install**, and it was re-decided on
every rebuild, so registering a marketplace whose name sorts earlier could
change what an existing install name means, retroactively, with no action by
either publisher.

For a product whose premise is that a name denotes exactly one approved
artifact, that is content substitution across a publisher boundary. It is a
defect in shipped code, not a missing feature, and it was found by the project's
own process — ADR 0017 names it — then queued behind eighty tasks of new scope
where nothing distinguished it from them.

## What Changes

- **A name is published only when exactly one served marketplace claims it.**
  The merge became two passes: gather every claim, then publish the uncontested.
  A first-wins rule cannot retract the winner when the second claimant arrives,
  and retracting is the whole of the fix.
- **A contested name is withheld from every claimant.** Omitting a name is
  legitimate; substituting content is not. Choosing a winner is the failure.
- **A within-marketplace duplicate falls out of the same rule**, at no extra
  cost, and is reported the same way.
- **Withholding is reported, because silence would only move the harm** — a
  publisher believes a withheld plugin is being served. Each contested name
  produces a ledger entry (`catalog-name-collision`, actor `catalog-builder`),
  a counter increment, and an entry in a new `collisions` field on the catalog
  read.
- **The collisions ride in the catalog commit message** beside the constituents,
  which is how `GET /api/catalog` — it reconstructs from the served commit and
  nothing else — can report them at all. No table, no migration.
- **Uncontested names are untouched**, including their order in the manifest, so
  an estate with no collision produces exactly the manifest it did before.

## Capabilities

### New Capabilities

**virtual-catalog.** GW_FACADE_0029 — A contested catalog name is published for
nobody.

### Modified Capabilities

_None._ GW_FACADE_0003 — Global virtual catalog states the naming construction
and never claimed the result was unique; it is unchanged, and this adds the
guarantee it left open rather than correcting it.

## Impact

**Served content changes only where a collision already exists**, and there it
changes in the safe direction: a name that was being served under one
publisher's content stops being served at all. A deployment with no colliding
pair sees a byte-identical catalog.

**A plugin published today can stop being published** if it turns out to be in a
colliding pair. That is the point of the change rather than a side effect of it,
and it is why the reporting ships with it: the ledger entry and the `collisions`
field are how its publisher finds out, and an upstream rename is the remedy.

`CatalogInfo` gains a field, which is additive; `openapi.json` and
`types.gen.ts` are regenerated. No path prefix moves and no migration is needed.

## Out of scope

The `virtual-catalogs` change in the backlog specifies more than this. Left
there, deliberately:

- **Revision provenance** — a catalog revision named on the fetch ledger may no
  longer be describable. A real second defect, but it needs a Flyway migration,
  a repository and two endpoints, which is a different shape of change.
- **The registration prefix warning** — a separate surface (registration, the
  portal dialog, estate reconciliation), and the existing duplicate-URL warning
  already sets its pattern.
- Everything in slice 2 and beyond: per-plugin exclusions, per-team catalogs,
  entitlement, multi-ref publication.
