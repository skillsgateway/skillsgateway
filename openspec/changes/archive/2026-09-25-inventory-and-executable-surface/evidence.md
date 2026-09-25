# Evidence: inventory-and-executable-surface (Tier 3)

- **Spec approval:** not obtained. This was an autonomous run. The acceptance
  criteria are `proposal.md`, `design.md` and the SVCs of:
  - GW_INGEST_0045 — The snapshot inventory lists every component a plugin
    declares;
  - GW_INGEST_0046 — The portal inventory shows each plugin's components as
    expandable counts;
  - GW_VETTING_0047 — Hooks that run automatically are flagged with their
    trigger;
  - GW_VETTING_0048 — Code a hook fetches at run time is a high-severity
    finding;
  - GW_VETTING_0049 — Hook declarations and launched files the vetter cannot
    read are reported.

  They were committed at `2be869db`, before any implementation commit. They are
  the artifacts to review after the fact, and confidence is claimed
  accordingly.
- **Source state:** `4e625a95` on `feat/inventory-and-executable-surface`. All
  the gates below, the mutation run and the measurements ran over that commit,
  after the last code edit. The only later changes are this file, `tasks.md`
  and the archive.
- **Entry points:** the project gates below;
  `openspec/changes/inventory-and-executable-surface/mutants.sh`; and
  `VettingPrecisionMeasurement` for the measurements.
- **Independent verification:** not performed.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 912, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  22 passed (22)
[INFO]       Tests  187 passed (187)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:08 min

$ (cd src/main/frontend && pnpm test:stories)   # after rm -rf node_modules/.cache/storybook node_modules/.vite
 Test Files  15 passed (15)
      Tests  73 passed (73)
# The third run. The first two failed in the known harness flake: 9 files
# failed to collect, then an iframe "did not become ready within 60000ms".
# No assertion failed in either run.

$ (cd src/main/frontend && pnpm e2e)
  22 passed (1.1m)

