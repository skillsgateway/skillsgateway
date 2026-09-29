# Evidence: vetting-runtime-dependencies-and-lsp

- **Source state:** every gate, the mutation run and the measurement below ran
  over `857d393d`, the commit after the last code edit. The only later changes
  are this file, `tasks.md` and the archive.
- **Tier:** 3 under `.claude/skills/old-coder`: the vetter is part of the
  approval gate.
- **Spec approval:** obtained. The owner approved `acceptance.md` as written
  (R1: Markdown is scanned in fenced blocks only), and then approved R2 (one
  finding per rule per file) after the measurement showed 20 groups for one
  plugin.
- **Entry points:** the six project gates below;
  `openspec/changes/vetting-runtime-dependencies-and-lsp/mutants.sh` (`DRY=1`
  proves every mutant applies without running); and `VettingPrecisionMeasurement`
  for the measurement.
- **Independent verification:** not performed. This is a declared downgrade.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 993, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  23 passed (23)
[INFO]       Tests  187 passed (187)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:55 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  16 passed (16)
      Tests  80 passed (80)

$ (cd src/main/frontend && pnpm e2e)
  23 passed (1.2m)

$ reqstool status local -p docs/reqstool        # reqstool==0.12.0, as CI pins it
322/322 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.49 seconds
```

What happened on the way:

- **Story tests.** The first two runs after `mvnw clean verify` failed to
  import story files ("Failed to fetch dynamically imported module"), with no
  test failing. That is the known cache flake. Clearing
  `node_modules/.cache/storybook` and `node_modules/.vite` and running twice
  passed both times (80/80). The numbers above are from those runs.
- **reqstool.** The locally installed CLI's pipx environment was broken
  (`ModuleNotFoundError: reqstool`). The gate ran from a scratch venv holding
  `reqstool==0.12.0`, the version `ci.yml` installs.
- **Leaked containers:** none after the gates
  (`docker ps --filter label=org.testcontainers=true` returned nothing).

## Spec → tests

| Scenario (`acceptance.md`) | Verified by |
| --- | --- |
| SVC_GW_INGEST_0045, 1–5: LSP reading, all sources, replacement, entry without `command`, escape | `PluginComponentsTests.lspServersAreReadFromEverySourceAndALaterNameReplacesAnEarlierOne`, `aMalformedLspFileLeavesOnlyItsServersOut` |
| SVC_GW_INGEST_0045, 6: the API lists `lspServers`; the OpenAPI diff is additive | `ContentTests.snapshotContentListsLspServers`, `OpenApiContractTests` (diff: one added property, two description texts) |
| SVC_GW_INGEST_0045, 7: the portal count and expanded list | `snapshot-inventory.test.tsx` `lsp_servers_are_a_count_that_expands_to_each_server_and_its_declaration`, story `LspServersExpanded` |
| SVC_GW_VETTING_0055, 1–5 | `ExecutableSurfaceVetterTests`: `anLspServerThatRunsAPackageRunnerWarnsAtItsCommand`, `anLspServerThatDownloadsAndExecutesBlocksAtItsCommand`, `aScriptAnLspServerLaunchesIsFollowedThroughASecondScript`, `anLspServerOnThePathABinaryAndALocalRunnerStaySilent`, `aScriptBothAHookAndAnLspServerLaunchKeepsTheHooksHighFinding` |
| SVC_GW_VETTING_0053, 1–6 | `aManifestDeclaringDependenciesWarnsAtItsDeclarationAndSaysWhetherALockfileSitsBesideIt` |
| SVC_GW_VETTING_0053, 7–10 | `emptyVendoredOutsideAndUnparseableManifestsStaySilent` |
| SVC_GW_VETTING_0054, 1–9 | `anInstallInASkillScriptOrAFencedBlockOfASkillCommandOrAgentWarnsAtItsLine` |
| SVC_GW_VETTING_0054, 10 (R2) | `aFileIsOneFindingPerRuleAtItsFirstLineNamingEveryLine`, `aLongListOfLinesIsShortened`, `aFileMixingInstallsAndRunnersNamesBoth` |
| SVC_GW_VETTING_0056, 1–4 | `downloadAndExecuteInASkillScriptOrAFencedBlockBlocksAtItsLine` |
| SVC_GW_VETTING_0056, 5 (the existing 0048 test, unchanged) | `documentationAndUnlaunchedScriptsMentioningAFetchStaySilent` |
| Integration: blocks, blobs, group waivers clear it, a dependency alone warns | `ExecutableSurfaceTests.runtimeDependenciesWarnWhileLspAndSkillFetchesBlockUntilWaived` |
| Failure model: one walk for any number of skill files | `skillFilesAreReadInOneWalkWhateverTheirNumber` |
| Adversarial layouts (below) | `hostileLayoutsAreReadAsTheToolsReadThem` |
| Invariant: every existing test unedited and green | `git diff main -- src/test` adds tests and changes no existing assertion; 993/993 above |
| Invariant: hook and MCP ids, severities and messages unchanged | the unchanged `SVC_GW_VETTING_0047`–`0052` tests, all green |
| Invariant: additive API | `OpenApiContractTests`; the breaking-change job runs in CI |
| Invariant: no new dependency, network, subprocess or filesystem use | `pom.xml` and `package.json` unchanged; the new classes are pure over text |

RED was observed for every new test before its code existed. Six tests passed
the first time they ran: the silent-manifest test, the malformed-LSP test, the
silent-LSP cases, the walk-count test, and the integration test. They guard
behaviour that either existed by construction or was already implemented. Each
is proven against a mutant: M3/M4/M5, M12, M13, M11 and a throwaway
severity mutant respectively. The exception is the malformed-LSP test, whose
no-throw path is shared with the MCP reading. It is recorded as regression
armour with no dedicated mutant.

## Mutation

`mutants.sh`, over `857d393d`: 16 hand-written mutants, each applied alone,
killed only when the named test method fails, and restored with `git diff
--exit-code` proving it.

```console
M1-dev-dependencies-ignored killed
M2-lockfile-never-found killed
M3-vendored-not-excluded killed
M4-empty-array-declares killed
M5-python-counts-as-dependency killed
M6-prose-scanned-as-code killed
M7-launched-file-scanned-twice killed
M8-skill-fetch-medium killed
M14-located-at-last-line killed
M15-long-list-not-shortened killed
M16-first-wording-only killed
M9-lsp-carries-mcp-ids killed
M10-plugin-json-lsp-unread killed
M11-one-walk-per-file killed
M12-entry-without-command-read killed
M13-local-runner-flagged killed
all mutants killed
```

## Adversarial pass

Hostile inputs, all pinned by `hostileLayoutsAreReadAsTheToolsReadThem` and the
silent tests:

- CRLF line endings in a manifest and in a `SKILL.md`.
- A fence nested in a list item and indented eight spaces. **It found a
  bug:** CommonMark's three-space limit missed it. The fix is to accept a
  fence at any indentation (commit `37879974`).
- A four-backtick fence containing a three-backtick line, and a fence left
  open to the end of the file.
- A `pyproject.toml` with comments after its header and after `[`.
- `[target.'cfg(unix)'.dependencies]`, and `[dependencies.serde]` tables.
- A `requirements.txt` with only `--index-url`, and one with `-e .`.
- A `package.json` that is not JSON, and manifests under each vendored
  directory.

## Measurement

`VettingPrecisionMeasurement` over `anthropics/claude-plugins-official` at
`ad30d62c` (460 files: 53 plugin roots, 31 skills, 4 manifests, 12 LSP servers
declared in marketplace entries).

| | Before this change (`mcp-command-scanning` evidence) | After, at `857d393d` |
| --- | --- | --- |
| high | 2 (`runtime-package-run`, hook script) | 2, the same |
| `auto-run-hook` / `mcp-package-run` | 22 / 3 | 22 / 3 |
| `lsp-*` | not read | 0: all 12 servers run a binary on `PATH` |
| `skill-fetch-exec` | not read | 0 |
| `runtime-dependency` | not read | 10 |
| `executable-surface` wall-clock | not recorded | 754 ms |

The 10 `runtime-dependency` findings, judged by hand:

- 4 manifests (`discord`, `fakechat`, `imessage`, `telegram`), each a
  `package.json` with dependencies and a `bun.lock` beside it, which the
  message says. All four are true: the server installs them at start.
- 6 skill and reference files whose fenced blocks run `npm install` or `npx`.
  One is in `claude-code-setup`, one in `plugin-dev`, and four in
  `mcp-server-dev`, whose skills scaffold an MCP server. They match the rule
  as specified. The packages are installed into the user's project rather
  than into the plugin, so a reviewer will usually waive them in one step
  each.
- Before R2 the same content gave 26 findings, 20 of them in `mcp-server-dev`.
  R2 made that 10.

## Gauntlet layers

| Layer | Result |
| --- | --- |
| Full suite | 993 Java, 187 UI unit, 80 story, 23 e2e: all green |
| Types, lint, format | `tsc -b`, oxlint (warnings only, all pre-existing), Spotless, Checkstyle, all inside `mvnw verify` |
| Coverage on changed lines | not measured by a tool: no changed-line coverage is wired in this project. The mutation run and the per-shape fixtures stand in for it |
| Mutation | 16/16 killed |
| Property-based tests | not added. The inputs are bounded file shapes, covered one per fixture; no round-trip or ordering invariant applies |
| Real execution | the measurement runs the vetter over a real repository's pinned tree through `QuarantineSnapshot` |
| Supply chain | no new dependency; no network, subprocess or filesystem use added to production code |
| Suite health | the Storybook cache flake above; nothing else was re-run |

## Known limits (documented on the vetting page)

Variable indirection and encoded payloads. An install written as an inline
code span. A command inside a string argument (`os.system("npm install")`).
No lockfile resolution, and no vulnerability or malware lookup. Monitors and
`bin/` are not scanned.
