# Evidence: vet-repeated-source-entries

Source state: `925243f7` (tree `98c7a787`): the change merged with `main` at
`99a29cac` (#933, Spring Boot 4.2 and Jackson 3), which renamed the JSON API
and added a `Json` class the cache's record had to give way to. Every result
below is from one fresh run after that last code edit, on 2026-11-23. An
earlier full run on `6e26adda`, before the merge, was also green
(`Tests run: 1392`).

Tier: trust boundary (vetting). Spec approval: not obtained before coding.
The owner asked for the fix and a PR in one message ("You can implement the fix
as well and create a PR"), so the spec (proposal, design, delta spec, this
mapping) is the artifact reviewed after the fact. Independent verification:
not performed. Both are declared downgrades.

## Spec → tests

| Scenario | Test | Seen failing first |
|---|---|---|
| SVC_GW_VETTING_0080: a later entry's inline `curl \| sh` hook is flagged at its manifest line with its fetch, and fails | `ExecutableSurfaceVetterTests.aHookInlineOnALaterEntryOverASeenSourceIsFlaggedWithItsFetch` | yes: `Expected size: 1 but was: 0 in: []` |
| SVC_GW_VETTING_0080: a later entry's inline MCP server is examined | `ExecutableSurfaceVetterTests.anMcpServerInlineOnALaterEntryOverASeenSourceIsExamined` | yes: `Expected size: 1 but was: 0 in: []` |
| SVC_GW_VETTING_0080: a skills directory only a later entry declares has its frontmatter hooks flagged | `ExecutableSurfaceVetterTests.aSkillsDirectoryOnlyALaterEntryDeclaresHasItsFrontmatterHooksFlagged` | yes: `Expected size: 1 but was: 0 in: []` |
| SVC_GW_VETTING_0080: the source's own hook is counted once however many entries read it | `ExecutableSurfaceVetterTests.aHookInTheSourcesOwnFileIsCountedOnceHoweverManyEntriesReadTheSource` | yes: `Expecting actual: ["p/hooks/hooks.json:3"]` (the entry's hook missing) |
| SVC_GW_VETTING_0080: every entry key is honoured, a non-declaring entry adds nothing, the limit holds | `PluginComponentsTests.a_later_entry_over_a_seen_root_is_read_when_it_declares_components` (×6 keys) | new API; killed by M1, M2, M4 |
| SVC_GW_VETTING_0066: a manifest past 1,000 plugins fails closed | `PluginRootBoundTests.a_manifest_with_more_plugins_than_the_bound_fails_closed` | killed by M6 |
| Invariant: the cache saves work, never the answer (JSON and frontmatter read once) | `PluginComponentsTests.a_shared_cache_reads_each_frontmatter_once_and_every_plugin_still_gets_its_hooks_and_problems` | killed by M5 |
| Invariant: existing SVC tests unchanged | full suite below | — |

## A spec change made during the work, and why

The first design also changed `SurfaceComparison`. Its test, a first
snapshot whose only surface is a later entry's inline hook, failed on `main`
with `not inert: .claude-plugin/marketplace.json (declares more than
descriptive fields)`, not with the missing hook: the comparison already
refuses any manifest whose entries declare more than descriptive fields. The
change there would have been unobservable, so it was reverted with its test,
and proposal, design and tasks were revised to say why.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, UI gate, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify` | `Tests run: 1400, Failures: 0, Errors: 0, Skipped: 9` · UI `Test Files 46 passed (46)` · `Tests 348 passed (348)` · `BUILD SUCCESS` · `Total time: 11:24 min` |
| Requirements traceability (backend results only) | `reqstool status local -p docs/reqstool` | `378/422 complete · 44 incomplete · FAIL`; every incomplete id is a portal e2e case (`automated test missing` without the Playwright results); `GW_VETTING_0047` complete. The full gate runs in CI's Traceability job with the e2e results |
| Storybook story tests, e2e | not run locally | no file under `src/main/frontend/` changed; CI runs both |
| OpenSpec | `openspec validate --all --strict` | `Totals: 29 passed, 0 failed (29 items)` (one fewer: this change is archived) |
| Docs | `mkdocs build --strict` | `Documentation built in 2.18 seconds` |
| Mutation | `openspec/changes/archive/2026-11-23-vet-repeated-source-entries/mutants.sh` | `6 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-first-entry-only killed
M2-skills-key-dropped killed
M3-hooks-counted-per-read killed
M4-entry-limit-ignored killed
M5-json-not-cached killed
M6-no-plugin-cap-finding killed
6 mutants run, all killed
```
