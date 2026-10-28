# Tasks: bounded-diff

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0073 and SVC_GW_INGEST_0073 in `docs/reqstool/`.

## 2. Bounded diff (SVC_GW_INGEST_0073)

- [x] 2.1 `PreviewTests`: a 64,000-line random two-value file pair diffs within
  5 s. Watch it time out first.
- [x] 2.2 `boundedDiff()` in `SnapshotPreviewService` (`@Requirements GW_INGEST_0073`),
  with the preview, browsing and content-diff suites unchanged.

## 3. Documentation

- [x] 3.1 `guides/approving-snapshots.md`. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
