# Tasks: bounded-inventory

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0074 and SVC_GW_INGEST_0074 in `docs/reqstool/`.

## 2. Bounded inventory (SVC_GW_INGEST_0074)

- [x] 2.1 Two test classes:
  - `ManifestPolicyTests`: 1,001 plugins are refused; one directory named
    two ways is refused; two distinct sources pass;
  - `InventoryBoundTests`: a 2,000-entry repeated-source manifest over 500
    skills lists at most 500 skills within 10 s.

  Watch all three fail first.
- [x] 2.2 The bounds in `ManifestPolicy`, and the single walk with per-root
  listing in `SnapshotContentService` (`@Requirements GW_INGEST_0074`), with the
  content, browsing and ingestion suites unchanged.

## 3. Documentation

- [x] 3.1 `guides/registering-a-marketplace.md`. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
