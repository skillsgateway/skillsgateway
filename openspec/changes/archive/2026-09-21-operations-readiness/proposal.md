# Proposal: operations-readiness

## Why

PR F of the 2026-09-20 1.0.0 readiness run — findings **4**, **5** and **10**. Three
separate defects with one thing in common: each is invisible until a real deployment is
under stress, and 1.0.0 is the version people will deploy for real.

**The chart corrupted the filesystem backend on every upgrade.**
`deploying-without-kubernetes.md` says two overlapping processes on one volume corrupt
repositories, and `replicaGate` already refuses `replicaCount > 1` on that backend. The
chart set no `strategy`, so the default `RollingUpdate` ran the new pod while the old one
still held the volume — the two-writer case the gate exists to prevent, arranged by the
chart itself.

**A dependency outage restarted healthy pods.** Liveness *and* readiness both addressed
`/actuator/health`, which aggregates the DataSource and `GitStorageHealthIndicator`. A
PostgreSQL or S3 outage therefore failed *liveness*, so the orchestrator killed pods that
were working correctly and waiting for the dependency to return — lengthening the outage
it was meant to detect.

**A rollout severed in-flight transfers.** No `server.shutdown: graceful` and no
`terminationGracePeriodSeconds`, so SIGTERM cut whatever `upload-pack` was mid-transfer.

**There was no backup, restore or upgrade procedure.** The only `pg_dump` in the docs
site was in the *exit* guide. The hazard was stated precisely — "the volume and the
database are one estate" — and then nothing said how to copy both, restore the pair, or
what startup does to the schema.

**Metrics could not leave.** Export needs `arconia.otel.enabled` plus the `OTEL_*`
variables, and the chart had no values for any of them — so the two metrics the
observability reference says to **alert on** reached nobody on a default install.

## What Changes

### 1. The chart stops arranging the two-writer case

`strategy: {type: Recreate}` when `storage.backend != "object-store"`. On the object
store, where conditional writes serialize the transition, rolling stays.

### 2. Liveness and readiness become different questions

Health groups in `application.yaml`: liveness includes `livenessState` and **no
dependency** — a restart cannot fix somebody else's database. Readiness includes
`readinessState`, `db` and `gitStorage`, because a gateway that cannot reach its storage
should not be sent traffic, and removing a pod from the endpoints is recoverable where
killing it is not.

Both probe paths are permitted unauthenticated in `SecurityConfig`, **enumerated rather
than `/actuator/health/**`** so that a future health endpoint exposing component detail
is not exempted by having been added.

### 3. A rollout drains

`server.shutdown: graceful`, and `terminationGracePeriodSeconds` (60s) so the
orchestrator waits through the drain. A drain nobody waits for is not a drain.

### 4. An operations page

`guides/backup-and-upgrade.md`: quiesce, copy both halves *together*, restore the pair,
and a verification step that actually catches a split restore — clone a marketplace the
portal lists as published. Plus what startup does to the schema, and the pre-1.0 reality
that editing the single migration in place means an upgrade recreates the database.

### 5. Metrics can leave

`otel.enabled` / `endpoint` / `protocol` / `extraEnv` in the chart. Enabling without an
endpoint **fails the render**, because exporting to nowhere reports nothing and says
nothing.

## Capabilities

### Modified Capabilities

- `git-facade`: gains `GW_FACADE_0033 — A shutdown drains in-flight transfers rather than
  severing them` and `GW_FACADE_0034 — Liveness and readiness are distinct, and a
  dependency outage is not a restart`. Both are properties of the serving surface under
  operational stress, and neither had a requirement — which is how both stayed wrong.

The chart's deployment strategy, the operations page and the OTLP values change no
requirement: the first is the mechanical consequence of `GW_FACADE_0030`'s single-writer
model, and the other two are documentation and packaging of behaviour
`GW_OBSERVABILITY_0003` already specifies.

## Impact

- **Backend**: `application.yaml` (shutdown, health groups) and three permitted paths in
  `SecurityConfig`. No Java logic.
- **Chart**: strategy, grace period, two probe paths, the otel block. Two new values;
  the chart's values are explicitly outside the API contract's promise, per
  `compatibility.md`.
- **Docs**: a new operations guide, linked from both deploying guides; the observability
  reference gains the chart's half of export.
- **Configuration**: no `skills-gateway.*` leaf. `server.shutdown` and
  `management.endpoint.health.*` are Spring's, so `ConfigSurfaceBudgetTests` does not move.
- **Schema**: none.
- **Not done here, deliberately**: a Prometheus registry and a scrape endpoint. Adding
  one is a second export mechanism with its own surface, and OTLP already reaches every
  collector that matters once the chart can turn it on. If pull-based scraping is wanted,
  it deserves its own argument rather than arriving as part of an operations fix.
