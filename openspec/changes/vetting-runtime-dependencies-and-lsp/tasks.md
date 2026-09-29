# Tasks: vetting-runtime-dependencies-and-lsp

The vetter is part of the approval gate, so this change is worked under
`.claude/skills/old-coder` at Tier 3. Every new test is shown failing before
the code it guards exists, or against a throwaway mutant when it passes on
first run.

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_VETTING_0053 to GW_VETTING_0056, with
  SVC_GW_VETTING_0053–0056, to `docs/reqstool/`. Revise GW_INGEST_0045 and
  SVC_GW_INGEST_0045 so that the inventory includes LSP servers, and bump
  their revision. Verify with
  `openspec validate vetting-runtime-dependencies-and-lsp --strict`.

## 2. LSP servers in the layout reader (SVC_GW_INGEST_0045)

- [x] 2.1 `PluginComponentsTests`: LSP servers from `.lsp.json`, an inline
  `plugin.json` `lspServers`, a referenced JSON file, an array of both, and the
  marketplace entry. Each has its command, arguments and the `path:line` of its
  `command`. A later name replaces an earlier one. An entry without `command`
  is a problem, not a server. Watch it fail.
- [x] 2.2 `PluginComponents.Components.lspServers` and `lspCommands`,
  annotated `@Requirements GW_INGEST_0045`. Make 2.1 green.
- [x] 2.3 Add `lspServers` to the inventory's `PluginComponent` schema.
  Regenerate the OpenAPI contract and confirm the diff is additive only. Extend
  the inventory API test (`@SVCs SVC_GW_INGEST_0045`).
- [x] 2.4 Portal: the `lspServers` kind in `snapshot-inventory.tsx`, with its
  story and test. Verify with `pnpm test:stories` and the component test.

## 3. LSP command scanning (SVC_GW_VETTING_0055)

- [x] 3.1 `ExecutableSurfaceVetterTests`: an LSP server running `npx -y …`
  is one medium `lsp-package-run` at its command line. One piping a download
  into a shell through `sh -c` is one high `lsp-fetch-exec`. A launched plugin
  script is followed, with findings at its `path:line`. Negatives stay silent:
  `gopls` on `PATH`, a binary in the plugin, and `npx ${CLAUDE_PLUGIN_ROOT}/srv`.
  A script both a hook and an LSP server launch keeps the hook's high finding.
  Watch each fail.
- [x] 3.2 Extend `ExecutableSurfaceVetter` (`@Requirements GW_VETTING_0055`)
  through the MCP command path, with its own visited set and rule ids. Make 3.1
  green, and keep every existing test green unchanged.

## 4. Skill, command and agent files (SVC_GW_VETTING_0053, SVC_GW_VETTING_0054, SVC_GW_VETTING_0056)

- [x] 4.1 Tests for 0053. One medium `runtime-dependency` at the declaring
  line, with lockfile presence in the message, for: `package.json` with
  `dependencies`, and with only `devDependencies`; `requirements.txt`;
  `pyproject.toml` with an inline array, with a multi-line array, and with
  poetry dependencies; `Cargo.toml` with `[dependencies]`. Silent for: a
  `package.json` with only scripts, a poetry table with only `python`, and
  manifests under `node_modules/`, `.venv/` and `target/`. Watch each fail.
- [x] 4.2 Tests for 0054. One medium `runtime-dependency` at the line for:
  `npm install` in a skill's `.sh`; `pip install` in an extensionless file
  with a shebang; `npx -y` in a fenced block of `SKILL.md`; `uvx` in a `~~~`
  block of a skill's reference Markdown; and `pip install` in a command's
  and an agent's fenced block. Silent for: `npm install` in prose and in an
  inline span of `SKILL.md`, in a fenced block of the plugin-root `README.md`,
  and in a script already scanned as a hook's launched
  file (which keeps its high finding). A `curl | sh` in a skill script yields
  no `runtime-dependency`. Watch each fail.
- [x] 4.2a Tests for 0056. One high `skill-fetch-exec` at the line for: `curl … | sh`
  in a skill's `.sh`; download-then-chmod-then-run in a skill's `.py`; `curl
  … | bash` in a fenced block of `SKILL.md` and of an agent. Silent for: a
  download never made executable, and a prose mention. Watch each fail.
- [x] 4.3 Implement all three in `ExecutableSurfaceVetter`
  (`@Requirements GW_VETTING_0053, GW_VETTING_0054, GW_VETTING_0056`), with the vetter's
  description and coverage summary updated. Make 4.1, 4.2 and 4.2a green.
- [x] 4.4 Integration: extend `ExecutableSurfaceTests`
  (`@SVCs SVC_GW_VETTING_0053, SVC_GW_VETTING_0054, SVC_GW_VETTING_0055,
  SVC_GW_VETTING_0056`). A `skill-fetch-exec` blocks. A
  plugin with a `package.json` and an `lsp-fetch-exec` blocks at approval. The
  finding carries a blob, and group waivers clear it. The `runtime-dependency`
  alone warns.

## 5. Evidence of precision

- [x] 5.1 Manual mutation: at least five mutants over the new code in
  `mutants.sh` in this change. Each is killed.
- [x] 5.2 Measure on `anthropics/claude-plugins-official` at a pinned SHA with
  `VettingPrecisionMeasurement`. Record the new findings by rule and severity,
  and a sample judged true or false positive, in `evidence.md`.

## 6. Docs (same PR)

- [x] 6.1 `concepts/vetting.md`: add the three rules to the
  `executable-surface` table and describe LSP servers beside MCP servers.
  Replace the two limitation paragraphs from PRs #521 and #528 with what
  remains unseen: manifest resolution and vulnerabilities. In "Why runtime
  fetching blocks", say it blocks wherever it is found, and note the deliberate
  overlap with `pipe-to-shell`. `reference/portal.md`: the inventory kinds. Verify
  with `mkdocs build --strict`.

## 7. Gates and archive

- [x] 7.1 A fresh run of all six gates after the last code edit. Write
  `evidence.md` with the commands, the result tails and the SHA.
- [ ] 7.2 `/opsx:archive` as the final commit, with the synced
  `openspec/specs/**` committed in it.
