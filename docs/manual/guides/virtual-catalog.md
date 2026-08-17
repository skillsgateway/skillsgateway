# The virtual catalog

One URL for the whole governed estate. The gateway synthesizes a **catalog**
repository — served by the ordinary facade at `/git/catalog` — containing the
currently approved-and-served snapshot of every marketplace, so a consumer runs
one `claude plugin marketplace add` instead of one per marketplace.

```console
$ claude plugin marketplace add https://skills.corp.example/git/catalog
$ claude plugin install acme-tools-deploy   # <marketplace>-<plugin>
```

## What's inside

Each served marketplace is vendored under a subdirectory named after it, and
one merged manifest ties them together:

```
catalog/
  .claude-plugin/marketplace.json   # merged: names "acme-…", sources "./acme/…"
  acme/…      # acme's approved snapshot, byte-identical
  tools/…     # tools' approved snapshot
```

Plugin names are prefixed with their marketplace (`acme-hello`), and every
source is a relative path into the vendored subtree — the catalog never points
anywhere outside itself.

## Strictly derived content

The catalog is rebuilt from what each marketplace's published repository is
serving **right now** — the same ref the facade serves. Nothing held, rejected,
or revoked can appear in it, and the approval gate is completely untouched: the
catalog adds a view, never a way in.

Rebuilds happen on their own at the two moments the served estate changes:

- an **approval** publishes a snapshot → the catalog gains or updates that
  marketplace;
- a **revocation** unpublishes one → the marketplace leaves the catalog, with
  no operator action.

Each catalog revision is a **parentless commit**: history depth one, so a
retracted constituent is unreachable from every advertised catalog ref the
moment the next revision lands — a consumer cannot fetch yesterday's catalog to
get around a retraction.

With nothing serving at all, the catalog serves a manifest with an empty plugin
list rather than nothing: an empty estate and a broken gateway must look
different.

## Provenance and audit

`GET /api/catalog` returns the served revision and its constituents — the
`(marketplace, SHA)` pairs vendored into it, which are also recorded in the
catalog commit itself. Fetches of `/git/catalog` land on the audit ledger under
the name `catalog` like any marketplace, and a manual
`POST /api/catalog/rebuild` (the on-demand repair path) is ledger-recorded with
the acting identity and, with
[role enforcement](delegated-administration.md) enabled, requires **admin**;
the catalog read stays open to any session.

## Configuration

The catalog name is **reserved**: registering a marketplace called `catalog`
is refused, because the catalog occupies that facade path. See
[Configuration](../reference/configuration.md#virtual-catalog) for the
`skills-gateway.catalog` block.

## Known limit

Prefixing plugin names with the marketplace makes collisions between
marketplaces impossible in practice but not in theory (`a` + `b-c` collides
with `a-b` + `c`). A collision keeps the first plugin in marketplace-name order
and logs the other; if it ever bites, rename one of the plugins upstream.
