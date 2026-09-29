# Design: vetting-runtime-dependencies-and-lsp

## Context

Motivation and scope: see proposal.md. The current state:

- `PluginComponents` is the one reading of the plugin layout (GW_INGEST_0045 —
  The snapshot inventory lists every component a plugin declares). It reads
  commands, agents, hooks and MCP servers from their default locations, from
  `plugin.json` and from the marketplace entry. It also returns `mcpCommands`
  (name, `path:line` of `command`, command, arguments) for the vetter.
  `skillFiles` finds every `SKILL.md` under `skills/` and the declared skills
  directories.
- `ExecutableSurfaceVetter` scans hook commands and MCP commands with
  `RuntimeFetch` and follows launched files to depth 3, with separate visited
  sets for hooks (`scanned`) and MCP (`mcpScanned`). The MCP path already
  normalises a command line (variable defaults, runner by path, `env`, shell
  wrappers) and exempts a runner pointed inside the plugin.
- `RuntimeFetch` classifies text into `runtime-fetch-exec` and
  `runtime-package-run` per logical line. It is reused unchanged.

Claude Code's LSP configuration (plugins reference, `lspServers`): `.lsp.json`
at the plugin root loads first. Then `lspServers` in `plugin.json`, and in a
marketplace entry, merge into it, each given as a `.json` path, an inline
map, or an array of either. A later name replaces an earlier one. Each
config is a strict object. `command` and `extensionToLanguage` are required;
`args`, `env`, `transport` (`stdio` | `socket`, both run a local process) and
others are optional. `${…}` resolves in `command`, `args`, `env` and
`workspaceFolder`.

## Goals / Non-Goals

**Goals:**

- Every LSP server a plugin declares is listed in the inventory and has its
  command scanned, exactly as a local MCP server is.
- Code a plugin's skills, commands or agents install from a registry, or
  download and execute, is a finding at a `path:line`, so it is stamped with a blob, grouped and waived
  like any other finding.

**Non-Goals:**

- **Vulnerability or malware lookup** in dependencies. That needs a network
  and an advisory database, which belongs in an external vetter.
- **Resolving a manifest.** No lockfile is parsed, no version range is
  evaluated, and `npm start` is not looked up in `package.json`.
- **Monitors and `bin/`** stay as today: listed or ignored, not scanned.
- **A finding per LSP server.** As with MCP, the inventory lists them. A
  warning on every one would add no information.

## Decisions

### D1. LSP servers are read as MCP servers are

`PluginComponents` reads LSP configuration from the same three places, in the
order Claude Code loads it: `.lsp.json`, then `plugin.json` `lspServers`, then
the marketplace entry's. It accepts the same path, inline and array forms.
`Components` gains `lspServers` (a `Component` per server, for the inventory)
and `lspCommands` (an `McpCommand`-shaped record, for the vetter). No `type`
test applies: every LSP server is local. An entry without a string `command`
is a problem, not a server, as an unreadable hook is.

*Alternative:* a generic "process component" record shared by MCP and LSP.
Rejected: the two differ in shape (remote MCP, bundles) and the inventory keeps
them apart. The shared part is the command scanning, which D2 reuses.

### D2. LSP commands go through the MCP command path

The MCP normalisation, the in-plugin runner exemption and the launched-file
following are reused, with the rule ids mapped per source:

| `RuntimeFetch` rule | MCP | LSP | Skill, command, agent (D4) | Severity |
| --- | --- | --- | --- | --- |
| `runtime-fetch-exec` | `mcp-fetch-exec` | `lsp-fetch-exec` | `skill-fetch-exec` | high, blocks |
| `runtime-package-run` | `mcp-package-run` | `lsp-package-run` | `runtime-dependency` | medium, warns |

LSP gets its own visited set, for the reason MCP has one: a script that both a
hook and an LSP server launch keeps the hook's high finding. A binary the
server runs is not reported. A language server is almost always a binary, and
`gopls` on `PATH` is the user's install, not the plugin's.

### D3. Which manifests are reported

`runtime-dependency` is raised once per manifest file under a plugin root
that declares at least one dependency:

| File | Declares a dependency when |
| --- | --- |
| `package.json` | `dependencies`, `devDependencies`, `optionalDependencies` or `peerDependencies` is a non-empty object |
| `requirements*.txt` | a line is neither blank, a comment, nor an option other than `-r`/`-c` |
| `pyproject.toml` | `[project]` has a non-empty `dependencies` array, or `[tool.poetry.dependencies]` has a key other than `python`, or `[dependency-groups]` has an entry |
| `Cargo.toml` | `[dependencies]`, `[dev-dependencies]`, `[build-dependencies]` or `[workspace.dependencies]` has an entry |

