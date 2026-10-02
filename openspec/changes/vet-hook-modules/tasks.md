# Tasks: vet-hook-modules

The vetter is part of the approval gate, so this change is worked under
`.claude/skills/old-coder`. Every new test is shown failing before the code it
guards exists, or against a throwaway mutant when it passes on first run.

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0065 and GW_VETTING_0057–0060 with
  SVC_GW_INGEST_0065 and SVC_GW_VETTING_0057–0060 to `docs/reqstool/`. Revise
  GW_INGEST_0046 and SVC_GW_INGEST_0046 to name hook modules and LSP servers,
  and bump their revision. Verify with
  `openspec validate vet-hook-modules --strict`.

## 2. Reading modules (SVC_GW_INGEST_0065, SVC_GW_VETTING_0060)

- [x] 2.1 `HookModulesTests` / `PluginComponentsTests`: a `hooks/hooks.json`
  with `modules` yields one `HookModule` with its `hooks.json:line`, its
  `on('…')` events and `$` uses at `path:line`, and imports followed through
  `./x`, `../lib/y.ts`, a suffix-less specifier and `dir/index.ts`; events in an
  imported file are located there. `claude-code` imports are ignored; a bare
  package, a missing import, a binary and an oversized file land in
  `unscanned`. A commented-out `on('tool.call')` and `$.process` are not
  reported; a URL string with `//` is not taken for a comment. Modules from a
  `plugin.json` inline `hooks` and a marketplace-entry path also read. A module
  path escaping the root is a problem. Watch each fail.
- [x] 2.2 Unknown shapes: `{"modules": "x"}`, `{"hookz": {...}}`,
  `{"PreToolUse": {}}` and `{"hooks": {"Stop": "x"}}` are each one problem
  naming the key; `{"description": "…", "hooks": {...}}` and a bare event map
  with an unfamiliar event name are not. Watch each fail.
- [x] 2.3 Implement `HookModules` and extend `PluginComponents`
  (`@Requirements GW_INGEST_0065, GW_VETTING_0060`). Make 2.1 and 2.2 green, with
  every existing test unchanged and green.

## 3. Inventory API and portal (SVC_GW_INGEST_0065, SVC_GW_INGEST_0046)

- [x] 3.1 `PluginContent.hookModules` in `SnapshotContentService`; extend
  `ContentTests` (`@SVCs SVC_GW_INGEST_0065`). Regenerate `openapi.json` and
  `types.gen.ts`; confirm the diff is additive.
- [x] 3.2 Portal: a "hook module" kind in `snapshot-inventory.tsx` showing path,
  events and uses; story and component test (`@SVCs SVC_GW_INGEST_0046`). Verify
  with `pnpm test:stories` and the component test. Before/after screenshots for
  the PR (`.claude/skills/ui-screenshots`).

## 4. Vetting (SVC_GW_VETTING_0057–0060)

- [ ] 4.1 `ExecutableSurfaceVetterTests`: one medium `auto-run-module` per
  module naming its events; `module-steers-agent` at a `tool.call` and at a
  `prompt.compose` line; `module-runs-process` and low `module-calls-model` at
  their lines; a module whose `$.process` call runs `curl … | sh`, and an
  imported file with the same, are high `runtime-fetch-exec` at `file:line`;
  each `unscanned` entry is a medium `hook-target-unscanned`; an unknown shape is
  `hook-config-unreadable`. Negatives: a module reaching only `$.ui` has no
  capability finding; a file both a hook and a module load is scanned once.
  Watch each fail.
- [ ] 4.2 Implement in `ExecutableSurfaceVetter`
  (`@Requirements GW_VETTING_0057, GW_VETTING_0058, GW_VETTING_0059, GW_VETTING_0060`),
  with its description and coverage summary updated. Make 4.1 green.
- [ ] 4.3 Integration: extend `ExecutableSurfaceTests`
  (`@SVCs SVC_GW_VETTING_0057, SVC_GW_VETTING_0058, SVC_GW_VETTING_0059, SVC_GW_VETTING_0060`):
  a mod plugin is held with the module findings; a module with download-and-
  execute blocks at approval; waivers clear the medium findings.

## 5. Evidence of precision

- [ ] 5.1 Manual mutation: at least five mutants over the new code in
  `mutants.sh` in this change. Each is killed.

## 6. Docs (same PR)

- [ ] 6.1 `docs/manual/concepts/vetting.md`: the new rules in the
  `executable-surface` table, hook modules beside hooks, and the lexical scan's
  limits. `docs/manual/reference/portal.md`: the inventory kinds. Verify with
  `mkdocs build --strict`.

## 7. Gates and archive

- [ ] 7.1 A fresh run of all six gates after the last code edit. Write
  `evidence.md` with the commands, the result tails and the SHA.
- [ ] 7.2 `/opsx:archive` as the final commit, with the synced
  `openspec/specs/**` committed in it.
