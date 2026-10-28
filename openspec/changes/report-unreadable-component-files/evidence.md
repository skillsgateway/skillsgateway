# Evidence: report-unreadable-component-files

Source state: `fee859a8` (tree `c624eb18`), on `main` at `ae1b53d6`. Every
result below is from one fresh run after the last code edit, on 2026-10-28.

Tier 2 (fail-closed vetting). Spec approval: the owner said to proceed without
stopping, to keep exposure short. Independent verification: not performed.
Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_VETTING_0069) | Test | Seen failing first |
|---|---|---|
| Oversized `.mcp.json`, `.lsp.json`, and the MCP and LSP files `plugin.json` names | `UnreadableComponentFileTests.an_oversized_server_declaration_file_is_reported` | yes: no finding for `p/.mcp.json` |
| Unparseable `.mcp.json` and `.lsp.json` | `…an_unparseable_server_declaration_file_is_reported` | yes |
| Oversized `SKILL.md` and agent file | `…an_oversized_skill_or_agent_file_is_reported` | yes |
| Readable or absent files raise nothing | `…readable_files_and_absent_ones_raise_nothing` | no: a control. M5 fails it |

**Changed assertion:**
`PluginComponentsTests.aMalformedHookFileIsAProblemAndNotAnException` asserted
that a malformed `.mcp.json` was *not* a problem, which is the behaviour this
change reverses. It now expects both problems, the hook file's and
`.mcp.json`'s. The test asserts more than before, not less.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1159, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 06:59 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)`. Two earlier runs hit the known harness flake (`iframe … did not become ready within 60000ms`). This passing run followed deleting `node_modules/.cache/storybook` and `.vite`; no frontend file changed. |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `360/360 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.58 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-report-unreadable-component-files/mutants.sh` | `5 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-oversize-silent killed
M2-unparseable-silent killed
M3-frontmatter-silent killed
M4-wrong-noun killed
M5-every-skill-reported killed
5 mutants run, all killed
```

## Known limits

- The finding is medium and warns. Whether an unreadable executable
  declaration should block is left to a chain-wide decision (design D3).
- The content inventory does not mark a plugin as incompletely read; the
  vetter's finding is where the signal appears.
