# Tasks: frontmatter-hook-budget

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0071 and SVC_GW_INGEST_0071 in `docs/reqstool/`.

## 2. Budget (SVC_GW_INGEST_0071)

- [x] 2.1 `PluginComponentsTests`: a skill whose frontmatter hooks alias three
  per level over twelve levels yields no hook and a "too large" problem within
  2 s. Watch it fail first.
- [x] 2.2 `withinBudget` and the check in `frontmatterHooks`
  (`@Requirements GW_INGEST_0071`), with the plugin-components, vetter and
  frontmatter suites unchanged.

## 3. Documentation

- [x] 3.1 The `hook-config-unreadable` row in `concepts/vetting.md`. Verify with
  `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
