# Design: frontmatter-hook-budget

## Decisions

### D1 — Count the expansion, do not just lower the alias cap

Lowering `MAX_ALIASES` bounds alias events, but the cost is the product of the
aliasing at each level, so any non-zero cap still leaves a document whose tree
is exponential in it. Counting values as the tree would visit them bounds the
real cost directly. The count stops at the budget, so it is never the slow part.

### D2 — A fixed bound of 10,000

A real hooks block holds tens of values. A fixed constant needs no
configuration leaf, so `ConfigSurfaceBudgetTests` does not move.

## Risks / Trade-offs

- [The finding is medium, so it warns rather than blocks] → It is the existing
  severity for an unreadable hook configuration. Whether unreadable executable
  configuration should block is the separate fail-closed question.
