# Design: bounded-inventory

## Decisions

### D1 — Bound the output by listing each root once, not by refusing repeats

Refusing two plugins over one directory at ingest was tried and dropped. Test
fixtures, and plausibly real marketplaces, name several plugins from one
directory. Listing each root's skills under only its first plugin bounds the
inventory by the tree's own skill files whatever the manifest says, and it
also covers snapshots ingested earlier.

### D2 — 1,000 plugins

This is the same bound the executable-surface vetter reads
(`GW_VETTING_0064 — The executable-surface vetter's work is bounded in plugin
roots and stops when cancelled`): a fixed constant, not a setting.
