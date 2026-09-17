# Proposal: vetting-chain-short-circuit

## Why

Every enabled vetter runs against every snapshot and the outcome is the
aggregate, so a vetter's position in the chain changes nothing observable. An
administrator can switch a vetter off (`GW_VETTING_0029 — The vetter on/off
switch never becomes a blanket approval`) but cannot move it, and order is
deploy-time configuration. Issue
[#391](https://github.com/skillsgateway/skillsgateway/issues/391) asks for the
two halves that make order a real control: a chain that can **stop** once a
snapshot is already condemned, so an expensive external vetter — an LLM review
billed per call, a sandbox detonation — is not spent on content `secret-scan`
has already failed, and an **order** an administrator can set so the cheap and
deterministic vetters run first.

Stopping early is a behaviour change with a cost the current model deliberately
avoids: today a reviewer sees everything wrong with a snapshot in one run, and a
run's verdict set is complete by construction. The whole risk of this change is
that a shorter run reads as a cleaner one, so the requirements below are mostly
about what a truncated run may never become.

## What Changes

- **A chain mode (GW_VETTING_0032).** `run-all` (today's behaviour, and the
  default) or `stop-after-fail`, resolved per marketplace, then globally, then to
  the default — the resolution rule the vetter on/off switch already uses
  (GW_VETTING_0029.1), reusing its storage shape rather than inventing a second
  mechanism. Under `stop-after-fail`, once a vetter returns a blocking `FAIL` the
  chain stops; the vetters after it are not run and are recorded with a new
  `NOT_REACHED` verdict state.
- **`NOT_REACHED` is an absence, never a conclusion (GW_VETTING_0032.2).** Like
  `DISABLED` it neither clears nor blocks on its own, but it is a distinct state
  because the cause is different and the ledger has to be able to tell an
  administrator's decision apart from a chain that ran out of road.
- **A run that stopped early stays blocked (GW_VETTING_0032.3).** The
  effective-outcome computation treats any run carrying a `NOT_REACHED` verdict as
  blocked, whatever the waivers say. Waiving the finding that stopped the chain
  therefore cannot clear a run whose later vetters never looked; it can only make
  the next run get further. A truncated run can never read as a clean chain.
- **Administrator-configurable vetter order (GW_VETTING_0033).** Globally and per
  marketplace, resolved by the same rule. Built-ins keep their current positions
  as the default; an override reorders any vetter, built-in or external. The
  resolved order is total and deterministic, and an order naming an unknown vetter
  is refused the way an unknown vetter's toggle is.
- **Both settings are part of the chain identity (GW_VETTING_0033.2).** A run
  records the mode and the resolved order alongside `vetter@version`, so
  `GW_VETTING_0012 — Continuous re-vetting of approved snapshots` keeps its
  ability to attribute a changed answer about unchanged content to the chain.
  Both are readable on the marketplace's effective-chain surface.
- **Both are audited, administrator-only acts (GW_VETTING_0032.4,
  GW_VETTING_0033.1)**, like the toggle: admin-only to set *and* to read, and
  every change writes a ledger entry naming the scope and the new value.
- **Portal (GW_VETTING_0034).** The marketplace vetting-chain card gains the mode
  control and keyboard-operable reordering (move-up/move-down buttons with
  accessible names, no drag-and-drop); the snapshot flow renders `NOT_REACHED`
  distinctly and its headline says the chain stopped early and at which step.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `snapshot-vetting`: the chain gains an administrator-set mode that stops it
  after a blocking failure and an administrator-set vetter order, both resolved
  per marketplace then globally then by default, both recorded in a run's chain
  identity; a new `NOT_REACHED` verdict state records the vetters the chain
  stopped short of; and the effective-outcome rule is extended so a run that
  stopped early is blocked until a complete run replaces it. The portal
  requirement for the chain flow (GW_VETTING_0031) lives in this capability too,
  and gains the mode control, keyboard-operable reordering, and a flow that shows
  a chain which stopped early.

## Impact

- **DB**: new migration `V6__vetting_chain_settings.sql` — add `not_reached` to
  the `vetting_verdict_state` enum and two tables (`vetting_chain_modes`,
  `vetting_chain_orders`) shaped like `vetter_toggles`.
- **Backend**: `VerdictState` (+`NOT_REACHED`), `Verdict` (`notReached(...)`),
  `WaiverEvaluation` (a run carrying `NOT_REACHED` is blocked), new
  `ChainMode`/`ChainModeSetting`/`ChainOrderSetting`/`VettingChainSettingsRepository`/
  `VettingChainSettingsService`/`VettingChainSettingsController`/`ChainSettingsView`,
  `VettingService` (resolved order, short-circuit, chain identity per marketplace),
  `VetterToggleController` (the effective chain in resolved order).
- **API** (additive): `PUT /api/vetting/chain-mode`, `PUT /api/vetting/chain-order`,
  `GET /api/vetting/chain-settings`,
  `GET /api/marketplaces/{name}/vetting-chain-settings`. The existing
  `GET /api/marketplaces/{name}/vetting-chain` keeps its array response and simply
  returns the resolved order.
- **Portal**: `marketplace-vetting-chain.tsx`, `vetting-flow.tsx`,
  `lib/vetting-flow.ts`, `api/queries.ts`, generated `types.gen.ts`.
- **Estate**: deliberately API-only, following the precedent the vetter toggle
  recorded; see design.md, "Estate integration".
- **Trust boundary**: this changes how the approval gate's evidence is produced →
  `.claude/skills/old-coder` discipline, adversarial and negative tests, evidence
  report.
- **Docs** (same PR): `concepts/vetting.md`, `concepts/glossary.md`,
  `reference/api/marketplaces.md`, `reference/portal.md`, `capability-map.md`.