A manifest with no dependencies is metadata, so it is not reported. Manifests
inside `node_modules/`, `.venv/`, `venv/`, `site-packages/` and `target/` are
not reported. They are vendored content, not declarations, and a vendored
`node_modules` would otherwise yield hundreds of findings.

The finding sits at the line of the declaring key or section header, not at
line 1, so its blob and group follow the declaration. The message names the
ecosystem and whether a lockfile sits in the same directory
(`package-lock.json`, `npm-shrinkwrap.json`, `pnpm-lock.yaml`, `yarn.lock`,
`bun.lock`, `bun.lockb`; `uv.lock`, `poetry.lock`, `pdm.lock`,
`Pipfile.lock`; `Cargo.lock`). A lockfile does not lower the severity: it
fixes versions, but the code is still fetched after the snapshot was pinned.
The reviewer is told either way.

*Alternatives:* a second rule id for "no lockfile", high without a lockfile,
or low with one. All three were rejected. A lockfile is honoured only when the
install command uses it (`npm ci`, not `npm i`), which this design does not
resolve. High would block the documented `${CLAUDE_PLUGIN_DATA}` pattern, for
the reason MCP runners only warn. "This plugin installs at run time" is one
reviewer decision, so it is one group and one waiver.

TOML and requirement files are read line by line: section headers, and the
first entry after them. No TOML parser is added. The question is only
whether a section is empty, and a new dependency for that is not warranted.

### D4. Which instructions and scripts are scanned

`RuntimeFetch` matches become `skill-fetch-exec` and `runtime-dependency`
(D2's table) in:

- **Skill files.** Every text file under a skill's directory (the directory
  holding `SKILL.md`), within the scan size limit, that is a script: a known
  extension (`.sh`, `.bash`, `.zsh`, `.py`, `.js`, `.mjs`, `.cjs`, `.ts`,
  `.ps1`, `.rb`) or a `#!` first line. The whole file is scanned, as a launched
  hook file is.
- **Instructions.** Every Markdown file under a skill's directory, and the
  command and agent files the inventory lists. Only fenced code blocks
  (```` ``` ```` and `~~~`) are scanned, each as a command. Prose and inline
  code spans are not: an inline span is usually a mention, and the existing
  test for SVC_GW_VETTING_0048 requires a mention in `SKILL.md` to stay silent
  (acceptance.md, R1). The vetting page's rule that "documentation that
  mentions `curl` is never a finding" still holds.

A file already scanned as a launched hook, MCP or LSP file is skipped. Each
line then carries the strongest applicable finding, never two.

Download-and-execute blocks here as it does everywhere else. The code was never
in the pinned snapshot, whatever triggers it. An MCP server also runs only when
used, and `mcp-fetch-exec` still blocks. On a Markdown line,
`skill-fetch-exec` can coincide with `prompt-injection`'s `pipe-to-shell`.
That overlap is deliberate: the two vetters have separate switches and
waivers, and `RuntimeFetch` also catches shapes that `pipe-to-shell` misses
(substitution, download-then-chmod, other interpreters).

*Alternative:* scan every text file in the plugin. Rejected for precision: a
README that tells a maintainer how to build the plugin is not an instruction
to the agent.

### D5. One rule id for manifests and install commands

The manifest and the install command are the same risk: code that runs but was
never pinned. One id lets a reviewer waive "this plugin installs its
dependencies at run time" once, by group, rather than twice.

### D6. The inventory API and portal

`PluginComponent` in the OpenAPI schema gains `lspServers` (name and location,
as `mcpServers`). This is additive. The portal's inventory gains the kind
`lspServers` ("LSP server" / "LSP servers") beside MCP servers.

## Risks / Trade-offs

- [Medium warnings on many marketplaces that ship scripts with `pip install`
  in a code block] → That is the intended signal. Measure on
  `anthropics/claude-plugins-official` at a pinned SHA, as the MCP change did,
  and record the counts in `evidence.md`.
- [A code span that names a command without asking the agent to run it
  ("do not run `npm install`")] → Accepted false positive at medium. The
  reviewer reads the line the finding names.
- [Line-based TOML reading misses an unusual layout] → The miss is a missed
  medium, not a wrong block. Tests cover the inline-array and multi-line-array
  forms that appear in practice.
- [Re-vetting adds warnings to snapshots already served] → Medium warns and
  never holds, so a served snapshot keeps serving. The new findings appear on
  its next verdict.

## Migration Plan

None. The rules live in an existing vetter behind its existing switch, and
there is no schema change.
