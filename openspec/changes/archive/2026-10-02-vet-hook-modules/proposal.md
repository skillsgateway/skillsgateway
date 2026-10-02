# Proposal: inventory and vet Claude Code hook modules (mods)

## Why

Claude Code plugins can now ship **hook modules** ("mods"): `hooks/hooks.json`
names TypeScript or JavaScript modules (`{"modules": ["./register.tsx"]}`)
instead of an event map. A module's `register(on, options)` hooks engine events
in-process for every session, `tool.call` and `prompt.compose` among them, and
reaches programs, files and models through `$`. `PluginComponents` reads that
file as an event map, takes `modules` for an event name, finds no handlers and
records zero hooks. A reviewer is shown a mod as a plugin with no executable
surface and could approve it on that basis: the same failure #491 fixed for
command hooks (#577).

## What Changes

- **Inventory:** `PluginComponents` reads `modules` wherever it reads a hooks
  object (`hooks/hooks.json`, a `plugin.json` or marketplace-entry `hooks` path
  or inline object). Each module becomes a `hookModules` entry of the plugin's
  inventory: its path, the `hooks.json` line that names it, the events it
  registers (`on('…')` calls a source scan can see, with `path:line`), the `$`
  interfaces it reaches (`$.process`, `$.model`, … with `path:line`), and the
  plugin files it imports, followed transitively.
- **Portal:** the inventory gains a "hook module" kind beside hooks, showing
  each module's path, events and interfaces.
- **Vetting** (`executable-surface`):
  - every hook module is a medium `auto-run-module` finding: it runs in every
    session without anyone invoking it, naming its events;
  - `module-steers-agent` (medium) where it hooks `tool.call` or
    `prompt.compose`, `module-runs-process` (medium) where it reaches
    `$.process`, `module-calls-model` (low) where it reaches `$.model`, each at
    the line that does it, so a waiver for one capability does not cover the
    next one a later version adds;
  - the module and every plugin file it imports are scanned with the existing
    run-time-fetch rules (high, as a hook's launched script is);
  - a module or import that is missing, binary, over the scan size limit, or a
    package outside the plugin is a medium `hook-target-unscanned` finding.
- **Unknown shapes fail loudly:** a `hooks.json` (or inline hooks object) key
  whose value is neither a recognised key nor an event's array of matcher
  groups is a `hook-config-unreadable` finding instead of an empty result, so
  the next format change cannot slip through the same way.
- Additive to the API (`hookModules` on `PluginContent`); not **BREAKING**.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: new `GW_INGEST_0065 — The inventory lists each hook
  module a plugin declares, with the events it registers and the interfaces it
  reaches` (`SVC_GW_INGEST_0065`). `GW_INGEST_0046 — The portal inventory shows
  each plugin's components as expandable counts` is revised to name hook
  modules (and the LSP servers it already shows), with `SVC_GW_INGEST_0046`
  extended; revision bump.
- `snapshot-vetting`: new
  `GW_VETTING_0057 — A hook module is flagged as code that runs in every session`,
  `GW_VETTING_0058 — A hook module that can steer the agent, run programs or call models is flagged for each`,
  `GW_VETTING_0059 — A hook module and the plugin files it imports are scanned, and what cannot be read is reported`,
  `GW_VETTING_0060 — A hook declaration of a shape the reader does not recognise is reported`,
  each with its `SVC_GW_VETTING_*` case.

## Stop rule

This extends existing surfaces only: the `PluginComponents` reader, the
`executable-surface` vetter, the inventory API and the portal inventory. It adds
no package, estate object type, grantable role, scheduled sweep or
configuration leaf, so `ConfigSurfaceBudgetTests` and `ContextBudgetTests` do
not move.

## Impact

- Code: `ingestion/PluginComponents.java` (plus a `HookModules` source scanner
  beside it), `ingestion/SnapshotContentService.java`,
  `vetting/ExecutableSurfaceVetter.java`,
  `src/main/frontend/src/components/snapshot-inventory.tsx`.
- API: `PluginContent.hookModules` (additive); `openapi.json` and
  `types.gen.ts` regenerated.
- Docs: `docs/manual/concepts/vetting.md` (`executable-surface` rules and
  limits), `docs/manual/reference/portal.md` (inventory kinds).
- Requirements: `docs/reqstool/` as listed above.
