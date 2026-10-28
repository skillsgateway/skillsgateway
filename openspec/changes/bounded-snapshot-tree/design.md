# Design: bounded-snapshot-tree

## Decisions

### D1 — Count entries, subtrees included

A recursive walk with a cap on files never stops on a tree of repeated empty
directories, which yields no file. Entering subtrees by hand and counting
every entry bounds any shape at the bound, as `ExternalSourceResolver.measure`
already does for resolved sources.

### D2 — Fixed bounds

The bounds are 200,000 entries, about ten times the per-source object default,
and 32 levels of depth, the per-source depth default. These are constants, not
settings: no real marketplace approaches them, and a setting would move
`ConfigSurfaceBudgetTests`.

### D3 — One walk for both checks

The non-regular-entry check and the bound share one pass, so ingest walks the
tree once.

## Risks / Trade-offs

- [A very large legitimate marketplace is rejected] → The violation names the
  bound. The bound sits well above anything a plugin marketplace holds.
