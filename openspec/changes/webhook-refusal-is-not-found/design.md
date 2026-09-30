# Design: webhook-refusal-is-not-found

## Context

See proposal.md for why. The webhook controller looks the marketplace up, then
checks the signature header, then reads the body within its bound, then
verifies the HMAC. Each refusal has its own status. The other items are
independent defects; each is a few lines in one class.

## Goals / Non-Goals

**Goals:**

- One observable answer for every refusal of `/hooks/{marketplace}` that is
  not about the body size.
- Each defect fixed where it is, with a test where one is cheap and meaningful.

**Non-Goals:**

- Response timing. An unknown name skips the HMAC, so a caller measuring
  latency can still tell some cases apart. Closing that would mean computing a
  dummy HMAC on every miss; the gain is small next to the status oracle and the
  cost is a code path with no functional purpose. Recorded as a known limit.
- `servedTip` per marketplace in the marketplace list: it reads git, not the
  database, and the issue names only the snapshot query.

## Decisions

- **404, not 403, for all refusals.** GW_AUTH_0006 — Marketplace-scoped access
  tokens — settled "refusal is indistinguishable from not found" for the
  facade, and the docs already say 404 for the unknown case, so 404 changes the
  fewest words. Body `not found` in every case.
- **The body bound runs first.** Keeping `413` while leaving it after the lookup
  would leave an oracle: an oversized body answers `413` for a webhook
  marketplace and `404` for anything else. Reading the bounded body before the
  lookup makes `413` a statement about the body only. The work an
  unauthenticated caller can cause is unchanged: at most the bound plus one
  byte is read, as before.
- **Orphan pin: keep the order, undo on failure.** The pin is written before the
  row so that no row ever exists without its pin (GW_INGEST_0018 — the checked
  pin). Reordering would open exactly that window. Instead a failed insert
  (other than the duplicate-key race, whose winner's row names the pin)
  deletes the pin, but only after confirming no row names the commit; if that
  check itself fails, the pin is kept. An orphan pin costs storage; a row
  without its pin is a correctness failure, so every doubt resolves toward
  keeping the pin. Cleanup failures are suppressed onto the insert's exception.
- **Vetting catch narrowed to content access.** `IOException` and
  `UncheckedIOException` (what opening and reading a quarantine repository
  raise, across both storage backends) keep mapping to the `snapshot-access`
  error verdict. Anything else — a database failure recording a verdict —
  propagates. The run row then keeps the blocked outcome it was created with,
  which is the honest record; before, the handler would try to write a
  verdict through the same failed database anyway.
- **Revet per snapshot.** `revetMarketplace` catches per snapshot and logs, as
  `sweep` does. `PassResult` is unchanged: no new field for failed snapshots.
- **Marketplace list: one `IN` query.** `SnapshotRepository` gains a batched read
  grouped by marketplace, ordered by id as the per-marketplace read was. The
  response is byte-for-byte the same shape.
- **Forge metadata bound: a constant, not a leaf.** 1 MiB; a repository metadata
  document is a few kilobytes. A new configuration leaf would raise the
  `ConfigSurfaceBudgetTests` ratchet for no operator decision.
- **Zero-or-less falls back to the default**, like every sibling guard in
  `SkillsGatewayProperties` (they substitute the default rather than refuse to
  start).
- **Diff summary cache.** `Collections.synchronizedMap` over an access-ordered
  `LinkedHashMap` of 256 entries, keyed by marketplace name, sha, baseline sha
  and the narrowed path (the summary is per path). The pair of commits is
  immutable, so an entry is never stale. On a hit only the page's entries are
  classified and formatted. No Caffeine: it is not on the classpath.

## Risks / Trade-offs

- [A forge operator with a wrong secret sees `404` instead of `403`] → the docs
  say `404` covers a signature that did not verify.
- [Orphan-pin cleanup races a second gateway instance ingesting the same
  commit] → cleanup runs only when no row names the commit, and checks again
  after deleting: a row that appeared in between gets its pin back. What is
  left is the moment between that delete and the rewrite, which needs two
  instances, the same commit, and this insert failing for a non-duplicate
  reason at that instant. Accepted and recorded.
- [OpenAPI breaking-change gate] → removing a documented `403` may trip it; the
  PR title then carries `!` under the pre-1.0 rule.

## Migration Plan

None. No schema change, no configuration change.
