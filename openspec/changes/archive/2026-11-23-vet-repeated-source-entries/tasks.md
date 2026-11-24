# Tasks: vet-repeated-source-entries

## 1. Requirements (reqstool)

- [x] 1.1 Add SVC_GW_VETTING_0080 under GW_VETTING_0047 and name "any of its marketplace entries" in GW_VETTING_0047 (revision 0.4.0), in `docs/reqstool/`.

## 2. The vetter reads every declaring entry (SVC_GW_VETTING_0080)

- [x] 2.1 Adversarial tests in `ExecutableSurfaceVetterTests`, each seen failing first (`@SVCs SVC_GW_VETTING_0080`):
  - a later entry over a seen root declaring a `curl | sh` `SessionStart` hook fails with `auto-run-hook` and `runtime-fetch-exec` at its manifest line;
  - the same for an inline MCP server and a skills directory declared only on the later entry;
  - a hook in the source's own hook file is counted once however many entries read the source.
- [x] 2.2 `PluginComponents.entriesByRoot` and the JSON cache (`@Requirements GW_VETTING_0047`); the vetter reads through them, with the entry bound and duplicate skipping.
- [x] 2.3 A manifest past `MAX_PLUGINS` entries fails closed in the vetter (`PluginRootBoundTests`, SVC_GW_VETTING_0066); `entriesByRoot` pinned per entry key (`PluginComponentsTests`, SVC_GW_VETTING_0080).
- [x] 2.4 `SurfaceComparison` left unchanged; the reason recorded in design.md.

## 3. Documentation

- [x] 3.1 Check `docs/manual/` for the vetter's description of where hooks are read from; update if it says "the entry". Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
