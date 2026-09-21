# Design: operations-readiness

## Context

See `proposal.md — Why`. What mattered while doing it:

- `replicaGate` already refuses `replicaCount > 1` on the filesystem backend, so the
  single-writer model was *asserted* and then broken by the deployment strategy the chart
  did not set. The gate and the strategy were saying different things about the same
  invariant.
- `SecurityConfig` permitted the exact path `/actuator/health` and nothing else, with a
  comment in the chart explaining that probe subpaths "would be redirected to the IdP" —
  the reason the aggregate was used for both probes in the first place. The comment was
  accurate; it was the conclusion drawn from it that was wrong.
- `GitStorageHealthIndicator` lists the bucket on every poll on the object-store backend,
  so the aggregate is not a cheap check either.

## Decisions

### 1. `Recreate` only where the backend needs it

Conditional on `storage.backend != "object-store"`. The object store serializes reference
transitions by conditional write, so rolling is safe there and is what a multi-replica
deployment wants. Making it unconditional would have taken away the one deployment shape
that supports a zero-downtime upgrade.

### 2. Liveness names no dependency at all

Not "fewer dependencies" — none. The question liveness answers is whether this process is
still itself, and the only honest response to an unreachable database is to keep running
and report not-ready. A restart cannot fix somebody else's outage, and restarting during
one destroys whatever the pod was draining.

Readiness takes `db` and `gitStorage` because that is the recoverable lever: out of the
endpoints, back in when the dependency returns, nothing lost.

### 3. The probe paths are enumerated, not wildcarded

`"/actuator/health"`, `"/actuator/health/liveness"`, `"/actuator/health/readiness"` — not
`/actuator/health/**`.

A wildcard would exempt every future subpath from authentication by default, and the
actuator's health tree is exactly where component detail shows up. Three literals is the
version where adding an endpoint that exposes more is a decision somebody has to make on
purpose.

### 4. The grace period is a value, and it is 60 seconds

Long enough for a normal `upload-pack` to finish, short enough that a wedged pod does not
hold a rollout for minutes. It is a value rather than a constant because the right number
depends on repository size, and the page says what to do when clients report truncated
fetches: raise it.

### 5. The operations page verifies the restore, not just the copy

The failure mode this page exists for is a **split** restore, and the property of a split
restore is that everything appears to work. So the procedure ends with a clone of a
marketplace the portal lists as published — the one check that distinguishes "restored"
from "restored the database and half the content", because it asks the two halves to
agree about the same snapshot.

### 6. OTLP only, and a render failure when misconfigured

No Prometheus registry. A second export mechanism is a second surface to document,
secure and keep working, and OTLP reaches every collector that matters. If pull-based
scraping is wanted it should arrive with its own argument.

`otel.enabled: true` with an empty `endpoint` **fails the render**. The alternative is a
deployment that starts, looks configured, and fails an export on every interval — the
shape of misconfiguration that survives longest because nothing is obviously broken.

## Risks / Trade-offs

- **`Recreate` means downtime on the filesystem backend during an upgrade** → that
  backend is already single-replica by the gate, so it had no zero-downtime story; what
  it had was an overlap that corrupted repositories. Downtime is the honest version.
- **Readiness including `gitStorage` makes an object-store blip take pods out of
  service** → correct: a gateway that cannot read its reference map cannot serve. The
  probe's period and failure threshold are the tuning surface, not the group's contents.
- **Health-group configuration is invisible until an outage** → hence the packaging test,
  which reads the files rather than a running pod. It cannot prove kubelet behaviour, and
  says so; it can prove the configuration is not silently absent, which is what went
  wrong.
- **The backup procedure is manual** → nothing here automates it, and pretending
  otherwise would be worse. Quiescing is the honest trade for a copy an operator can
  restore without reasoning about interleaving.

## Migration Plan

Nothing to migrate. An existing deployment picks up the drain and the probe split by
upgrading; the two new chart values default to the previous behaviour except where the
previous behaviour was the defect (`Recreate` on the filesystem backend, which replaces
an overlap nobody wanted).

Operators with their own manifests rather than the chart need to change the two probe
paths themselves. The observability reference and the new guide both name them.
