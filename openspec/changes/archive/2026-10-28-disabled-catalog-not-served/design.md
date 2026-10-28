# Design: disabled-catalog-not-served

## Decisions

### D1 — Refuse in the resolver, keep the repository

The resolver is where every served name is decided, so the flag is honoured
there. The repository is kept, as the flag has always promised.

### Not in this change

The record also suggests withdrawing the catalog's `main` at startup, so that a
re-enabled catalog does not serve a stale revision before its first rebuild.
That is left out: re-enabling is an operator action, and the next approval or
revocation, or `POST /api/v1/catalog/rebuild`, rebuilds it.

### D2 — A unit test, not a context

Testing with the catalog disabled through Spring would need a context of its
own, which `ContextBudgetTests` counts. The resolver is tested directly, with
mocked storage and properties.
