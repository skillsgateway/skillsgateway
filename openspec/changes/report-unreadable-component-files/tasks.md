# Tasks: report-unreadable-component-files

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_VETTING_0066 and SVC_GW_VETTING_0069 in `docs/reqstool/`.

## 2. Report every unreadable component file (SVC_GW_VETTING_0069)

- [x] 2.1 `UnreadableComponentFileTests`. Oversized `.mcp.json`, `.lsp.json`,
  declared MCP and LSP files, `SKILL.md` and agent file, and unparseable
  `.mcp.json` and `.lsp.json`, each give one medium `hook-config-unreadable`
  finding naming the file, and the verdict does not pass. Readable or absent
  files give nothing. Watch it fail first.
- [x] 2.2 `Reader.json` takes what the file declares and always records the
  problem. `frontmatterHooks` records one for a file it cannot read
  (`@Requirements GW_VETTING_0066`). `PluginComponentsTests` expects the
  `.mcp.json` problem beside the hook file's.

## 3. Documentation

- [x] 3.1 `concepts/vetting.md`: the `hook-config-unreadable` row covers server
  files and skill or agent files. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [ ] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
