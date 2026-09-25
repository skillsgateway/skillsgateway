# Evidence: mcp-command-scanning (Tier 3)

- **Spec approval:** not obtained. This was an autonomous run. The acceptance
  criteria are `proposal.md`, `design.md` and the SVCs of:
  - GW_VETTING_0050 — An MCP server that runs a package fetched at run time is
    a medium-severity finding;
  - GW_VETTING_0051 — An MCP server that downloads and executes code is a
    high-severity finding;
  - GW_VETTING_0052 — Files an MCP server launches from its plugin are scanned
    as hook scripts are.

  They were committed at `b0ba4796`, before any implementation commit. They are
  the artifacts to review after the fact, and confidence is claimed
  accordingly.
- **Source state:** `224f83d0` on `feat/mcp-command-scanning`. All the gates
  below, the mutation run and the measurement ran over that commit or, for the
  mutation run and the measurement, over its code (the commit after them
  changed only docs). The only later changes are this file, `tasks.md` and the
  archive.
- **Entry points:** the project gates below;
  `openspec/changes/mcp-command-scanning/mutants.sh`; and
  `VettingPrecisionMeasurement` for the measurement.
- **Independent verification:** not performed.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 966, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  23 passed (23)
[INFO]       Tests  185 passed (185)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:21 min

$ (cd src/main/frontend && pnpm test:stories)   # after rm -rf node_modules/.cache/storybook node_modules/.vite
 Test Files  16 passed (16)
      Tests  79 passed (79)
# The third run. The first two failed in the known harness flake: test files
# failed to import ("Failed to fetch dynamically imported module"). No
# assertion failed in either run. No portal file changed in this change.

$ (cd src/main/frontend && pnpm e2e)
  23 passed (1.2m)

$ uvx --from reqstool==0.12.0 reqstool status local -p docs/reqstool
316/316 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
exit 0
```

No existing test changed. The vetter's summary sentence changed ("MCP server
and monitor commands are not examined" became a count of MCP server commands
and "monitor commands are not examined"); the existing assertions on the
summary check the plugin-root and hook counts, which are unchanged.

## Behaviours → tests

| Behaviour | Test |
| --- | --- |
| Local servers are read from `.mcp.json`, a referenced JSON file, inline `plugin.json` `mcpServers` and the marketplace entry, each with its command, arguments and the `path:line` of `command`; remote and bundle servers carry none | `PluginComponentsTests.localMcpServerCommandsAreReadFromEverySourceAtTheirCommandLine` |
| A package runner or install warns as one medium `mcp-package-run` at the command's line: `npx -y`, `uvx --from git+…`, `pnpm dlx`, `bunx`, `pip install` through `sh -c`, `cmd /c npx`, `/usr/local/bin/npx`, `env … npx`, `${RUNNER:-npx}` | `ExecutableSurfaceVetterTests.anMcpServerThatRunsAPackageRunnerWarnsAtItsCommand` (9) |
| Negatives stay silent: `npx ./server`, `npx -y ${CLAUDE_PLUGIN_ROOT}/server`, a plain local binary, `node` on a clean local script, an `http` server with a stray `command`, an `sse` server, a `curl` that only prints | `aLocalRunnerALocalBinaryARemoteServerAndAPrintedDownloadStaySilent` (7) |
| Download-and-execute blocks as one high `mcp-fetch-exec`: `sh -c "curl … \| sh"`, the pipe split across `args` entries, `bash <(curl …)`, `${X:-c}url`, `${SHELL:-sh}`, `powershell -Command "iex (iwr …)"`, Python `exec(urlopen(…))` | `anMcpServerThatDownloadsAndExecutesBlocksAtItsCommand` (7) |
| Servers in a referenced file, inline in `plugin.json` and in the marketplace entry are each scanned at their own line | `mcpServersInPluginJsonAndTheEntryAreScannedAtTheirOwnLines` |
| A script the server launches is followed through a second script, with the finding at the second script's line | `aScriptAnMcpServerLaunchesIsFollowedThroughASecondScript` |
| A script both a hook and a server launch keeps the hook's high finding beside the server's medium one | `aScriptBothAHookAndAnMcpServerLaunchKeepsTheHooksHighFinding` |
| End to end: blocks at approval, groups across a vendored copy with a blob and line, a group waiver clears it, and the package runner alone does not block | `ExecutableSurfaceTests.anMcpServerThatFetchesCodeBlocksAndAPackageRunnerOnlyWarns` |

RED was observed before GREEN:

- `PluginComponentsTests` gave 1 failure (the new test) against a stub that
  returned no commands.
- `ExecutableSurfaceVetterTests` gave 19 failures against the unchanged
  vetter: every positive and both follow tests. The 7 negatives passed on the
  first run, because the unchanged vetter scanned no MCP server at all. They
  are proven by mutants M5 and M9 below, which make them fail.
- The integration test was written after the vetter; it exercises behaviour
  whose unit tests were seen failing. It was not separately seen failing.

## Mutation (manual, `mutants.sh`)

Each mutant counts as killed only when the named test fails, and the restore is
checked with `git diff --exit-code`.

```console
$ openspec/changes/mcp-command-scanning/mutants.sh
M1-mcp-runner-blocks killed
M2-wrapper-script-not-scanned killed
M3-defaults-not-expanded killed
M4-env-not-dropped killed
M5-local-runner-flagged killed
M6-launched-not-followed killed
M7-launched-fetch-medium killed        # run again with ONLY='^M(7|8|9|10|11)-'
M8-one-visited-set killed
M9-launched-scanned-as-hook killed
M10-remote-scanned killed
M11-absolute-runner-kept killed
all mutants killed
```

The first run stopped at M7 because its text matched twice. That was fixed in
the script, not in the code, and M7 to M11 were run again. That is 11/11
killed.

## Measured on a real repository

`VettingPrecisionMeasurement` over a local clone. It runs only with
`-Dvetting.measure.repo=…`, and never in the build.

| Repository | Plugin roots | Hooks | MCP server commands | Launched files | MCP high | MCP medium |
| --- | --- | --- | --- | --- | --- | --- |
| `anthropics/claude-plugins-official` @ `ad30d62c` | 53 | 22 | 9 | 15 | 0 | 3 |

- The 3 medium findings are `mcp-package-run` at
  `external_plugins/firebase/.mcp.json:3` (`npx -y firebase-tools@latest`),
  `external_plugins/playwright/.mcp.json:3` (`npx @playwright/mcp@latest`) and
  `external_plugins/serena/.mcp.json:3` (`uvx --from git+https://…`). Each one
  does fetch its code at start, so all three are real.
