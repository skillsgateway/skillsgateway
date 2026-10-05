# Proposal: correct a marketplace's upstream URL before its first snapshot

## Why

An upstream marketplace's URL cannot be changed: the API has no update for it
and the estate reconciler refuses a declared URL that differs from the stored
one. A typo at registration therefore means removing the marketplace on a
stated reason and registering it again (#596). Until the first snapshot exists
there is nothing for the URL to be provenance of, so that detour protects
nothing.

## What Changes

- **API:** `PUT /api/v1/marketplaces/{name}/url` (admin-only) replaces an
  upstream marketplace's URL while the marketplace has no snapshot in any
  state. The new URL passes the registration gate unchanged: scheme
  allowlist, no embedded credential, duplicate-URL warning, then the
  reachability probe that refuses an upstream it cannot read
  (`GW_INGEST_0040 — Registration refuses an upstream it cannot read`).
  Forge metadata is resolved again for the new URL. The edit goes on the
  audit ledger with the old and new URL.
- **Refused:** a hosted marketplace (it has no upstream), a marketplace with
  any snapshot (remove and re-register keeps provenance intact), a
  non-administrator, an unknown or removed name. A refused edit changes
  nothing.
- **Race with ingestion:** a snapshot is only recorded when the marketplace's
  URL is still the one its content was fetched from, and the edit and the
  snapshot insert serialise on the marketplace row, so content fetched from
  the old URL can never be recorded under the new one.
- **Four-eyes:** the editor becomes the marketplace's recorded registrant,
  because the editor chose the upstream it now ingests from
  (`GW_APPROVAL_0010 — Separation of duties on snapshot approval` reads it).
- **Estate:** a declared URL that differs from the stored one converges
  through the same edit while the marketplace has no snapshot, with an
  unreadable upstream reported rather than refused, as for a declared
  registration. With a snapshot it stays a reconciliation failure.
- **Portal:** an "Edit URL" action in the marketplace's Settings › Upstream
  card for administrators, shown only while the marketplace has no snapshot.
- Additive to the API; not **BREAKING**.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: new `GW_INGEST_0066 — An administrator can correct
  an upstream marketplace's URL until its first snapshot`
  (`SVC_GW_INGEST_0066`, `SVC_GW_INGEST_0066.1` for the ingest race) and
  `GW_INGEST_0067 — Correcting a marketplace's URL from the portal`
  (`SVC_GW_INGEST_0067`).
- `declarative-estate`: `GW_ESTATE_0002 — Declared marketplaces pass the
  registration trust boundary` revised so a differing declared URL is
  converged through the same edit while no snapshot exists, and stays a
  failure once one does; revision bump, `SVC_GW_ESTATE_0002` extended.

## Stop rule

This narrows an existing refusal rather than adding a surface: one route on
the existing marketplace resource, no package, estate object type, grantable
role, scheduled sweep or configuration leaf, so `ConfigSurfaceBudgetTests` and
`ContextBudgetTests` do not move. No webhook event is added; the ledger entry
records the edit.

## Impact

- Code: `admin/MarketplaceRegistrationService.java` (the edit, sharing the
  registration validations), `admin/AdminController.java` (route),
  `persistence/MarketplaceRepository.java` (locked conditional update),
  `persistence/SnapshotRepository.java` + `ingestion/IngestionService.java`
  (snapshot insert conditional on the fetched URL),
  `estate/EstateReconciler.java`, portal
  `pages/marketplace-detail.tsx` plus a new `components/edit-marketplace-url.tsx`.
- API: new route; `openapi.json` and `types.gen.ts` regenerated.
- Docs: `docs/manual/` pages on registering/removing a marketplace, the
  estate reference and the portal reference.
- Requirements: `docs/reqstool/` as listed above.
- Trust boundary: registration. Worked under `.claude/skills/old-coder`.
