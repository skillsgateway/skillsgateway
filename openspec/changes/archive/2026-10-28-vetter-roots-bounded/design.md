# Design: vetter-roots-bounded

## Decisions

### D1 — A lookup on the SPI, with a default

`read(path)` defaults to the old walk, so a test snapshot or an external
implementation keeps working. `QuarantineSnapshot` overrides it with the map
it already builds.

### D2 — Fail closed past 1,000 roots, reading the first 1,000

Reading the first roots still reports what they hold. The finding is high
severity, because the rest were not examined. The bound is a constant, not a
setting.

### D3 — Interruption in the per-root loop

This is the loop whose iterations are the expensive unit. Checking between
roots bounds the work after a cancel to one root.
