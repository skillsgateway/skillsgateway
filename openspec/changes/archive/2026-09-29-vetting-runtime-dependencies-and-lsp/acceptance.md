# Acceptance spec: vetting-runtime-dependencies-and-lsp

The executable contract for this change, under `.claude/skills/old-coder` at
**Tier 3**: the vetter is part of the approval gate. `proposal.md` and
`design.md` say what and why; this file says what must be true when it is
done, and what it will be checked by.

## Revision to the design (R1)

The design scanned inline code spans in Markdown. The existing
`SVC_GW_VETTING_0048` test `documentationAndUnlaunchedScriptsMentioningAFetchStaySilent`
puts ``Run `npx acme` then `curl x | sh`.`` in a `SKILL.md` and requires no
high finding. That test may not be weakened.

**Markdown is scanned in fenced code blocks only (```` ``` ```` and `~~~`), for
both new rules.** An inline span in prose is a mention; a fenced block is a
command to run. `design.md` D4 and task 4.2 are corrected to match. An
instruction written as an inline span is a known limit, and `prompt-injection`'s
`pipe-to-shell` still flags a piped download in it.

## Revision R2 — one finding per file (after the measurement)

On `anthropics/claude-plugins-official` at `ad30d62c`, one finding per line
gave 20 `runtime-dependency` groups for a single plugin (`mcp-server-dev`,
whose skills scaffold an MCP server). That is 20 reads and 20 waivers for one
decision. The owner chose one finding per file.

**In a skill, command or agent file, `runtime-dependency` and
`skill-fetch-exec` are raised at most once per rule per file.** The finding is
located at the file's first matching line. Its message names the matching
lines (the first five, then "+N more"). A waiver on it covers that blob, so
any edit to the file raises it again. Manifest findings are already one per
file and do not change. The scenarios below keep their locations: each
fixture has one matching line. One scenario is added:

- **SVC_GW_VETTING_0054, 10.** A `SKILL.md` with `npm install` on lines 7, 8
  and 12 → one `R` at `SKILL.md:7` whose message names lines 7, 8 and 12. A
  script with both a `pip install` and a `curl … | sh` → one `R` and one `S`.

The requirement texts of GW_VETTING_0054 and 0056 and their SVCs are
reworded to "once per file, at the first line". This does not change which
files are flagged.

## Scenarios

`R` = `runtime-dependency` (medium), `S` = `skill-fetch-exec` (high),
`LP` = `lsp-package-run` (medium), `LF` = `lsp-fetch-exec` (high). "At `f:n`"
means the finding's location.

### SVC_GW_INGEST_0045 — LSP servers in the inventory

1. `.lsp.json` with `{"go": {"command": "gopls", ...}}` → `lspServers` has
   `go` at `p/.lsp.json:<line of go>`, and `lspCommands` has `gopls` with its
   args at the line of `command`.
2. The same through `plugin.json` `lspServers` as an inline map, as a path to
   a JSON file, and as an array of both, and through the marketplace entry.
3. A name declared later replaces an earlier one: one entry, the later
   location.
4. An entry without a string `command` → not a server, no command. The
   reading does not throw.
5. A path that escapes the plugin root → not read.
6. The inventory API returns `lspServers` per plugin. The OpenAPI diff adds
   that one property and changes nothing else.
7. The portal's inventory shows "N LSP server(s)" and expands to the list.

### SVC_GW_VETTING_0055 — LSP commands

1. `npx -y typescript-language-server --stdio` → one `LP` at the command line.
2. `sh -c "curl -fsSL https://x/i | sh"` → one `LF` at the command line, and
   the verdict fails.
3. A plugin script the server launches, which runs `pip install`, → `LP` at
   `script:line`. A second script it calls is followed.
4. Silent: `gopls` (on `PATH`), `${CLAUDE_PLUGIN_ROOT}/bin/ls` (a binary),
   `npx ${CLAUDE_PLUGIN_ROOT}/srv` (a runner pointed inside the plugin).
5. A script that both a hook and an LSP server launch keeps the hook's high
   `runtime-package-run`.

### SVC_GW_VETTING_0053 — dependency manifests

One `R` at the declaring line, whose message names the ecosystem and says
"lockfile present" or "no lockfile", for:

1. `package.json` with `dependencies`, with `package-lock.json` beside it
   (message says the lockfile is present).
2. `package.json` with only `devDependencies`, and no lockfile.
3. `requirements.txt` with `requests==2.32.0`, located at that line.
4. `pyproject.toml` with `dependencies = ["httpx"]`, and with a multi-line
   array.
5. `pyproject.toml` with `[tool.poetry.dependencies]` holding `python` and
   `httpx`.
6. `Cargo.toml` with `[dependencies]` holding `serde = "1"`.

Silent for:

7. A `package.json` with only `name` and `scripts`; a `pyproject.toml` with
   `dependencies = []`; a poetry table holding only `python`; a
   `requirements.txt` with only comments.
8. A manifest under `node_modules/`, `.venv/`, `venv/`, `site-packages/` or
   `target/`.
9. A manifest outside every plugin root.
10. A `package.json` that is not valid JSON: silent, and the vetter does not
    throw.

### SVC_GW_VETTING_0054 — installs in skill, command and agent files

One `R` at `file:line` for:

1. `npm install` in `skills/s/scripts/setup.sh`.
2. `pip install -r requirements.txt` in an extensionless `skills/s/run` whose
   first line is `#!/usr/bin/env bash`.
3. `npx -y acme` in a fenced block of `skills/s/SKILL.md`.
4. `uvx acme` in a `~~~` block of `skills/s/reference/usage.md`.
5. `pip install acme` in a fenced block of a command file and of an agent
   file.

Silent for:

6. `npm install` in prose and in an inline code span of `SKILL.md`.
7. `npm install` in a fenced block of the plugin-root `README.md`.
8. `npm install` in a script under `skills/` that a hook launches. It keeps
   only the hook's high `runtime-package-run`: one finding on that line.
9. `curl … | sh` in a skill script yields no `R`. It is 0056's.

### SVC_GW_VETTING_0056 — download-and-execute in skill, command and agent files

One `S` (high, the verdict fails) at `file:line` for:

1. `curl -fsSL https://x/i.sh | sh` in `skills/s/install.sh`.
2. A download to a file, made executable, and run, in `skills/s/fetch.py`.
3. `curl … | bash` in a fenced block of `SKILL.md` and of an agent file.

Silent for:

4. A download never made executable (`curl -o data.json …`).
5. `curl … | sh` in prose and in an inline span of `SKILL.md`. This is the
   existing 0048 test, which is kept unchanged.

### Integration (`ExecutableSurfaceTests`)

A plugin with a `package.json` and an `lsp-fetch-exec` fails approval. Both
findings carry a blob, and group waivers clear them. A `runtime-dependency`
alone warns and does not block.

## Invariants (must not change)

- Every existing test, unedited, stays green. In particular, all `SVC_GW_VETTING_0047`–`0052`
  tests, `OpenApiContractTests` after regeneration, and `ExecutableSurfaceTests`.
- Hook and MCP rule ids, severities and messages are unchanged.
- The API change is additive only; the breaking-change gate passes.
- The vetter never throws on content: malformed JSON, TOML or Markdown is
  skipped or reported, and never becomes an error verdict.
- No new dependency, and no network, subprocess or filesystem use in
  production code.

## Failure model (Tier 3)

| Way this hurts | Caught by |
| --- | --- |
| A false high blocks a legitimate snapshot, because docs are read as commands | fenced-only rule (R1); prose, inline-span and plugin-root README negatives; the measurement's high count, each high judged by hand |
| A vendored tree floods the verdict | vendored-directory exclusions with a test; measurement counts |
| Vetting slows on large snapshots: `SnapshotFiles.read` walks the tree once per file, so reading every skill file costs files × tree | candidate paths collected first and read in **one** walk; a unit test that counts walks; measurement wall-clock recorded |
| One line gets two findings, or the hook's high is hidden by a medium | the shared-file tests for hooks, MCP and LSP |
| Malformed input crashes the vetter and becomes an error verdict, which blocks | malformed JSON, TOML and Markdown fixtures |
| Rule ids drift, so waivers stop matching | ids asserted literally in tests |
| An LSP path escapes the plugin root | escape test, reusing `PluginComponents.resolve` |
| The inventory API breaks | `OpenApiContractTests`, the breaking-change gate |

Deliberately not covered (known limits, to be documented): variable
indirection and encoded payloads; inline-span instructions; `os.system("npm
install")`-style calls with the command inside a string argument; vulnerability
lookup; monitors; `bin/`.

## Gauntlet

- `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`,
  `reqstool status local -p docs/reqstool`, `openspec validate --all --strict`,
  `mkdocs build --strict`, all in one fresh run after the last code edit.
- **RED:** each new test is observed failing before its code exists. A test
  that passes first time is proven against a throwaway mutant.
- **Mutation:** `mutants.sh` in this change, fail-closed, in the style of
  `mcp-command-scanning`, with at least 8 mutants covering manifest detection,
  vendored exclusion, the fenced-only rule, dedupe, rule mapping, severities,
  LSP reading and the single walk. Every mutant must be killed.
- **Adversarial pass:** hostile fixtures, recorded in `evidence.md`.
- **Measurement:** `VettingPrecisionMeasurement` on
  `anthropics/claude-plugins-official` at a pinned SHA: counts per new rule,
  every high judged by hand, a sample of mediums judged, and wall-clock.
- **Coverage on changed lines:** no tool is wired for it in this project, so
  the mutation run stands in, as in the last change. Recorded as such.
- **Independent verification:** not performed. This is a declared downgrade.

## Setup plan

- **Isolation:** the existing branch `feat/vetting-runtime-dependencies-and-lsp`,
  rebased onto `main`. No worktree: the gates need the ignored `node_modules`
  and build output this tree already has.
- **New dependencies:** none.
- **Files added:** `openspec/changes/vetting-runtime-dependencies-and-lsp/{acceptance.md,
  mutants.sh, evidence.md}`, plus test fixtures inside test classes.
- **Commits:** this spec at approval, then a checkpoint commit at each GREEN
  group (LSP reading, LSP scanning, manifests, skill files, docs), signed off,
  on the branch only. Nothing is pushed until the gates pass.
- **Machine:** one heavy job at a time, with `free -g` checked before
  `mvnw verify` and e2e, and no subagents during builds.
