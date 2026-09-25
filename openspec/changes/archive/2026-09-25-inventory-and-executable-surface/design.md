# Design: inventory-and-executable-surface

## Context

The motivation is in proposal.md. The current state:

- `SnapshotContentService.content()` reads `.claude-plugin/marketplace.json` and
  lists, for each plugin with a string `source`, the `skills/*/SKILL.md` under
  it. Nothing else is read. `SnapshotFactsService` builds the policy gate's
  facts from the same call, so an exception in it is a refused approval.
- Vetters see a snapshot only through `SnapshotUnderVetting.walk(predicate,
  visitor)`. A predicate that declines a path costs no blob read (#433). The
  gateway stamps each finding's blob from the pinned tree, and #500 groups
  findings by rule, severity, message, blob and line. A group waiver matches
  on rule, blob and line.
- Built-in vetters are Spring beans, enabled unless an administrator switches
  them off. The switch is global or per marketplace, and the declarative
  estate reaches it. `ExternalConnectorProperties` reserves the built-in names.

The Claude Code plugin layout, as documented in the plugin manifest reference
and the hooks reference:

| Component | Default location | Manifest key | Combines how |
| --- | --- | --- | --- |
| Commands | `commands/**/*.md` | `commands`: path, array, or object map | replaces the default |
| Agents | `agents/**/*.md` | `agents`: path or array of `.md` files | replaces the default |
| Hooks | `hooks/hooks.json` | `hooks`: path, inline object, or array of either | merges with the default |
| MCP servers | `.mcp.json` | `mcpServers`: path, inline map, bundle, or array | merges with the default |

A marketplace entry accepts the same keys. Skills and agents can also declare
`hooks:` in their frontmatter. A hook handler is `command` (shell form, or exec
form with `args`), `http`, `mcp_tool`, `prompt` or `agent`.

## Goals / Non-Goals

**Goals:**

- One reading of the plugin layout, shared by the inventory and the vetter, so
  the two cannot disagree about what a plugin's hooks are.
- A runtime-fetch detector that fires on the trial's launcher and stays silent
  on documentation, comments and local-only hooks.

**Non-Goals:**

- **Scanning MCP servers and monitors for runtime fetches.** The issue scopes
  the vetter to hooks and the scripts they launch. MCP servers are listed in
  the inventory, so a reviewer sees them. Flagging every `npx -y` MCP server as
  high would block a large share of real marketplaces on a decision the owner
  has not taken. The vetter's description and the docs name this as a known
  limit.
- **Other harnesses' hook files** (`.codex/hooks.json`, `.cursor/hooks.json`,
  `cursor-plugin/hooks/hooks.json`). The gateway serves Claude Code
  marketplaces, and Claude Code never runs those files.
- **Listing output styles, LSP servers, monitors, themes and workflows** in the
  inventory. They are not code that runs on its own, apart from monitors,
  which are experimental. They can be added to the same reader later, one kind
  at a time, if a reviewer asks.
- **Following variable indirection** (`$CURL "$url" | sh`, where `CURL=curl`)
  or decoding base64 payloads. These are named as known limits.

## Decisions

### D1. `PluginComponents` is pure, and lives in `ingestion/`

It takes a `Files` view (the tree's paths plus a reader that returns a file's
bytes, or null when the file is over the size limit), a plugin root, and the
plugin's marketplace entry. It returns the components.

- The inventory backs `Files` with one `TreeWalk` index over the repository.
- The vetter backs it with `walk()` predicates, so it opens only the blobs the
  layout names.

There is no new package: the inventory is ingestion's, and vetting already
imports from ingestion.

