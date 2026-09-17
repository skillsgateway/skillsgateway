## Why

The three chain settings — the vetter on/off switch (`GW_VETTING_0029 — An
administrator can enable and disable individual vetters`), the chain mode
(`GW_VETTING_0032 — An administrator can stop the vetting chain after a blocking
failure`) and the vetter order (`GW_VETTING_0033 — An administrator can order the
vetting chain`) — all resolve per marketplace, then globally, then to a default.
The portal only ever shows the per-marketplace half, on the marketplace detail
page (`GW_VETTING_0034 — Portal control of the chain mode and the vetter order,
and a chain that stopped early`). Setting the global default, seeing which
marketplaces depart from it, and changing several at once are all reachable only
with `curl` today, which means the estate-wide half of an administrator-only
control has no administrative surface at all.

## What Changes

- A new admin-only **Vetting** page at `/vetting`, in the Governance group of the
  sidebar, with three sections: the default chain, the overrides, and bulk edit.
  A non-administrator sees neither the nav entry nor the page's data, only a
  clean refusal.
- **The default chain** — mode, vetter order and each vetter's on/off state as
  they apply to a marketplace with no override — using the controls the
  marketplace card already carries, scoped globally (the existing `PUT` bodies
  with `marketplace` omitted). Two additive reads,
  `GET /api/vetting/global-chain` and `GET /api/vetting/global-chain-settings`,
  mirror the per-marketplace pair so the resolution rule stays on the server.
- **The overrides** — one row per marketplace that departs from the default,
  saying what it overrides and offering *clear override*.
- **Clearing an override** is not expressible by today's API: a `PUT` can only
  write a value, and an override equal to the default is not the same thing as
  no override — the source has to go back to `GLOBAL`/`DEFAULT`. One additive
  endpoint, `POST /api/vetting/chain-settings/bulk`, carries the four estate
  actions including the clear, and the single-row clear is that endpoint applied
  to one marketplace.
- **Bulk edit** — select marketplaces (or all), then apply one change: set mode,
  set order, switch a vetter on or off, or clear overrides. A confirm step lists
  the affected marketplaces and the before/after; afterwards the result is
  reported per marketplace. A request that any marketplace refused answers
  `207 Multi-Status`, never `200`.
- Each affected marketplace still gets **its own ledger entry**, carrying the
  shared reason and a **correlation id**, so an auditor can see the entries as
  one deliberate act rather than a loop.
- The marketplace detail card keeps working and gains a link to the new page.
- These settings remain deliberately **API-only** for
  `skills-gateway.estate.*`; the design restates why rather than leaving it
  inferred.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `snapshot-vetting`: three new requirements —
  `GW_VETTING_0035 — Estate-wide portal governance of the vetting chain`,
  `GW_VETTING_0036 — An administrator can clear a marketplace's chain override`,
  `GW_VETTING_0037 — One chain change applied to several marketplaces as a single
  audited act`.

## Impact

- **API (additive within the major):** `GET /api/vetting/global-chain`,
  `GET /api/vetting/global-chain-settings`,
  `POST /api/vetting/chain-settings/bulk`.
- **Server:** `VetterToggleController`, `VettingChainSettingsController`, their
  services and repositories (delete-by-scope), a new
  `VettingChainBulkService`; new ledger events for the three clears.
- **Portal:** new page `src/main/frontend/src/pages/vetting.tsx`, the route,
  the sidebar group, and generalisation of `ModeControls`, `OrderControls` and
  the vetter toggle control to a global scope.
- **Docs:** `docs/manual/reference/portal.md`,
  `docs/manual/reference/api/marketplaces.md`,
  `docs/manual/concepts/vetting.md`, `docs/manual/capability-map.md`,
  `docs/manual/guides/delegated-administration.md`.
- **Traceability:** `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml`; regenerated `openapi.json` and
  `types.gen.ts`.
- **No estate objects and no schema-breaking migration** — the two settings
  tables gain deletes, not columns.
