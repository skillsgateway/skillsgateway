# Design: approver-scoping

## Decisions

### D1 — Live-name resolution, not id-keyed grants

Grants are by name by design: estate declarations and group mappings name
marketplaces. Resolving a resource to its name only when its marketplace is
the live holder closes the reverse direction without changing what a grant
is. Grant reads already join through the live row, which closed the forward
direction.

### D2 — Bind the fetchers query to the marketplace

`fetch_log.marketplace_id` is recorded on every facade entry, so the filter is
a predicate on an existing column. Re-vetting's affected list shares the
method, so it is bound too.
