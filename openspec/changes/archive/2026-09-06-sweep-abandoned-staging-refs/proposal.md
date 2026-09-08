# Proposal: sweep-abandoned-staging-refs

## Why

Publication is one seam operation: the snapshot's objects are copied into the
published repository under an unadvertised `refs/staging/<sha>`, and only then
do the served references move. That ordering is deliberate — a transfer that
completes and a transition that is then refused leaves nothing on the wire.

A process killed *between* the two leaves the staging reference behind. It
serves nothing: the facade advertises `refs/heads/main` and `refs/snapshots/*`
and nothing else. But it keeps its objects reachable, so garbage collection
reclaims nothing and the published repository carries the whole size of a
snapshot that will never be served, indefinitely and with nothing anywhere that
would ever revisit it.

Three of the four ways a staging reference could leak were closed in
[#149](https://github.com/skillsgateway/skillsgateway/issues/149) — a refused
transition, a re-publication of the same snapshot, and a revocation all clean up
after themselves. This is the fourth and last.

Issue [#207](https://github.com/skillsgateway/skillsgateway/issues/207).

## What Changes

- **Retention's compaction pass gains a sweep of the published side** —
  `GW_FACADE_0019 — Abandoned publication staging references are swept from published
  repositories`. For every published repository it lists `refs/staging/*`,
  removes each reference that no live snapshot record names *and* that has been
  under observation for longer than a bound, garbage-collects, and records what
  it removed on the ledger.
- **The sweep lives in `RetentionService`, not in the storage seam.** Deciding
  whether a staged commit is still anybody's snapshot takes the database, and
  the seam is deliberately ignorant of it.
- **A new `skills-gateway.retention.staging-ref-max-age`, default `24h`**, is
  the bound. It is the correctness argument of the whole change and not a
  tuning knob: a publication in flight has staged its objects and has not yet
  moved the served references, so a sweep acting on a database read alone could
  collect them out from under it. Zero or negative switches the sweep off rather
  than making everything eligible — the same fail-safe reading `held-max-age`
  gets.
- **A new `staging_ref_sightings` table** is the clock the bound needs. A git
  reference carries no creation time either backend can be asked for, so the
  first pass that sees a reference stamps it. The stamp under-estimates the
  reference's real age, which is the only direction it is safe to be wrong in.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `snapshot-retention`: compaction, which until now touched only quarantine,
  also removes abandoned publication staging references from published
  repositories and reclaims what they held.

## Impact

- **DB**: one new table, `staging_ref_sightings`, folded into `V1__init.sql`.
- **Backend**: `RetentionService` (the sweep; `collectGarbage` becomes
  role-aware), `RetentionScheduler` (documentation only — the sweep rides the
  existing compaction pass), `SkillsGatewayProperties.Retention`
  (+`stagingRefMaxAge`), new `StagingRefSightingRepository`.
- **API**: none. `POST /api/retention/compact` runs the sweep as part of the
  pass; its response shape is unchanged.
- **Trust boundary**: none crossed, and one guarded. The sweep only ever removes
  references under `refs/staging/`, which the facade does not advertise; it
  cannot reach `refs/heads/main` or `refs/snapshots/*`, and the approved-snapshot
  guard is untouched. The risk this change introduces is data loss on the
  publication path, which the age bound exists to close.
- **Docs** (same PR): `reference/retention.md`, `reference/configuration.md`,
  `guides/snapshot-retention.md`.
