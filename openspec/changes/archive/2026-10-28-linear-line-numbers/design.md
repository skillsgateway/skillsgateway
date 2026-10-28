# Design: linear-line-numbers

## Decisions

### D1 — An index, not a cursor

Matches from several rules come out of order relative to one another, so a
cursor advanced between ascending offsets would need sorting. One index per
file answers any offset in O(log n), and costs one pass to build.

### D2 — Same imports, found differently

The keyword-first pattern required `import` or `export`, then no `;` or quote,
then `from '…'`. Looking back from each `from '…'` to the previous `;` or
quote, bounded by the previous match's end, and checking that span for the
keyword, accepts exactly the same text. The existing import-following tests
pass unchanged.

### Not in this change

The record's other suggestions (capping findings per rule, caching the content
inventory per snapshot) change output or add state. The time-limit half is
already covered by `GW_VETTING_0062 — A built-in vetter that outruns its time
limit stops working`.