*Alternative:* have the vetter call `SnapshotContentService`. Rejected. A
vetter must see only the pinned snapshot it was handed (the SPI's contract),
not open the quarantine repository itself.

### D2. JSON locations come from Jackson's parsing context

Each string value's JSON pointer is recorded against the line it starts on
(`JsonParser.getParsingContext().pathAsPointer()`) in the same pass that builds
the tree. A hook in `hooks.json`, in `plugin.json` or in the marketplace entry
is then located at the line of its `command` (or `url` or `prompt`).

A hook in YAML frontmatter is located at the first line that contains its
command text, and otherwise at the line of the `hooks:` key.

A group waiver matches on blob and line, so the line must be the hook's own
line and the same for every copy of the file. Both methods are.

### D3. Plugin roots: manifest sources, plus any `.claude-plugin/plugin.json`

The vetter vets every string `source` the manifest declares, and also every
directory that holds a `.claude-plugin/plugin.json`. This is the shape rule
`skill-conformance` uses: a plugin the manifest does not list is still served,
and listing it in a later commit must not be the moment its hooks are first
seen.

The inventory keeps its existing contract of manifest plugins only.

### D4. Which files a hook launches

For each hook, the candidate paths are:

- `${CLAUDE_PLUGIN_ROOT}/…` references in its command and `args`, resolved
  against the plugin root;
- path-shaped tokens (containing `/`, or with a script extension), resolved
  against the plugin root.

Inside a launched file, path-shaped tokens are resolved against that file's
directory and against the plugin root. A `$dir/…`, `${X}/…` or
`$(dirname "$0")/…` prefix is stripped first.

A candidate is followed only when it names a file that exists in the snapshot
under the plugin root. That keeps the rule precise: `node`, `/usr/bin/env` and
a path outside the plugin never match. It is followed to a depth of three, and
a visited set stops cycles.

### D5. The runtime-fetch rules

Before matching, each line of a hook command or launched file is normalised:
empty quote pairs and quote marks inside a word are removed (`c''url`,
`"cu"rl`), and so are backslashes before a letter (`c\url`). Lines that are
comments (`#`, `//`, `rem`, `::`) are skipped in launched files. A shebang is
also a `#` line.

The download verbs are `curl`, `wget`, `Invoke-WebRequest`/`iwr`,
`Invoke-RestMethod`/`irm`, `certutil -urlcache`, `bitsadmin /transfer`,
`urlretrieve`, `urlopen` and `requests.get`.

| Rule | Severity | Fires on |
| --- | --- | --- |
| `runtime-fetch-exec` | high | (a) a download verb whose output is piped into an interpreter (`sh`, `bash`, `zsh`, `dash`, `python`, `node`, `perl`, `ruby`, `iex`, `pwsh`, `powershell`), on one line; (b) an interpreter fed by `$(download …)`, `` `download …` `` or `<(download …)`, or `eval "$(download …)"`, or `iex (iwr …)`; (c) a launched file or hook command that both downloads to a file (`curl -o/-O/--output`, `wget` not writing to stdout, `-OutFile`, `urlretrieve`, `certutil`, `bitsadmin`) and makes something executable (`chmod` with an execute bit, `os.chmod`, `fs.chmod`, `Start-Process`). It is located at the download line. |
| `runtime-package-run` | high | `npx`, `bunx`, `pnpx`, `uvx`, `pnpm dlx`, `yarn dlx`, `npm exec`, `pipx run`, `uv tool run` in command position: at the start of the line, or after `;`, `&&`, `\|\|`, `\|`, `(`, `exec` or `then`/`do`. |

**Why file-level co-occurrence for (c).** The trial launcher downloads in one
function (`curl -o "$tmp"`) and makes the file executable forty lines later
(`chmod +x "$tmp"`). No single-line rule sees both halves. Requiring both in
one launched file keeps the false positives rare: a hook script that
downloads data and never makes anything executable is not flagged.

**Why a package runner is high.** `npx pkg` in a hook fetches the package's
current code from a registry every time the hook runs. That is exactly what
the issue means by code fetched at run time.

Findings are de-duplicated on rule and location across hooks. Three hooks that
reach the same launcher yield one finding per line, not three.

### D6. The severity model

| Finding | Severity | Verdict | Why |
| --- | --- | --- | --- |
| `auto-run-hook` | medium | warns | A hook is a legitimate plugin feature whose code is in the snapshot and read by the rest of the chain. Blocking every hook would teach reviewers to waive them unread. Info would be invisible on a passing row. Medium puts it on the verdict row without holding the snapshot. |
| `runtime-fetch-exec`, `runtime-package-run` | high | fails | The code that runs is not the code that was approved. A block, waivable per group, is the owner's decision. |
| `hook-config-unreadable`, `hook-target-unscanned` | medium | warns | Claude Code rejects a hook file it cannot parse, so nothing runs from it. A launched binary is shipped content, pinned by the gateway, but no rule can read it. Both are things the reviewer must see, and neither is evidence of a fetch. |

### D7. Chain position 250, on by default, version 1

The vetter sits after the two content-danger scanners and before license and
conformance. In `stop-after-fail` mode the security findings then come first.
It is on by default because a gateway that ships the control switched off
protects nothing by default. An estate that disagrees uses the existing
switch, globally or per marketplace; no new configuration leaf is needed.

### D8. Portal: `snapshot-inventory.tsx`

The component moves out of `snapshot-card.tsx`, which keeps only the call, so
that it has its own story. Each count is a `Button variant="ghost" size="xs"`
with `aria-expanded` and `aria-controls`, labelled "3 hooks", and toggles its
list in place. A kind with no components shows no count. A plugin with none at
all reads "no components found".

Hook rows show the trigger (event, plus matcher when set) as a badge-like chip,
the command in monospace wrapped with `break-all`, and the declaring
`path:line` in muted text.

## Risks / Trade-offs

- [A paraphrased or indirected fetch (`$C "$u" | sh`) walks past the rules] →
  Named as a known limit in the vetter's description and in the docs, like
  every pattern vetter. The `auto-run-hook` warning still puts the hook in
  front of the reviewer.
- [File-level co-occurrence can fire on a script that downloads data and makes
  an unrelated file executable] → It is only evaluated in files a hook
  launches, and the finding is waivable per group. It is measured against the
  real repository in evidence.md.
- [Package runners in hooks block marketplaces that use them] → That is the
  intended reading of runtime fetching. The rule has its own id, so an estate
  can waive `runtime-package-run` for a marketplace path without waiving
  download-and-execute.
- [The inventory now reads more blobs per call] → Only the component
  declaration files and frontmatter-bearing `.md` files under plugin roots are
  read, and `diff()` does not compute components at all.
