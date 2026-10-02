# Evidence: vet-hook-modules

- **Source state:** every gate and the mutation run below ran over `f32bf945`,
  the commit after the last code edit. The only later changes are this file,
  `tasks.md` and the archive.
- **Tier:** 3 under `.claude/skills/old-coder`: the vetter is part of the
  approval gate.
- **Spec approval:** obtained. The owner reviewed the OpenSpec proposal, design
  and requirement set and replied "go ahead and apply". Two visible revisions
  were made during apply and are recorded in `design.md`: an event's value must
  be a list of matcher groups (D4), and the adversarial-pass fix (D2).
- **Entry points:** the six project gates below, and
  `openspec/changes/vet-hook-modules/mutants.sh` (`DRY=1` proves every mutant
  applies without running; `ONLY=<regex>` runs a subset).
- **Independent verification:** not performed. This is a declared downgrade.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 1064, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  30 passed (30)
[INFO]       Tests  219 passed (219)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:29 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  18 passed (18)
      Tests  94 passed (94)

$ (cd src/main/frontend && pnpm e2e)
  25 passed (1.4m)

$ reqstool status local -p docs/reqstool        # reqstool==0.12.0, as ci.yml pins it
331/331 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.55 seconds
```

What happened on the way:

- **Story tests.** After `mvnw clean verify` the first two runs failed to
  import story files ("Failed to fetch dynamically imported module") with no
  test failing: the known cache flake. After clearing
  `node_modules/.cache/storybook` and `node_modules/.vite`, the third run
  passed 94/94. The same happened, and was cleared the same way, on the earlier
  full run at `2a7faeaf`.
- **reqstool.** The locally installed CLI is an editable development build that
  prints no `PASS` line; the gate ran from a scratch venv holding
  `reqstool==0.12.0`, the version CI installs.
- **Leaked containers:** none after the gates
  (`docker ps --filter label=org.testcontainers=true` returned nothing).

## Spec → tests

| Scenario | Verified by |
| --- | --- |
| SVC_GW_INGEST_0065: module path, declaring line, events and uses at `path:line`, imports followed (relative, `../`, suffix-less, `index`), `claude-code` skipped, package / missing / binary unscanned, commented registration not listed | `HookModulesTests.aModuleIsListedWithItsEventsUsesImportsAndWhatCouldNotBeScanned`, `importsResolveWithSuffixesAndIndexFilesAndCyclesEnd` |
| SVC_GW_INGEST_0065: modules from an inline `plugin.json` hooks object and a marketplace-entry path | `modulesAreReadFromAnInlinePluginManifestAndAMarketplaceEntryPath` |
| SVC_GW_INGEST_0065: missing or oversized module listed as not scanned; escaping path is a problem | `aMissingOrOversizedModuleIsListedAsNotScanned`, `aModulePathEscapingThePluginRootIsAProblemNotAModule` |
| SVC_GW_INGEST_0065: the API lists `hookModules`; the OpenAPI diff is additive | `ContentTests.snapshotContentListsHookModulesWithTheirEventsAndUses`, `OpenApiContractTests` (diff: 108 lines added, none removed) |
| SVC_GW_INGEST_0046: the portal count and expanded module list | `snapshot-inventory.test.tsx` `each_component_kind_is_a_collapsed_count_that_expands_in_place`, `a_hook_module_names_what_it_imports_and_what_could_not_be_scanned`; stories `HookModulesExpanded`, `HookModuleNotScanned` |
| SVC_GW_VETTING_0057 | `ExecutableSurfaceVetterTests.everyHookModuleWarnsNamingItsEventsAndUses` |
| SVC_GW_VETTING_0058 | `aModuleThatSteersTheAgentRunsProgramsOrCallsModelsIsFlaggedForEach`, `aModuleReachingOnlyTheUiHasNoCapabilityFinding` |
| SVC_GW_VETTING_0059 | `downloadAndExecuteInAModuleOrAnImportBlocks`, `aModuleOrImportThatCannotBeReadIsReported`, `aFileBothAHookAndAModuleLoadIsScannedOnce` |
| SVC_GW_VETTING_0060 | `HookModulesTests.anUnrecognisedKeyIsOneProblemNamingIt`, `aRecognisedShapeIsNoProblem`, `anUnrecognisedKeyInAnInlinePluginManifestHooksObjectIsAProblem`; `ExecutableSurfaceVetterTests.anUnrecognisedHooksShapeIsReported`, `aRecognisedHooksShapeIsNotReported` |
| Integration: held, findings grouped, an imported fetch blocks approval, a waiver clears it, medium findings only warn | `ExecutableSurfaceTests.aHookModuleIsHeldWithItsFindingsAndItsImportedFetchBlocksUntilWaived` |
| Adversarial: regex literal holding `//`, CRLF, a template literal holding `//`, a commented-out import | `HookModulesTests.hostileModulesAreReadAsWritten` |
| Invariant: every existing test unedited and green | `git diff main -- src/test` adds tests and assertions; the one existing assertion edited (`snapshot-inventory.test.tsx`) only gains lines. 1064/1064 above |
| Invariant: hook, MCP and LSP ids, severities and messages unchanged | the unchanged `SVC_GW_VETTING_0047`–`0056` tests, all green |
| Invariant: no new dependency, network, subprocess or filesystem use | `pom.xml` and `package.json` unchanged; `HookModules` is pure over text |
| Invariant: stop rule | no package, role, estate object, sweep or configuration leaf; `ConfigSurfaceBudgetTests` and `ContextBudgetTests` unchanged and green |

