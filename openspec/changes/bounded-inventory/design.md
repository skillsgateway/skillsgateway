# Design: bounded-inventory

## Decisions

### D1 — Refuse a repeated source at ingest

De-duplicating only the output would leave an approvable snapshot whose
manifest gives one directory two plugin names, a shape no client needs and
one that confuses what is installed. Refusing it keeps the manifest one plugin
per directory, which is what bounds the inventory by the tree.

### D2 — List each root once at read time too

Snapshots already ingested, and rejected ones, are still listed. Listing a root
under only its first plugin bounds those too, without refusing the read.

### D3 — 1,000 plugins

This is the same bound the executable-surface vetter reads
(`GW_VETTING_0064 — The executable-surface vetter's work is bounded in plugin
roots and stops when cancelled`): a fixed constant, not a setting.
