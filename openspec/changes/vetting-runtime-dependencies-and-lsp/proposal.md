# Proposal: vetting-runtime-dependencies-and-lsp

## Why

The vetting concept page names two paths by which code that was never in the
pinned snapshot runs on a user's machine without any finding (PRs #521 and
#528):

- **Dependencies a skill installs.** `executable-surface` scans hooks and MCP
  server commands for package installs, but not a skill's own scripts, not a
  `SKILL.md` that tells the agent to run `npm install`, and not dependency
  manifests. A plugin observed in the wild ships a skill with a `package.json`
  and tells the user to run `npm i` after every update. Claude Code documents
  `${CLAUDE_PLUGIN_DATA}` as the place for "installed dependencies such as
  `node_modules`", so this is a sanctioned pattern, not an edge case.
- **LSP servers.** A plugin's `lspServers` (and its `.lsp.json`) start a local
  process whenever a matching file is opened, without anyone invoking it.
  Nothing in the gateway reads the field: the server is neither in the
  inventory nor scanned.

Categorising marketplaces ("content" versus "program", or a set of flags) was
considered and rejected. The flags duplicate findings `executable-surface`
already produces, they measure different risks and cannot be ordered, and they
would add a portal concept with no mechanical backing. The two gaps above are
the part of that idea the gateway cannot already see.

## What Changes

- **`runtime-dependency` (medium, warns).** A new `executable-surface` rule, raised for:
  - a dependency manifest in a plugin that declares dependencies
    (`package.json`, `requirements.txt`, `pyproject.toml`, `Cargo.toml`). The
    finding says whether a lockfile sits beside it;
  - a package install or package runner, in the shapes `runtime-package-run`
    already recognises, in a skill's scripts, or in a code block or code span of
    a skill's Markdown or of a command or agent.

  Medium, as for MCP servers: runtime installs are a documented plugin
  pattern, and blocking them would teach reviewers to switch the vetter off.
- **`skill-fetch-exec` (high, blocks).** Download-and-execute in the same
  skill, command and agent files, in the shapes `runtime-fetch-exec` already
  recognises. It blocks, as it does in a hook, an MCP server or an LSP server.
- **LSP servers are read and scanned.** The layout reader reads `.lsp.json`
  and the `lspServers` of `plugin.json` and of the marketplace entry. The
  inventory lists each server beside the plugin's MCP servers. Each server's
  command is scanned as a local MCP server's is, under its own ids:
  `lsp-package-run` (medium) and `lsp-fetch-exec` (high). Files of the plugin
  that the command launches are followed.
- **Docs:** the two limitations added by PRs #521 and #528 are replaced by a
  description of the new rules. Their remaining limits (text rules, not a
  resolver) stay documented.

**Not in scope:** vulnerability lookup (OSV), which belongs in an external
vetter. Monitors, `bin/` executables, and following `npm start`-style scripts
into `package.json` are also out.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`:
  - GW_VETTING_0053 — A dependency manifest in a plugin is a medium-severity finding
  - GW_VETTING_0054 — A package install in a plugin's skill, command or agent
    files is a medium-severity finding
  - GW_VETTING_0055 — LSP server commands are scanned as MCP server commands are
  - GW_VETTING_0056 — Download-and-execute in a plugin's skill, command or
    agent files is a high-severity finding
- `marketplace-ingestion`:
  - GW_INGEST_0045 — The snapshot inventory lists every component a plugin
    declares: now includes LSP servers

## Impact

- **Backend:** `ingestion/PluginComponents` gains an LSP reading beside the
  MCP reading (`lspServers`, `lspCommands`). `vetting/ExecutableSurfaceVetter`
  gains the four rules. `RuntimeFetch` is reused and its classification does
  not change.
- **API:** the inventory's plugin component gains an `lspServers` list. The
  change is additive within the major, so no path prefix moves.
- **Portal:** the snapshot inventory shows LSP servers as it shows MCP servers.
- **Docs:** `concepts/vetting.md`, and `reference/portal.md` where it lists
  inventory kinds.
- **Stop rule:** no new package, estate object type, role, sweep or
  configuration leaf. It extends a vetter already in the chain, behind the
  switch it already has, and the capability map's "Vetting chain" row does
  not change.
