# Proposal: every component file the vetter cannot read is reported

## Why

The executable-surface vetter reads several kinds of file for what a plugin
runs: hook files, `plugin.json`, `.mcp.json` and `.lsp.json` (plus the server
files `plugin.json` names), and skill and agent files, whose frontmatter can
declare hooks.

When a hook file or `plugin.json` is over the scan size limit, or is not valid
JSON, the vetter raises `hook-config-unreadable`. The other files are dropped
silently. As a result:

- the servers or hooks they declare are absent from the verdict and from the
  inventory;
- the verdict reads as though the plugin declared none.

## What Changes

- New `GW_VETTING_0066 — MCP and LSP server files and skill or agent files the
  vetter cannot read are reported`. A server declaration file that is over the
  scan size limit or not valid JSON, and a skill or agent file over the limit,
  each raise one medium `hook-config-unreadable` finding naming the file. The
  message says what could not be read: MCP servers, LSP servers, or frontmatter
  hooks.
- New `SVC_GW_VETTING_0069` covers the four oversized cases, the two
  unparseable ones, and the control where readable or absent files raise
  nothing.
- Not **BREAKING**: no API, schema or configuration change. A snapshot with
  such a file gains a medium finding it did not have.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`: adds `GW_VETTING_0066`.

## Impact

- `PluginComponents.Reader.json` and `frontmatterHooks`. One assertion in
  `PluginComponentsTests` encoded the old silence about a malformed
  `.mcp.json`; it now expects the second problem too.
- Docs: `concepts/vetting.md`.

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.
