# Tasks: mcp-command-scanning

The vetter is part of the approval gate, so this change is worked under
`.claude/skills/old-coder` at Tier 3. Every new test is shown failing before
the code it guards exists, or against a throwaway mutant when it passes on
first run. Spec approval was not obtained: this is an autonomous run, and the
spec is reviewed after the fact.

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_VETTING_0050, GW_VETTING_0051 and GW_VETTING_0052 with
  SVC_GW_VETTING_0050–0052. Verify with
  `openspec validate mcp-command-scanning --strict`.

## 2. Local server commands in the layout reader

- [x] 2.1 `PluginComponentsTests`: local servers from `.mcp.json` (wrapped and
  bare map), inline `plugin.json` `mcpServers`, a referenced JSON file and the
  marketplace entry, each with its command, arguments and the `path:line` of
  its `command`; remote and bundle servers carry none. Watch it fail.
- [x] 2.2 `PluginComponents.Components.mcpCommands`, annotated
  `@Requirements GW_VETTING_0050`. Make 2.1 green. The inventory API is
  unchanged: `OpenApiContractTests` passes without regenerating.

## 3. Scanning (SVC_GW_VETTING_0050–0052)

- [x] 3.1 `ExecutableSurfaceVetterTests`, over `InMemorySnapshot`:
  - 0050: `npx -y`, `uvx --from git+…`, `pnpm dlx`, `bunx`, `pip install`
    through `sh -c`, `cmd /c npx`, `/usr/local/bin/npx`, `env X=1 npx`, and
    `${RUNNER:-npx}` are one medium `mcp-package-run` each at the command's
    line; negatives `npx ./local`, `npx ${CLAUDE_PLUGIN_ROOT}/srv`, a plain
    local binary and a remote server stay silent.
  - 0051: `sh -c "curl … | sh"`, the pipe split across `args` entries, a
    download fed through substitution, the same in `plugin.json`
    `mcpServers` and in the marketplace entry, and a `${SHELL:-sh}` wrapper
    each block with one high `mcp-fetch-exec`; a `curl` that only prints
    stays silent.
  - 0052: a script the server launches through `${CLAUDE_PLUGIN_ROOT}` and a
    second script it calls are followed, with findings at their `path:line`;
    a binary it runs is not reported; a script both a hook and a server
    launch keeps the hook's high finding.

  Watch each fail against the current vetter.
- [x] 3.2 Extend `ExecutableSurfaceVetter` (`@Requirements GW_VETTING_0050,
  0051, 0052`), with the summary and description updated. Make 3.1 green, and
  keep every existing test green unchanged.
- [x] 3.3 Integration: extend `ExecutableSurfaceTests` (`@SVCs
  SVC_GW_VETTING_0051`) so an MCP download-and-execute blocks at approval, its
  finding carries a blob and a group waiver clears it, and an `npx` server
  alone warns. Verify with the test class.
- [x] 3.4 Manual mutation: at least five mutants over the new code in
  `mutants.sh` in this change. Each is killed.
- [x] 3.5 Measure on `anthropics/claude-plugins-official` at a pinned SHA with
  `VettingPrecisionMeasurement` and record MCP findings by severity, and
  whether any high one is real, in `evidence.md`.

## 4. Docs (same PR)

- [x] 4.1 `concepts/vetting.md`: the `executable-surface` section says MCP
  commands are scanned, with the two rules and severities, and the known
  limits (indirection, encoded payloads, monitors, package-manager scripts,
  container images, bundles). Update the glossary if it lists the rule ids.
  Verify with `mkdocs build --strict`.

## 5. Gates and archive

- [x] 5.1 A fresh run of all six gates after the last code edit. Write
  `evidence.md` with the commands, the result tails and the SHA.
- [x] 5.2 `/opsx:archive` as the final commit, with the synced
  `openspec/specs/**` committed in it.
