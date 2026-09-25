# Proposal: mcp-command-scanning

## Why

The `executable-surface` vetter (#491, PR #502) flags hooks and the code they
fetch at run time. It lists a plugin's MCP servers in the inventory but does
not examine what they run. An MCP server's command runs whenever Claude Code
starts the server, which for a plugin is every session. A server whose command
pipes a download into a shell bypasses quarantine exactly as such a hook does,
and today no finding says so.

The owner has decided how this is judged:

- A server that runs a package runner or an install (`npx -y`, `uvx`,
  `pnpm dlx`, `pip install`, …) is **medium**. It warns and does not block. The
  MCP ecosystem runs almost every server this way, and blocking all of them
  would teach reviewers to switch the vetter off.
- A server that downloads code and executes it is **high**, the same as for a
  hook.
- The rules have their own ids, so each is waivable apart from the hook rules.

## What Changes

- **The `executable-surface` vetter scans every local MCP server command.** A
  local (stdio) server's `command` and `args` are scanned with the classifier
  the hook rules already use. The servers come from the same reading of the
  plugin layout that the inventory uses: `.mcp.json`, and the `mcpServers` of
  `plugin.json` and of the marketplace entry, in their file, inline and array
  forms.
  - `mcp-package-run` (medium, warns): the server runs a package runner or
    installs packages from a registry. A runner pointed at a path inside the
    plugin is not a fetch and stays silent.
  - `mcp-fetch-exec` (high, blocks): the server downloads code and executes it.
    This includes a shell wrapper (`sh -c "curl … | sh"`), a command split
    across `args` entries, and an `${VAR:-default}` whose default is the
    command.
  - Files of the plugin that the server launches are followed and scanned as
    hook scripts are, to the same depth, and their findings carry the same two
    ids at the file's `path:line`.
- **Remote servers are not scanned.** A server reached over a URL (`http`,
  `sse`, `ws`) runs no code on the user's machine, so there is nothing to scan.
- Findings are located at the `path:line` of the server's `command`, so they
  are stamped with a blob, grouped and waived as #500 defined.
- The vetter's description, its summary and the vetting concept page say that
  MCP commands are now scanned. Indirection, encoded payloads and monitors
  remain documented limits.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`:
  - GW_VETTING_0050 — An MCP server that runs a package fetched at run time is
    a medium-severity finding
  - GW_VETTING_0051 — An MCP server that downloads and executes code is a
    high-severity finding
  - GW_VETTING_0052 — Files an MCP server launches from its plugin are scanned
    as hook scripts are

## Impact

- **Backend:** `ingestion/PluginComponents` also returns each local MCP
  server's command and arguments, located at its declaration. The inventory's
  `mcpServers` list and the REST API are unchanged. `vetting/ExecutableSurfaceVetter`
  scans them with `RuntimeFetch`, whose classification is unchanged.
- **API and portal:** none. The findings are ordinary findings on the existing
  verdict.
- **Docs:** the vetting concept page (the `executable-surface` section and its
  known limits) and the glossary if it names the rule ids.
- **Stop rule:** there is no new package, estate object type, role, sweep or
  configuration leaf. It extends a vetter already in the chain, behind the
  switch it already has, and the capability map's "Vetting chain" row does
  not change.