- There is no high MCP finding. The vetter's two high findings are unchanged
  from #502: the hook script that hands `pip install` to a subprocess.
- 6 remote servers carry no command and are not scanned.
- Of the other 6 local servers, 4 run `bun run --cwd ${CLAUDE_PLUGIN_ROOT} start`,
  and each `package.json` start script runs `bun install` before the server.
  One runs `docker run … hashicorp/terraform-mcp-server:0.4.0`, and one runs
  `php artisan boost:mcp` in the user's project. None is flagged. The first
  two are the known limits below; the third runs the user's own code.

## Layers

| Layer | Result |
| --- | --- |
| Full suite | 966 Java tests (9 skipped, all pre-existing), 185 UI unit tests, 79 story tests and 23 e2e tests, all green |
| Types, lint and format | `tsc -b`, oxlint, Spotless and Checkstyle, all inside `mvnw verify` |
| Mutation | 11/11 killed |
| Real execution | the measurement runs the vetter over a real repository's pinned tree through `QuarantineSnapshot` |
| Adversarial | shell wrappers (`sh -c`, `bash -c`, `cmd /c`, `powershell -Command`); the pipe split across `args`; variable defaults in the command and inside the script; `env` with an option that takes a value; a runner by absolute path; declarations in `.mcp.json`, a referenced file, `plugin.json` and the marketplace entry; a remote entry carrying a `command`; a script shared with a hook |
| Coverage on changed lines | not measured by a tool. The mutation run and the per-shape fixtures stand in for it |
| Supply chain | no new dependency; no network, subprocess or filesystem use added to production code |

## Known limits

- Variable indirection and encoded payloads walk past the rules, as for hooks.
  A bare `${VAR}` in command position is not resolved.
- A package-manager run script (`bun run start`, `npm start`) is not looked up
  in `package.json`. Measured: 4 of 9 local servers in
  `claude-plugins-official` start that way, and all 4 run `bun install`.
- A container runner (`docker run image`) is not classified as a fetch,
  because `RuntimeFetch` is reused unchanged.
- MCP bundles (`.mcpb`, `.dxt`) are archives and are not read.
- The local-runner exemption applies to the program itself. `sh -c "npx ./x"`
  still warns, which is the conservative side.
