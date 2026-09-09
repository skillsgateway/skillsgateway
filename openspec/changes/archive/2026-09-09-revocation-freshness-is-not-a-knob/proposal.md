# Proposal: revocation-freshness-is-not-a-knob

## Why

`skills-gateway.storage.object-store.cache.ref-freshness` set how long a replica
could keep serving a reference map it had already read. On the revocation path
that is the window in which a snapshot the gateway has unpublished is still
advertised by a replica that did not perform the revocation.

Its own javadoc said it: *"on the revocation path a trust-boundary property and
not a tuning knob"*. It was settable anyway.

Three things make removing it the right call rather than a preference:

- **The default is `Duration.ZERO`** — every advertisement already re-checks the
  manifest. Removing the knob changes nothing for any deployment that did not set
  it.
- **No requirement mandates it configurable**, and `GW_FACADE_0011 — Reference
  transitions are the same on every storage backend` says nothing about a
  settable bound.
- **No test varies it.** Every call site in the suite passes `Duration.ZERO`, so
  the non-zero branch of `ManifestStore.freshen()` was reachable only by
  configuration and exercised by nothing. An untested branch whose only effect is
  to keep revoked content on the wire for longer is not a feature.

**The documentation was also wrong about it, in three places.** The reference
table, the YAML sample and the storage guide all stated a `10s` default. The code
default was zero. So the docs promised an operator a 10-second revocation window
that never existed, and presented a trust-boundary property as an ordinary
tuning dial with a non-zero default — which is roughly the worst way to be wrong
about a control of this kind.

## What Changes

- `Cache.refFreshness` is gone. `ManifestStore` no longer holds a freshness
  duration or a last-checked timestamp; `freshen()` performs its conditional
  `GET` every time. That `GET` is `O(1)` and returns no body when nothing has
  changed, which is what makes doing it unconditionally affordable.
- `skills-gateway.storage.object-store.cache.ref-freshness` joins
  `RemovedProperties.ALL` as **`REFUSE`**. A deployment that set it was asking
  for a *longer* bound; ignoring it would shorten that bound silently — the right
  behaviour reached by the wrong route, with the operator still believing the
  value they wrote was in force. Same reasoning as
  `skills-gateway.roles.enabled`.
- The three stale documentation passages are corrected, and the reference gains a
  removal warning beside the existing one.
- `ConfigSurfaceBudgetTests`' ratchet drops 105 → 104.

`ObjectStoreBackendTests.aRevokedSnapshotStopsBeingServedByAnotherReplica…` is
renamed from `…WithinTheBound` to `…OnTheNextAdvertisement`. It already asserted
this exact property against `Duration.ZERO`; what changes is that the bound it
names is no longer a value somebody could configure.

## Impact

- **No behaviour change for any deployment that did not set the property.** For
  one that did, revocation gets *faster* and startup refuses until the line is
  removed.
- **One fewer configuration leaf.** No API, no schema, no requirement text.
- **A cost, stated plainly:** a replica now pays one conditional `GET` per
  reference advertisement with no way to amortise it. That was already the
  default, so this removes an escape hatch rather than adding a cost — but a
  deployment that had bought latency with revocation window can no longer make
  that trade, and that is the point.

## Out of scope

`pack-grace` next to it stays settable. It bounds how long an unreferenced pack
survives so an in-flight fetch is not cut off mid-stream; getting it wrong costs
a failed clone, not a revoked snapshot staying on the wire.
