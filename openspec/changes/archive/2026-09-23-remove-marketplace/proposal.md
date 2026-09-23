## Why

Nothing can remove a marketplace ([#447](https://github.com/skillsgateway/skillsgateway/issues/447)).
There is no `DELETE`, no `PUT`, the estate reconciler is additive-only, and the
URL is immutable on purpose. A marketplace registered with a typo, one whose
upstream is abandoned, and one whose upstream moved are all permanent rows the
sync sweep keeps stamping. The third case has only one honest answer —
remove and re-register against the new URL — and that answer is useful only if
the name, which is the facade clone path, can come back: `acme` must be able to
return as `acme` or every client that cloned it breaks.

The ledger decision has to be taken now, before 1.0.0 lets real ledger data
accumulate: `fetch_log.marketplace` stores the name, so reusing a name would
conflate two upstreams in the record a malicious-skill recall reads. Rows
written before a disambiguator exists could never be separated afterwards.

## What Changes

- `DELETE /api/v1/marketplaces/{name}`, administrator-only, with a mandatory
  stated reason. It is a **soft retirement**: the row gains `deleted_at` /
  `deleted_by` / `deleted_reason` in the retention idiom, and the row, its
  snapshots, their provenance and every ledger entry survive.
- Removal withdraws every approved snapshot through the existing administrative
  revocation (`GW_APPROVAL_0015 — Administrative revocation of an approved
  snapshot`), serving nothing afterwards — not a second withdrawal mechanism.
  Clients learn of it through `POST /status/v1/snapshots` as with any
  withdrawal.
- A removed marketplace drops out of every name-addressed path: sync selection,
  ingestion, hosted push, the git facade, the virtual catalog, the marketplace
  listing, per-marketplace settings routes, and approval. A snapshot-id read
  (provenance, content) keeps working, because that is the record.
- The name becomes reusable. `marketplaces.name` loses its table-level `UNIQUE`
  in favour of a partial unique index over live rows. A re-registered name is a
  new marketplace with a new id that inherits nothing of its predecessor —
  no served references, no approvals, no approver grants, no hosted lineage.
- **The ledger disambiguator is the marketplace's own id** (owner's decision):
  `fetch_log` gains `marketplace_id`, denormalised with no foreign key, beside
  the name. The audit browse and export carry it.
- Removal takes the name out of every token's publication (push) grant and keeps
  fetch grants, and hides the removed marketplace's vetting settings from the
  admin listings (amended at the owner's decision).
- A `marketplace.removed` lifecycle webhook event.
- API-only: no portal control. Administrative revocation, the act removal is
  built from, is API-only too.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: adds `GW_INGEST_0034 — Administrative marketplace
  removal` and `GW_INGEST_0035 — Reuse of a removed marketplace's name`.
- `audit-export`: adds `GW_AUDIT_0009 — Ledger entries identify the marketplace
  they concern by id`.
- `lifecycle-webhooks`: adds `GW_WEBHOOK_0010 — Marketplace removal event`.

## Impact

- **Trust boundary.** Removal writes to the served set, and a reused name must
  not inherit content or authority. `.claude/skills/old-coder` discipline:
  adversarial and negative tests.
- **API**: one new route, one additive field (`marketplaceId`) on the audit
  entry. Additive within the major.
- **Schema**: `V1__init.sql` edited in place (pre-1.0): retirement columns and
  the partial unique index on `marketplaces`; `marketplace_id` on `fetch_log`.
- **Backend**: `MarketplaceRepository` filters retired rows from every
  name-addressed read; a removal service beside registration; an approval guard;
  a facade registry check; `status/snapshots` prefers the live incarnation;
  re-registration clears a predecessor's leftover served references and hosted
  lineage; retention no longer removes a quarantine pin another incarnation of
  the same name still holds; approver grants on a retired marketplace stop
  matching the name.
- **Declarative estate gains nothing**: removal is an act, not converge-able
  state — the line administrative revocation already drew.
- **Docs**: REST reference, a guide section, the webhook event table, the audit
  ledger reference.