$ uvx --from reqstool==0.12.0 reqstool status local -p docs/reqstool
297/297 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
exit 0
```

No existing test had to change for the new vetter. The chain gained a fifth
verdict, and no test asserted the chain's length. The one existing assertion
that did change is the e2e Inventory step. Skills are now a collapsed count, so
the step now expands "1 skill", asserts that it was collapsed first, and then
checks the skill. That makes the step stricter than before, not weaker.

## Behaviours → tests

| Behaviour | Test |
| --- | --- |
| Commands, agents, hooks and MCP servers are read from default locations, manifest paths (which replace), inline and array hooks, the marketplace entry, and skill/agent frontmatter, each hook at `path:line` | `PluginComponentsTests` (11) |
| A path that escapes the plugin is ignored, and a malformed or oversized hook file is a problem rather than an exception | `PluginComponentsTests.aPathEscapingThePluginRootIsIgnored`, `aMalformedHookFileIsAProblemAndNotAnException`, `anOversizedHookFileIsAProblem` |
| The inventory API carries each component, and a malformed file elsewhere costs only that plugin's hooks | `ContentTests.snapshotContentListsEveryComponentWithEachHooksTrigger` |
| Every hook is a medium `auto-run-hook` naming its trigger, from every declaration source; unlisted plugin roots are vetted; other harnesses' files are not | `ExecutableSurfaceVetterTests.everyHook…`, `hooksDeclaredInPluginJsonTheEntryAndFrontmatterAllWarn`, `aPluginTheManifestDoesNotListIsStillVetted`, `otherHarnessesHookFilesAreNotClaudeCodePluginHooks` |
| Download-and-execute in a hook command blocks at the hook's line. 13 shapes are covered, including `c''url`, `"cu"rl`, a backslash in the name, `iex (iwr …)`, and Python `exec(urlopen)` | `aHookCommandThatDownloadsAndExecutesBlocks` |
| Package runners and installs block | `aHookThatRunsAPackageRunnerBlocks` (8), `anExecFormHookIsScannedAsCommandAndArguments`, `aScriptThatHandsPipInstallToASubprocessBlocks` |
| A launched script is followed directly, in a subdirectory, through a second script, and beside the skill that declares the hook; a continued line is one command | `theTrialLauncherShape…`, `aScriptInASubdirectory…`, `aHookInFrontmatterLaunchingAScript…`, `aContinuedLineIsOneCommand`, `aLaunchedScriptThatDownloadsAFileAndMakesItExecutableBlocks` (4) |
| Negatives stay silent. Checked: docs mentioning curl, an unlaunched script, comments, a download never made executable, `\| jq`, `\| shasum`, `\|\| sh`, an echo mentioning npx or pip, `npx --no-install`, a checksum sidecar, prose in backticks, and a path outside the plugin | `documentationAndUnlaunchedScripts…`, `aLaunchedScriptWithoutDownloadAndExecuteStaysSilent` (13), `aReferenceOutsideThePluginRootIsNotFollowed` |
| An unparseable hook file, and a binary or oversized launched file, are medium findings | `anUnparseableHookFileIsReported`, `aBinaryOrOversizedFileAHookRunsIsReported` |
| End to end: blocks at approval, groups across a vendored copy, a group waiver clears it, a hook-free snapshot passes with a summary, and the switch records `DISABLED` | `ExecutableSurfaceTests` (3) |
| The portal shows collapsed counts for the present kinds only, and each expands in place | `snapshot-inventory.test.tsx` (`SVC_GW_INGEST_0046`), the `PluginInventory` stories (5, axe as errors), the e2e Inventory step |

RED was observed before GREEN in each case:

- `PluginComponentsTests` gave 11/11 errors against a stub.
- `ExecutableSurfaceVetterTests` gave 42 errors and 1 failure against a stub.
- The checksum-sidecar, echo-prose, backtick-prose and `pip install` fixtures
  were added after measuring the real repositories below showed those shapes.
  Each shape was a false positive or a false negative of the build that
  preceded its fixture.
- The portal test was written after its component. The M12 mutant below is its
  proof that it can fail.

## Mutation (manual, `mutants.sh`)

The run is split in two with `ONLY=`, because the whole set takes longer than
one tool call. Each mutant counts as killed only when the named test fails, and
the restore is checked with `git diff --exit-code`.

```console
$ ONLY='^M(1|2|3|4|5|6)-' openspec/changes/inventory-and-executable-surface/mutants.sh
M1-no-pipe-rule killed
M2-comments-scanned killed
M3-any-chmod-covers-any-download killed
M4-quoting-not-removed killed
M5-no-transitive-follow killed
M6-hook-is-info killed
$ ONLY='^M(7|8|9|10|11|12)-' openspec/changes/inventory-and-executable-surface/mutants.sh
M7-unscanned-target-silent killed
M8-no-install-still-fetches killed
M9-manifest-only-roots killed
M10-plugin-json-hooks-ignored killed
M11-escape-allowed killed
M12-open-by-default killed
```

That is 12/12 killed.

## Measured on real repositories

The measurement uses `VettingPrecisionMeasurement` over local clones. It runs
only with `-Dvetting.measure.repo=…`, and never in the build.

| Repository | Plugin roots | Hooks | Launched files | High | Medium |
| --- | --- | --- | --- | --- | --- |
| `pbakaus/impeccable` @ `9d715cc4` | 2 | 3 | 2 | 2 | 3 |
| `anthropics/claude-plugins-official` @ `ad30d62c` | 53 | 22 | 15 | 2 | 22 |

- **impeccable.** The 3 medium findings are the three hooks: `SessionStart`,
  `PostToolUse` for `Edit|Write`, and `Stop`. The 2 high findings are
  `runtime-fetch-exec` at `plugin/skills/impeccable/scripts/impeccable:97` and
  `:99`. Those are the `curl -o "$tmp"` and `wget -O "$tmp"` lines that fetch
  the engine binary, and `chmod +x "$tmp"` later makes that binary executable.
  An earlier build reported 5 high findings. Three were false positives: the
  two `.sha256` sidecar downloads, and a help message that mentions "curl or
  wget". Checking that the downloaded file is the one made executable, and that
  `wget` is invoked as a command, removed them.
- **claude-plugins-official.** The 2 high findings are `runtime-package-run` at
  `plugins/security-guidance/hooks/ensure_agent_sdk.py:433` and `:594`. At each
  of those lines, a `SessionStart` hook hands `pip install` to a subprocess, so
  it installs a package from PyPI at run time. That is the class of code this
  vetter exists to surface. An earlier build also fired on three lines of prose
  in backticks, and backticks no longer count as command position.

## Layers

| Layer | Result |
| --- | --- |
| Full suite | 912 Java tests (9 skipped, all pre-existing), 187 UI unit tests, 73 story tests and 22 e2e tests, all green |
| Types, lint and format | `tsc -b`, oxlint, Spotless and Checkstyle, all inside `mvnw verify` |
| Mutation | 12/12 killed |
| Real execution | the measurements above run the vetter over two real repositories' pinned trees through `QuarantineSnapshot` |
| Adversarial | obfuscated command names; hooks in `plugin.json` against `hooks.json`; hooks in the manifest entry and in frontmatter; a script in a subdirectory and through a second script; a plugin missing from the manifest; `..` escapes; a binary target; a malformed configuration |
| Coverage on changed lines | not measured by a tool. The mutation run and the per-rule fixtures stand in for it |
| Supply chain | no new dependency; no network, subprocess or filesystem use added to production code |

## Known limits

- Variable indirection (`$FETCH "$url" | sh`) and encoded payloads walk past
  the rules. The vetter description and the docs say so.
- MCP server and monitor commands are inventoried, not scanned (design,
  Non-Goals).
- A launched file that downloads to a file and makes that same file executable
  is matched by name. A download renamed by `mv` before `chmod` is caught only
  when the `chmod` target is the downloaded name. It is when the rename comes
  after, as in the trial launcher.