RED was observed for every new test before its code existed, with these
exceptions, each proven against a mutant instead:

- `aRecognisedShapeIsNoProblem` (three cases) passed first: the shapes already
  read without a problem. Proven by M6.
- `anUnrecognisedHooksShapeIsReported` and `aRecognisedHooksShapeIsNotReported`
  in the vetter passed once the reader produced the problems, since the vetter
  already maps `hookProblems`. Proven by M5 and M6 through the reader tests.
- `aFileBothAHookAndAModuleLoadIsScannedOnce` passed before modules were
  scanned at all. Proven by M12.
- `ExecutableSurfaceTests.aHookModuleIsHeldWithItsFindingsAndItsImportedFetchBlocksUntilWaived`
  was written after the vetter. Proven by M14.

One test failed for a reason that changed the design, not the test:
`anUnrecognisedKeyInAnInlinePluginManifestHooksObjectIsAProblem` showed that
`{"mods": ["x"]}` read as an event. The shape rule now requires a list of
matcher groups (D4).

## Mutation

`mutants.sh`, over `f32bf945`: 16 hand-written mutants, each applied alone,
killed only when the named test method fails, restored with `git diff
--exit-code` proving it.

```console
M1-comments-kept killed
M2-strings-not-protected killed
M3-imports-not-followed killed
M4-package-taken-for-a-path killed
M5-unknown-key-silent killed
M6-description-reported killed
M7-any-array-is-an-event killed
M8-escaping-module-read killed
all mutants killed
M9-prompt-compose-not-steering killed
M10-model-call-medium killed
M11-module-files-unscanned killed
M12-file-scanned-twice killed
M13-unscanned-unreported killed
M14-module-reported-as-hook killed
M15-regex-taken-for-comment killed
M16-raw-imports-ignored killed
all mutants killed
```

M4 would have survived the first draft of the tests: a package taken for a
path is reported as "not in the snapshot", and that message also contains
`'lodash'`. The assertion was tightened to "the package 'lodash'" (commit
`bad2c827`) before the run.

## Adversarial pass

It found a bug. A regex literal holding `//` (`/a\/\//`) was blanked as a line
comment, hiding an `import` and a `$.process` after it on the same line; the
hidden import was neither scanned nor reported. Fixed in `a6cc6ec3`: regex
literals are kept where one can start, and imports are matched on the raw text
as well. Also probed: CRLF line endings, a `//` inside a template literal, a
commented-out import (followed), and an import cycle (followed once, in
`importsResolveWithSuffixesAndIndexFilesAndCyclesEnd`). Unterminated comments
and strings were not probed by a test.

## Known limits

- The scan is lexical. A renamed `$`, an interface held in a variable
  (`const p = $.process`), `$['process']`, or an event name built at run time
  walks past the capability rules. The module itself is always
  `auto-run-module`, and its files are always scanned for run-time fetches.
- A `/` after `)` or an identifier is read as division, so a regex literal
  there (rare: after a keyword such as `return`) can still hide the rest of its
  line from `on`/`$` matching. Imports are unaffected, since they are matched on
  the raw text too.
- The portal shows each event's and interface's `path:line` only as a tooltip;
  the same locations are on the vetting findings. Recorded as a P3 from the
  `/impeccable audit` pass.
- Skipped: no precision measurement against a public marketplace
  (`VettingPrecisionMeasurement`) was run for this change.
