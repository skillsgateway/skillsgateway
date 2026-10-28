# Evidence: vetting-reads-invalid-utf8

Source state: `5f146afa` (branch `fix/vetting-reads-invalid-utf8`, on
`origin/main` `a056c258`). Every result below is from one fresh run after the
last code edit, on 2026-10-28.

Tier 2 (a fail-open in vetting coverage). Spec approval: the owner said to
proceed without stopping, to keep exposure short. Independent verification:
not performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_VETTING_0063) | Test | Seen failing first |
|---|---|---|
| A skill injection still fails with a stray byte | `InvalidUtf8IsTextTests.a_stray_byte_in_a_skill_does_not_hide_an_injection` | yes |
| A token still fails | `…a_stray_byte_does_not_hide_a_secret` | yes |
| A hook script that downloads and executes still fails | `…a_stray_byte_does_not_hide_what_a_hook_script_fetches_and_runs` | yes |
| A hook-module import that does still fails | `…a_stray_byte_does_not_hide_what_a_hook_module_import_fetches_and_runs` | yes |
| An MCP server script that does still fails | `…a_stray_byte_does_not_hide_what_an_mcp_server_script_fetches_and_runs` | yes |

Each one failed at the "with a stray byte" assertion: the fixture failed vetting
when clean and did not once the byte was added.

**Existing tests changed, with their assertions kept:**
`SkillConformanceVetterTests.aSkillTheVetterCouldNotReadIsInformationalAdvisoryAndBlockingUnderEnforcement`
and `ExternalVettingConnectorUnitTests.aBinaryFileIsShippedUnscannedNotDropped`
used `0xC3 0x28` and `0xFF 0xFE 'x'` as their "binary" fixture. Under this
change those are text, so each fixture gains a NUL byte. The assertions about
how an unreadable file is reported are unchanged, except that the expected
wording is now "binary", matching the message change in the proposal.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1113, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 08:38 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.9m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `343/343 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.68 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-vetting-reads-invalid-utf8/mutants.sh` | `3 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-content-rules-strict killed
M2-hook-modules-strict killed
M3-nul-not-binary killed
3 mutants run, all killed
```

## Known limits

- A file holding a NUL byte is still skipped. In an executable position it
  yields only the medium `hook-target-unscanned` finding that
  `GW_VETTING_0049 — Hook declarations and launched files the vetter cannot
  read are reported` specifies, which warns and does not block. A binary
  launched by an MCP or LSP server is not reported at all. Making unreadable
  executable or instruction files block is a separate change (design.md, D3).
