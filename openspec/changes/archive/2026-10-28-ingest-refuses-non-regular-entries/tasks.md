# Tasks: ingest-refuses-non-regular-entries

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0070 and SVC_GW_INGEST_0070 in `docs/reqstool/`.

## 2. Reject non-regular entries (SVC_GW_INGEST_0070)

- [x] 2.1 `NonRegularEntryTests`: a symlinked hooks file and a submodule entry
  each land `rejected`, with the path and kind in the violation; an executable
  script is still `held`. Watch the two rejections fail first.
- [x] 2.2 `IngestionService.serve` checks the upstream tree and any composite
  tree (`@Requirements GW_INGEST_0070`). Make 2.1 green with the ingestion,
  hosted and catalog suites unchanged.

## 3. Documentation

- [x] 3.1 `guides/registering-a-marketplace.md` says links and submodules
  reject the snapshot. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
