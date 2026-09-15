# Proposal: vetting-chain-flow

## Why

The vetting chain is the product's central mechanism, and the portal renders it as
a flat list. A reviewer opening a snapshot sees a stack of per-connector blocks in
no particular visual order, with no indication of where the snapshot *is* in the
chain, which connector stopped it, or what the aggregation did with the rest. The
chain is an ordered pipeline in the code and a list on the screen.

Issue #377 asks for the pipeline to be drawn as one: ingest → each connector in run
order → the aggregated outcome → the approval gate, with each node carrying its
verdict state and opening to its own evidence. The same drawing, without verdicts,
answers a question the portal cannot answer at all today: *which connectors actually
run for this marketplace, and why is one of them off?* `GET /api/vetting/connector-toggles`
returns rows keyed by marketplace id with no chain, no order and no defaults, so an
administrator cannot read the effective chain anywhere — neither in the portal nor
over the API.

## What Changes

- **A `VettingFlow` component.** The chain as an ordered, wrapping flow of nodes
  built from the existing primitives — no graph library, no canvas, no new colour.
  Each node states its verdict as icon **and** text **and** the theme's existing
  colour language; an external connector is marked as external. Every node is a
  button that opens its detail in the existing Dialog primitive: findings with their
  waived-ness, the connector's version and self-description, and — for a node the
  chain skipped — that an administrator disabled it and where to read why. The
  outcome node explains the aggregation from the data the API already returns
  (blocking connectors, uncovered findings).
- **The snapshot vetting report gains the flow** above its per-connector list. The
  list stays: the flow is the overview, the list is the detail. `DISABLED` is added
  to the report's verdict icon and badge, which currently fall through to "unknown".
- **A marketplace's effective chain, for administrators.** The marketplace detail
  page gains an administrator-only card drawing the same flow without verdicts: every
  connector in chain order with its resolved enabled state and whether that state
  comes from this marketplace, the global setting, or the default — with the existing
  `PUT /api/vetting/connectors/{name}/toggle` behind each node.
- **One additive read endpoint**, `GET /api/marketplaces/{name}/vetting-chain`,
  administrator-only, because the effective chain is not derivable from what the API
  returns today (see the design). No new server-side vetting behaviour: the endpoint
  reports the resolution rule `ConnectorToggleService` already applies.
- **`ConnectorView` gains `version` and `external`** (additive) so the snapshot flow
  can name what produced a verdict.

## Capabilities

### New Capabilities

_None._ Both new requirements belong to `snapshot-vetting`, the capability that
already owns the chain, its portal surface (GW_VETTING_0005 — Portal vetting surface)
and the connector on/off switch (GW_VETTING_0029 — The connector on/off switch never
becomes a blanket approval).

### Modified Capabilities

- `snapshot-vetting`: adds GW_VETTING_0031 — Portal vetting chain flow, and
  GW_VETTING_0029.5 — Administrator surface for a marketplace's effective vetting
  chain. Nothing existing is weakened: the aggregation, the approval gate, the
  toggle's semantics and GW_VETTING_0029.4 — Every toggle is an audited,
  administrator-only action are untouched, and the new read endpoint sits behind the
  same administrator check as the settings list it complements.

## Impact

- **API**: one additive endpoint (`GET /api/marketplaces/{name}/vetting-chain`) and
  two additive fields on `ConnectorView`. Additive within the major, so the path
  prefix does not move; `openapi.json` and `src/api/types.gen.ts` are regenerated.
- **Backend**: `ConnectorToggleService` (the resolution rule gains a form that names
  *where* the state came from, with `enabled(...)` delegating to it so there is still
  one rule), `ConnectorToggleController` (the new read), `VettingController`
  (`ConnectorView` fields), new `ChainConnectorView` / `ChainSource`.
- **Portal**: new `src/lib/vetting-flow.ts` (pure derivation), `src/components/vetting-flow.tsx`,
  `src/components/marketplace-vetting-chain.tsx`; changed `src/components/vetting-report.tsx`,
  `src/pages/marketplace-detail.tsx`, `src/api/queries.ts`, `src/test/msw-handlers.ts`.
- **Requirements**: two added (GW_VETTING_0031, GW_VETTING_0029.5) with their SVCs.
- **Docs** (same PR): `docs/manual/reference/portal.md`, `docs/manual/concepts/vetting.md`,
  `docs/manual/reference/api/marketplaces.md`.
- **Trust boundary**: the new endpoint is a *read* of connector settings, which
  GW_VETTING_0029.4 already reserves to administrators; it is behind the same
  `roleService.requireAdmin` call as the existing settings list, and a negative test
  asserts a non-administrator is refused. Nothing about approval, facade auth or the
  registration allowlist changes.
- **Declarative estate**: no new API-managed runtime state — the endpoint reads the
  `connector_toggles` state that already exists, so `skills-gateway.estate.*` needs no
  extension.
