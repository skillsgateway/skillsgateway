# Design: mcp-command-scanning

## Context

The motivation and the owner's severity decisions are in proposal.md. The
current state, from `inventory-and-executable-surface`:

- `PluginComponents` reads a plugin's MCP servers from `.mcp.json` and from the
  `mcpServers` of `plugin.json` and the marketplace entry (a path to a JSON
  file, an inline map, a bundle, or an array of these). It returns each as a
  name and a location. What the server runs is not kept.
- `ExecutableSurfaceVetter` reads hooks through the same class, scans each
  command hook with `RuntimeFetch`, and follows the files of the plugin that the
  command names, to a depth of three.
- `RuntimeFetch` answers two rules over text: `runtime-fetch-exec` (download
  and execute) and `runtime-package-run` (a package runner or install in
  command position). The vetter maps both to high for hooks.

An MCP server entry in Claude Code is either local or remote. A local (stdio)
server has a `command` and optional `args` and `env`, and no `type` or
`type: "stdio"`. A remote server has `type` `http`, `sse` or `ws` and a `url`.
`command`, `args`, `env`, `url` and `headers` support `${VAR}` and
`${VAR:-default}` expansion. `${CLAUDE_PLUGIN_ROOT}` names the plugin's
directory.

## Goals / Non-Goals

**Goals:**

- Every local MCP server command the inventory lists is scanned by the same
  classifier as a hook, under its own rule ids and severities.
- The finding sits at the line of the server's `command`, so it has a blob and
  groups and waives like any finding.

**Non-Goals:**

- **Remote servers.** They run no code on the user's machine. A `command` on an
  entry whose `type` is remote is ignored by Claude Code, and by the vetter.
- **Bundles** (`.mcpb`, `.dxt`). A bundle is an archive whose manifest is
  inside it; nothing in this vetter reads archives. It stays in the inventory.
- **Package-manager run scripts.** `bun run start` or `npm start` runs a script
  from `package.json`. Following it is a new resolution step (read the
  manifest, look up the script, resolve its working directory), not the
  path-following #502 does for hooks. It is recorded as a known limit, with the
  measured count.
- **New classifications.** `RuntimeFetch` is reused unchanged. A container
  runner (`docker run image`) pulls an image from a registry at run time, but
  it is not a package runner in today's classifier, and adding one would change
  the hook rule too. It is recorded as a known limit.
- **A finding for every MCP server.** The inventory already lists them. Adding
  an `auto-run-hook`-style warning per server would put a medium on nearly
  every marketplace for no new information.

## Decisions

### D1. `PluginComponents` returns local server commands beside the list

`Components` gains `mcpCommands`: for each local server, its name, the
`path:line` of its `command` value, the command and the arguments as strings.
The existing `mcpServers` list and the REST schema are unchanged, so the
inventory API does not move.

A server is local when its value is an object with a string `command` and no
`type`, or `type: "stdio"`. The same three sources are read, in the same
order, as for the inventory's list, so the two cannot disagree about which
servers exist.

*Alternative:* add the command to the `PluginComponent` schema and show it in
the portal. Rejected for now. It is an API and portal change the owner did not
ask for, and the finding already quotes the command.

### D2. What is scanned for a server

One text is built per server and scanned with `RuntimeFetch` as a command, not
as a file:

1. Every `${VAR:-default}` is replaced by its default. The default is what
   runs when the variable is unset, which is the case the reviewer is
   approving. A bare `${VAR}` is left as written.
2. The first line is the program followed by the arguments, joined by spaces.
   A program given as a path outside the plugin (`/usr/local/bin/npx`) is
   reduced to its file name, so a runner is still in command position.
3. `env` is a launcher, not the program: `env` and the `NAME=value` and option
   arguments after it are dropped.
4. A shell wrapper hands its script over as an argument: `sh`, `bash`, `zsh`,
   `dash` or `ksh` with `-c` take the next argument; `cmd` with `/c` or `/k`,
   and `powershell` or `pwsh` with `-c` or `-Command`, take the remaining
   arguments. That script is added as a line of its own, so a runner at its
   start is in command position.

### D3. A package runner pointed at the plugin is not a fetch

Where the program is a runner (`npx`, `bunx`, `pnpx`, `uvx`, `pnpm dlx`,
`yarn dlx`, `npm exec`) and its first operand is a path inside the plugin
(`./…`, `../…` that stays inside, or `${CLAUDE_PLUGIN_ROOT}/…`), the first
line's `mcp-package-run` match is dropped. This is applied to MCP commands
only. The hook rule is unchanged, because changing it is outside what the
owner approved.

### D4. Rule ids and severities

| `RuntimeFetch` rule | MCP rule | Severity | Verdict |
| --- | --- | --- | --- |
| `runtime-fetch-exec` | `mcp-fetch-exec` | high | fails |
| `runtime-package-run` | `mcp-package-run` | medium | warns |

The ids are distinct from the hook rules, so a waiver or an estate that
accepts `mcp-package-run` for a marketplace path does not accept a hook that
does the same. The message names the server: "MCP server 'firebase' runs
'npx', which fetches…".

### D5. Launched files are followed as a hook's are

The same reference resolution follows files of the plugin that the server's
text names, with the same depth limit of three. A finding in a followed file is
located at that file's `path:line` and carries the MCP rule id and severity.

Hook scripts and MCP scripts keep separate visited sets. A script that both a
hook and a server launch is scanned for each, so the hook's high
`runtime-package-run` is never lost because the server's medium scan reached
the file first.

A binary or oversized file a server launches is not reported. A server whose
command is a compiled binary shipped in the plugin is ordinary, its bytes are
pinned in the snapshot, and reporting every one would be noise. The hook rule
`hook-target-unscanned` is unchanged.

### D6. The vetter's summary and description

The summary counts MCP server commands alongside hooks and launched files, and
says monitor commands are not examined. The description says MCP commands are
scanned, and names indirection, encoding and monitors as limits.

## Risks / Trade-offs

- [A shell wrapper or runner spelled in a way D2 does not unwrap] → The
  download-and-execute patterns match anywhere on a line, so the high rule does
  not depend on command position. Only the medium runner rule does.
- [A server that fetches through `package.json` scripts or a container image
  is not flagged] → Named as known limits on the vetting page, with the
  numbers measured on `anthropics/claude-plugins-official`.
- [Medium findings on most MCP-bearing marketplaces] → That is the owner's
  decision: a warning a reviewer reads, not a block.
