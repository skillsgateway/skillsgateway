# Tasks: bounded-snapshot-tree

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0072 and SVC_GW_INGEST_0072 in `docs/reqstool/`.

## 2. Bounded walk (SVC_GW_INGEST_0072)

- [x] 2.1 Three cases:
  - `TreeBoundTests`: a ten-way, eight-level fan-out upstream is rejected,
    naming the entry bound, within 30 s;
  - `TreeBoundIndexTests`: the same fan-out over empty directories is refused
    by the index within 10 s;
  - `TreeBoundIndexTests`: a 40-deep tree is refused, naming the depth.

  Watch the two fan-out cases fail first.
- [x] 2.2 `BoundedTree`, the ingest check and `QuarantineSnapshot.index` on it
  (`@Requirements GW_INGEST_0072`), with the ingestion, non-regular-entry,
  hosted, closure and vetting suites unchanged.

## 3. Documentation

- [x] 3.1 `guides/registering-a-marketplace.md`. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
