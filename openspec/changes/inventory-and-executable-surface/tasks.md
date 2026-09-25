# Tasks: inventory-and-executable-surface

The new vetter is part of the approval gate, so this change is worked under
`.claude/skills/old-coder` at Tier 3. Every adversarial and precision test is
shown failing before the code it guards exists, or against a throwaway mutant
when it passes on first run. Spec approval was not obtained: this is an
autonomous run, and the spec is reviewed after the fact. The results are
recorded in `evidence.md`.

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0045, GW_INGEST_0046, GW_VETTING_0047, GW_VETTING_0048
  and GW_VETTING_0049, each with its SVC, and verify with
  `openspec validate inventory-and-executable-surface --strict`.

## 2. The plugin layout reader (SVC_GW_INGEST_0045)

- [x] 2.1 `PluginComponentsTests` (pure, no Spring). Cover default locations;
  manifest paths that replace commands and agents; hooks merged from
  `hooks/hooks.json`, a `plugin.json` path, inline and array forms, and a
  marketplace entry; frontmatter hooks in a skill and an agent; `.mcp.json`
  plus inline `mcpServers`; path:line of each hook; `..` escape ignored; a
  malformed file becomes a problem and not an exception. Watch it fail
  against a stub.
- [x] 2.2 `PluginComponents` in `ingestion/`, annotated
  `@Requirements GW_INGEST_0045`. Make 2.1 green.
- [x] 2.3 `SnapshotContentService.PluginContent` gains `commands`, `agents`,
  `hooks` and `mcpServers`, read through a `Files` view of the pinned tree.
  `diff()` does not compute components. Verify with an integration test in
  `ContentTests` (`@SVCs SVC_GW_INGEST_0045`) through
  `GET /api/v1/snapshots/{id}/content`.
- [x] 2.4 Regenerate `openapi.json` and `types.gen.ts`. Verify that
  `OpenApiContractTests` passes.

## 3. The executable-surface vetter (SVC_GW_VETTING_0047–0049)

- [x] 3.1 `ExecutableSurfaceVetterTests` over `InMemorySnapshot`. Cover:
  - positives: pipe to a shell, substitution, a package runner,
    `curl -o` + `chmod +x` in a launched script (direct, in a subdirectory,
    through a second script, in exec-form `args`), obfuscated `c''url` and
    `"cu"rl`, and the trial launcher's shape;
  - negatives: docs mentioning curl, the same script with no hook launching
    it, a comment line, a local-only hook, a download that is never made
    executable;
  - auto-run hooks from every source, with the trigger in the message;
  - unreadable config, and binary or oversized launched files.

  Watch it fail against a stub.
- [x] 3.2 `ExecutableSurfaceVetter` (order 250, version 1),
  `@Requirements GW_VETTING_0047/0048/0049`, and the name reserved in
  `ExternalConnectorProperties`. Make 3.1 green.
- [x] 3.3 Integration test `ExecutableSurfaceTests` (`@SVCs` 0047, 0048,
  0049), end to end through ingestion. Cover: a hook-bearing snapshot blocks;
  its finding groups across a vendored copy and a group waiver clears it; a
  hook-free snapshot passes with a summary; switching the vetter off records
  `DISABLED`.
- [ ] 3.4 Fix existing tests that assumed four built-ins. They are corrected,
  not weakened. Verify that `./mvnw clean verify` passes.
- [ ] 3.5 Manual mutation: at least five mutants over the rules and the
  launcher resolution, kept in `mutants.sh`. Each one is killed.
- [x] 3.6 Measure against `pbakaus/impeccable` at a pinned SHA and record the
  numbers in evidence.md.

## 4. Portal (SVC_GW_INGEST_0046)

- [ ] 4.1 `snapshot-inventory.tsx`, with a story and a unit test
  (`@SVCs SVC_GW_INGEST_0046`) that covers collapsed-by-default, counts for
  the present kinds only, and expanding the hooks. Update MSW handlers.
  `snapshot-card.tsx` calls it. Verify with `pnpm test:stories` and
  `./mvnw verify`.
- [ ] 4.2 Run `/impeccable audit` and `harden` on the Inventory tab. Fix the
  findings or dismiss each one in the PR body with a reason.

## 5. Docs (same PR)

- [ ] 5.1 Update `concepts/vetting.md` (the chain diagram, the built-in list
  with a new `executable-surface` section, and the known limits),
  `reference/portal.md` (Inventory), `reference/api/marketplaces.md` (the
  content fields), the capability map row, the glossary, and
  `reference/configuration.md` (the reserved names). Verify with
  `mkdocs build --strict`.

## 6. Gates and archive

- [ ] 6.1 Do a fresh run of all six gates after the last code edit. Write
  `evidence.md` with the commands, the result tails and the SHA.
- [ ] 6.2 `/opsx:archive` as the final commit, with the synced
  `openspec/specs/**` committed in it.
