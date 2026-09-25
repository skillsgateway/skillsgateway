# Proposal: inventory-and-executable-surface

## Why

A trial deployment of `0.3.0-b1` reviewed the public repository
`pbakaus/impeccable` (#491). Its plugin ships one skill, four agents and three
hooks: session start, after every edit, and end of turn. The Inventory tab
showed the skill and nothing else. The three hooks all run a launcher that, as
its last resort, downloads an engine binary from a release channel into a user
cache, makes it executable and runs it. That binary never passes through
quarantine, so no vetter ever sees it, and nothing on the review surface says
it exists.

A reviewer cannot approve what they cannot see. Code fetched at run time
bypasses the whole quarantine-and-approve model: the snapshot the gateway
pinned is not the code that runs.

The owner's decisions on the issue are settled:

- The Inventory lists every component, as expandable counts that are collapsed
  by default.
- A vetter in the existing chain flags hooks that run automatically, with their
  trigger, and flags code fetched at run time as high severity. It is a vetter
  in the existing chain, not a new capability area.

## What Changes

- **The inventory lists every component.** Each plugin in
  `GET /api/v1/snapshots/{id}/content` gains `commands`, `agents`, `hooks` and
  `mcpServers` beside `skills`. The components are read from their default
  locations and from what `plugin.json` and the marketplace entry declare. Each
  hook carries its trigger (event and matcher), its handler type, what it runs,
  and the `path:line` where it is declared. A malformed component declaration
  leaves that component out; it never fails the inventory, because the policy
  gate builds its facts from the inventory.
- **The portal shows them as expandable counts.** Each plugin reads
  "1 skill · 4 agents · 3 hooks". Each count is a button that expands its list
  in place, and all are collapsed by default. A hook row shows its trigger and
  its command.
- **A new built-in vetter, `executable-surface`**, sits at order 250, between
  `prompt-injection` and `license-scan`. It is on by default and uses the
  existing vetter switch.
  - `auto-run-hook` (medium, warns): every hook Claude Code runs without the
    user invoking anything, with its trigger, at its declaration's `path:line`.
  - `runtime-fetch-exec` (high, blocks): download-and-execute in a hook's
    command, or in a file the hook launches (followed transitively inside the
    snapshot). This covers a pipe to a shell, a shell fed by command or process
    substitution, and a file that both downloads to disk and makes something
    executable.
  - `runtime-package-run` (high, blocks): a hook that runs a package runner
    (`npx`, `uvx`, `pnpm dlx`, …), which fetches its code from a registry at
    run time.
  - `hook-config-unreadable` and `hook-target-unscanned` (medium): a hook
    declaration that cannot be parsed, or a launched file that is binary or
    over the size limit. The vetter reports that it could not look; it does
    not pass silently.
- Findings are ordinary findings. They carry `path:line`, the gateway stamps
  their blob, they group, and they can be waived exactly as #500 defined.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`:
  - GW_INGEST_0045 — The snapshot inventory lists every component a plugin
    declares
  - GW_INGEST_0046 — The portal inventory shows each plugin's components as
    expandable counts
- `snapshot-vetting`:
  - GW_VETTING_0047 — Hooks that run automatically are flagged with their
    trigger
  - GW_VETTING_0048 — Code a hook fetches at run time is a high-severity
    finding
  - GW_VETTING_0049 — Hook declarations and launched files the vetter cannot
    read are reported

## Impact

- **Backend:**
  - `ingestion/`: a new `PluginComponents`, which is the one reading of the
    Claude Code plugin layout. It is pure over a list of paths and a reader.
    `SnapshotContentService.PluginContent` gains four lists.
  - `vetting/`: a new `ExecutableSurfaceVetter`, and `ExternalConnectorProperties`
    reserves its name.
- **API:** additive. There are new fields on `PluginContent`, and new schemas
  `PluginComponent` and `PluginHook`.
- **Portal:** the Inventory tab moves into its own `snapshot-inventory.tsx` with
  a story.
- **Docs:** the vetting concept page (the built-in vetter list and the chain
  diagram), the portal reference, the snapshot content API reference, the
  capability map row, the glossary, and the configuration page's reserved
  names.
- **Stop rule:** there is no new package, estate object type, role, sweep or
  configuration leaf. The vetter is a Spring bean in the existing chain. The
  existing per-vetter switch covers it, and so does its declarative estate
  form, so an estate that does not want it switches it off like any built-in.
  The capability map's "Vetting chain" row absorbs it: the row gains one name.
