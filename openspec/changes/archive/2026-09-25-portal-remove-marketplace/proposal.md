# Proposal: portal-remove-marketplace

## Why

A trial deployment of `0.3.0-b1` found that a marketplace can be removed only
through the API, added by #477. The tester had to call
`DELETE /api/v1/marketplaces/{name}` from the browser devtools (#493). The
removal itself is built and tested; what is missing is the control an
administrator looks for on the marketplace's own page.

The issue settles the shape: a **Remove** action on the marketplace's Settings
section, calling the existing endpoint, admin-only, with a confirmation that
names the marketplace. Editing the clone URL is parked, because removing and
re-registering the name covers it without a second way through the
registration trust boundary.

## What Changes

- **A Remove control on Settings, for administrators.** A "Remove marketplace"
  card at the end of the marketplace's Settings section opens a confirmation
  dialog. The dialog names the marketplace and states what removal does: every
  approved snapshot is withdrawn, so the facade serves nothing; publication
  grants for the name are removed and fetch grants kept; the record, snapshots
  and ledger stay; and the name can be registered again as a new marketplace.
- **The reason the server requires.** The dialog asks for the reason that
  `DELETE` requires, and its confirm control is disabled until a non-blank reason
  is written, as the form rules require.
- **Afterwards.** The portal goes to the marketplace list with a toast saying
  how many approved snapshots were withdrawn. A refusal is shown as a toast
  carrying the server's message, and the dialog stays open.
- **A declared marketplace is not offered for removal.** If the last estate
  reconciliation report lists the marketplace as declared, the control is
  disabled, and the card says why: the next reconciliation would register the
  name again as a new, empty marketplace, so the declaration has to be removed
  first.
- A user who is not an administrator does not see the card, as with the vetting
  chain on the same page.

No server, API, schema, configuration, role or estate change. The portal reads
the existing estate report (`GET /api/v1/estate`) and calls the existing
removal endpoint.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`:
  - GW_INGEST_0048 — Removing a marketplace from the portal
  - GW_INGEST_0049 — The portal does not offer to remove a declared marketplace

## Impact

- Portal: `pages/marketplace-detail.tsx` (Settings), a new
  `components/remove-marketplace.tsx`, `api/queries.ts` (removal mutation, estate
  report read), MSW handlers, component tests and a story, and an e2e step in
  `e2e/portal.spec.ts`.
- Documentation: `reference/portal.md`, the "Removing a marketplace" section of
  `guides/registering-a-marketplace.md`, and the estate guide's sentence that
  removal is API-only.
- `docs/manual/capability-map.md` absorbs this: it is a portal surface for an
  existing capability, with no new package, object type, role, sweep or
  configuration leaf.
