# Configuration

Every setting the gateway reads, with its default and what consumes it.

## Summary

| Block | Purpose | Required in production |
| --- | --- | --- |
| [`skills-gateway.*`](#skills-gateway) | Storage location, the URL-scheme allowlist, and the development auth escape hatch. | No — all defaulted. |
| [`skills-gateway.webhooks.*`](#webhooks) | Outbound lifecycle-webhook dispatch: poll interval, retry budget and backoff. | No — all defaulted. |
| [`skills-gateway.audit-export.*`](#audit-export) | Ledger export: the commit-settling lag, batch and page sizes. | No — all defaulted. |
| [`skills-gateway.retention.*`](#retention) | Snapshot retention policies and the schedules that apply them. **Off by default.** | No — all defaulted. |
| [`skills-gateway.sync.*`](#upstream-sync) | Upstream sync: the polling sweep's schedule and batch, and the inbound webhook body bound. | No — all defaulted. |
| [`spring.datasource.*`](#datasource) | PostgreSQL connection. Supplied entirely by environment. | **Yes** |
| [`spring.security.oauth2.client.*`](#oidc-login) | OIDC login for the web surface. | **Yes** |
| [`management.endpoints.*`](#actuator) | Which actuator endpoints are exposed. | No |
| [`scalar.*`](#api-documentation) | The bundled API reference UI. | No |

There are **no validation annotations** anywhere in the properties record. Every
field is nullable and its default is applied in the constructor, which means
omitting a whole block and omitting individual keys behave identically.

---

## `skills-gateway`

The application's own namespace. Only `data-dir` appears in
`application.yaml`; the rest are Java-side defaults.

```yaml
skills-gateway:
  # Root of git storage. Two subdirectories are created beneath it:
  #   {data-dir}/quarantine/{marketplace}.git   — never served
  #   {data-dir}/published/{marketplace}.git    — what the facade reads
  # The container image sets SKILLSGATEWAY_DATADIR=/data instead.
  data-dir: data

  # URL-scheme allowlist for every operator-supplied outbound URL.
  # Compared lower-cased. A URL that fails to parse, or carries no scheme,
  # is rejected — the check fails closed. Rejection is HTTP 400.
  # An empty list is not configurable: it falls back to the default.
  allowed-url-schemes:
    - http
    - https

  # DEVELOPMENT ONLY. Makes the entire web surface unauthenticated and
  # injects a synthetic principal "dev". Logs a warning at startup.
  # Does not affect the git facade, which always requires a PAT.
  dev-insecure-auth: false
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.data-dir` | path | `data` | Relative to the working directory. `/data` in the container image. |
| `skills-gateway.allowed-url-schemes` | list of string | `[http, https]` | See [Compatibility and allowlists](compatibility.md). |
| `skills-gateway.dev-insecure-auth` | boolean | `false` | Must stay `false` outside a development loop. |

!!! danger "`dev-insecure-auth` in a deployed environment"

    It permits **all** of `/api/**`, `/actuator/**` and `/docs` without
    authentication, and attributes every audit entry to `dev`. There is no
    partial mode.

---

## Webhooks

Tuning for the outbound dispatcher that delivers snapshot lifecycle events. None
of it appears in `application.yaml`; every value below is a Java-side default,
and omitting the whole block is identical to omitting each key.

```yaml
skills-gateway:
  webhooks:
    # Stops the polling dispatcher only. Events are still enqueued as delivery
    # rows and the admin API keeps working, so turning this back on drains
    # whatever accumulated instead of losing it.
    enabled: true

    # How often the dispatcher looks for due deliveries. Also the floor on
    # end-to-end latency for the first attempt.
    poll-interval: 5s

    # Attempt n schedules the next attempt at base-backoff * 2^(n-1),
    # never later than max-backoff.
    base-backoff: 10s
    max-backoff: 1h

    # Total attempts per delivery, first one included. Reaching it marks the
    # delivery 'failed'; it is never retried again.
    max-attempts: 5

    # Connect and read timeout for the outbound POST. The claim lease is
    # derived from it, so a slow receiver cannot strand a delivery.
    timeout: 10s

    # Deliveries claimed per poll pass.
    batch-size: 50
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.webhooks.enabled` | boolean | `true` | `false` pauses delivery; it does not stop emission. |
| `skills-gateway.webhooks.poll-interval` | duration | `5s` | Fixed delay between dispatch passes. |
| `skills-gateway.webhooks.base-backoff` | duration | `10s` | First retry delay; doubles per attempt. |
| `skills-gateway.webhooks.max-backoff` | duration | `1h` | Ceiling on the doubling. |
| `skills-gateway.webhooks.max-attempts` | integer | `5` | Attempt budget per delivery. |
| `skills-gateway.webhooks.timeout` | duration | `10s` | Connect and read timeout per attempt. |
| `skills-gateway.webhooks.batch-size` | integer | `50` | Deliveries claimed per pass. |

!!! warning "Raising the retry budget raises the retry window"

    The delays compound: with the defaults a delivery is abandoned about two
    minutes after the event. `max-attempts: 12` with the same base reaches the
    one-hour cap and keeps a dead receiver in the queue for hours. Receivers
    must de-duplicate on the delivery id regardless — see
    [Receiving lifecycle webhooks](../guides/lifecycle-webhooks.md).

---

## Audit export

Tuning for the ledger export — the NDJSON pull endpoint and the scheduled
exporter that feeds push sinks. Java-side defaults again; nothing appears in
`application.yaml`.

```yaml
skills-gateway:
  audit-export:
    # Stops the exporter poller only. The pull endpoint keeps serving and sinks
    # keep their cursors, so turning this back on resumes where it left off.
    enabled: true

    # How often each enabled sink is offered the next batch.
    poll-interval: 30s

    # Commit-settling window. The ledger id is a BIGSERIAL assigned before
    # commit, so a lower id can become visible after a higher one; both export
    # paths withhold entries younger than this so a cursor cannot step over an
    # append that was still in flight. The cost is staleness, not correctness.
    lag: 5s

    # Ledger entries per pushed batch, unless the sink overrides it.
    batch-size: 500

    # GET /api/audit/export page size when ?limit= is omitted, and the ceiling
    # it is clamped to when it is given.
    default-page-size: 1000
    max-page-size: 10000
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.audit-export.enabled` | boolean | `true` | `false` pauses push export; the pull endpoint is unaffected. |
| `skills-gateway.audit-export.poll-interval` | duration | `30s` | Fixed delay between export passes. |
| `skills-gateway.audit-export.lag` | duration | `5s` | Entries younger than this are withheld from both paths. |
| `skills-gateway.audit-export.batch-size` | integer | `500` | Per-sink default; a sink may set its own, clamped to `max-page-size`. |
| `skills-gateway.audit-export.default-page-size` | integer | `1000` | Applied when `?limit=` is absent. |
| `skills-gateway.audit-export.max-page-size` | integer | `10000` | Hard ceiling on `?limit=` and on a sink's batch size. |

!!! warning "`lag: 0` reintroduces the skip window"

    Setting the lag to zero lets a cursor advance past an entry that had an id
    but was not yet committed — that entry is then never exported, and the gap
    is silent. Shorten it only with evidence about your commit latency; a
    compliance feed with a hole in it is worse than one that is seconds behind.

Sinks, cursors and replay are described in
[Exporting the audit ledger](../guides/exporting-the-audit-ledger.md).

---

## Vetting

The connector chain that runs at ingestion. Java-side defaults; nothing appears
in `application.yaml`.

```yaml
skills-gateway:
  vetting:
    # How long a single connector may take before its verdict is recorded as an
    # error — which blocks the snapshot. A wedged connector must never wedge
    # ingestion, and a connector that never answers must never look like a pass.
    timeout: 30s

    # Files larger than this are handed to connectors unread. They are reported
    # as an informational 'file-not-scanned' finding, never skipped in silence.
    max-file-bytes: 1048576

    # How often lapsed waivers are noted in the audit ledger. This cannot open a
    # hole: a waiver stops suppressing its finding the moment the effective
    # outcome is next computed, whether or not this sweep has run.
    waiver-sweep-interval: 1h
    waiver-sweep-batch-size: 200
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.vetting.timeout` | duration | `30s` | Per connector, per run. Exceeding it is an `ERROR` verdict, which blocks. |
| `skills-gateway.vetting.max-file-bytes` | integer | `1048576` | Zero or negative falls back to the default. |
| `skills-gateway.vetting.waiver-sweep-interval` | duration | `1h` | How often `waiver-expired` ledger entries are written. Has no effect on the gate. |
| `skills-gateway.vetting.waiver-sweep-batch-size` | integer | `200` | Lapsed waivers recorded per pass. |

!!! note "There is no switch that turns vetting off"

    Deliberately. A snapshot with no chain run is blocked either way, so a kill
    switch would buy an estate of blocked snapshots with no findings to explain
    them. To get past a connector that is wrong about a snapshot, waive the
    findings it raised — scoped, justified and expiring, and on the record.

!!! warning "A shortened timeout silently converts slow connectors into blockers"

    Lowering `timeout` does not make vetting faster; it makes slow connectors
    fail. A connector that times out is recorded as `ERROR` and blocks the
    snapshot, so every affected approval then needs a `connector-error` waiver —
    which is a reviewer writing down that the scanner never looked.

The chain, the verdict states, the aggregation rule, waivers, and the honest
limits of the built-in scanners are described in
[Vetting — the connector chain](../concepts/vetting.md).

---

## Continuous re-vetting

Re-running the chain over content that is **already approved and served**, and
what a fresh violation on it does. Java-side defaults; nothing appears in
`application.yaml`.

```yaml
skills-gateway:
  vetting:
    revet:
      # Whether the scheduled sweep runs. ON by default: it only ever produces
      # evidence — a new chain run over pinned content — and never retracts
      # anything on its own. The on-demand endpoints work regardless.
      enabled: true

      # WARN (default): a violation is recorded and announced in full, and the
      # snapshot stays approved and published.
      # ENFORCE: a violation revokes the snapshot and removes its published refs.
      mode: warn

      # How often the sweep runs.
      interval: 6h

      # A snapshot is re-vetted only when its newest run is older than this. With
      # batch-size, this is what stops a tick re-vetting the whole estate: the
      # sweep takes the oldest-vetted snapshots first and covers the rest in
      # rotation over later ticks.
      cadence: 24h
      batch-size: 25
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.vetting.revet.enabled` | boolean | `true` | Runs the scheduled sweep. `POST /api/snapshots/{id}/revet` and `POST /api/marketplaces/{name}/revet` are unaffected. |
| `skills-gateway.vetting.revet.mode` | `warn` \| `enforce` | `warn` | What a violation does. `warn` never unpublishes anything. |
| `skills-gateway.vetting.revet.interval` | duration | `6h` | Sweep schedule. |
| `skills-gateway.vetting.revet.cadence` | duration | `24h` | Minimum age of a snapshot's newest run before the sweep picks it again. |
| `skills-gateway.vetting.revet.batch-size` | integer | `25` | Approved snapshots re-vetted per pass. |

!!! warning "`enforce` unpublishes content teams are already using"

    Under `enforce`, a re-vetting violation revokes the snapshot and removes
    the refs the facade serves it through — with no person in the loop. The
    next `git fetch` by every consuming team fails. Run `warn` for at least one
    full sweep cycle first and read the `revet-violation` ledger entries: each
    one names the identities that had already fetched the snapshot, which is
    the blast radius `enforce` would have caused.

!!! note "A broken connector never revokes anything"

    A run that blocks only because a connector errored, timed out, or has not
    answered is recorded as **inconclusive**, and leaves the snapshot approved
    and served in either mode. Retraction needs a connector that objects to the
    *content*. This does not loosen the approval gate: an inconclusive run still
    blocks approving, re-approving or publishing that snapshot.

The sweep, the revoked state, and the re-approval path are described in
[Re-vetting approved content](../guides/re-vetting.md).

---

## Retention

Snapshot retention: which snapshots the gateway may delete, and when the two
passes run. Java-side defaults; nothing appears in `application.yaml`.

```yaml
skills-gateway:
  retention:
    # OFF by default. Retention only ever deletes because an operator asked for
    # it; an upgrade never starts deleting on its own. The on-demand endpoints
    # work regardless, so a policy can be inspected before this is flipped.
    enabled: false

    # Evaluation marks (soft delete); compaction removes (hard delete). They are
    # separate schedules because the restore window only means anything if the
    # two are decoupled in time.
    poll-interval: 1h
    compaction-interval: 6h

    # Snapshots considered per marketplace per pass.
    batch-size: 200

    # The policy every marketplace inherits.
    defaults:
      # A snapshot still 'held' this long after ingestion is eligible.
      # Zero or negative disables the criterion rather than selecting everything.
      held-max-age: 90d

      # Whether a held/rejected snapshot overtaken by a later approved snapshot
      # of the same marketplace is eligible.
      superseded: true
      superseded-min-age: 30d

      # Veto, not a selector: a candidate whose SHA was fetched through the
      # facade within this window is dropped from the pass.
      min-idle: 30d

      # How long a soft-deleted snapshot stays restorable before compaction may
      # remove it. Resolved at deletion time, not at compaction time.
      restore-window: 14d

    # Per-marketplace overrides; unset fields fall back to defaults.
    marketplaces:
      acme:
        held-max-age: 30d
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.retention.enabled` | boolean | `false` | Runs the scheduled passes. The on-demand endpoints are unaffected. |
| `skills-gateway.retention.poll-interval` | duration | `1h` | Evaluation (soft delete) interval. |
| `skills-gateway.retention.compaction-interval` | duration | `6h` | Compaction (hard delete) interval. |
| `skills-gateway.retention.batch-size` | integer | `200` | Snapshots per marketplace per pass. |
| `skills-gateway.retention.defaults.held-max-age` | duration | `90d` | Zero or negative disables the criterion. |
| `skills-gateway.retention.defaults.superseded` | boolean | `true` | Enables the supersession criterion. |
| `skills-gateway.retention.defaults.superseded-min-age` | duration | `30d` | Minimum age of a superseded snapshot. |
| `skills-gateway.retention.defaults.min-idle` | duration | `30d` | Fetch-recency veto window. |
| `skills-gateway.retention.defaults.restore-window` | duration | `14d` | Restore window applied at deletion time. |
| `skills-gateway.retention.marketplaces.<name>.*` | policy | *(empty)* | Same keys as `defaults`; unset fields inherit. |

!!! warning "Enabling retention starts deleting on the next pass"

    Preview first: `GET /api/retention/candidates` shows exactly what the
    policies in force would select, and writes nothing. Approved snapshots are
    never eligible, but a generous `held-max-age` can still clear a large
    review backlog on the first pass.

!!! danger "Compaction cannot be undone"

    A soft-deleted snapshot is restorable only until its `purge_after`. After
    compaction the row and the unreachable git objects are gone, and only the
    ledger entry remains. Size `restore-window` so a mistake is noticed inside
    it.

Criteria, guards and the two passes are described in
[Snapshot retention](retention.md).

---

## Upstream sync

How automated ingestion behaves for marketplaces whose sync mode is
`scheduled` or `webhook`. Java-side defaults; nothing appears in
`application.yaml`.

```yaml
skills-gateway:
  sync:
    # Whether the scheduled polling sweep runs. ON by default, and still safe
    # on upgrade: the sweep only touches marketplaces an operator has
    # explicitly moved to the `scheduled` sync mode, so an estate of defaults
    # (all on-demand) sees no change. The inbound webhook endpoint and the
    # sync-mode endpoint work regardless.
    enabled: true

    # How often the sweep runs, and how many scheduled marketplaces one pass
    # ingests — least recently attempted first, so a large estate is covered
    # in rotation rather than all at once.
    poll-interval: 10m
    batch-size: 10

    # Inbound webhook bodies larger than this are rejected with 413 before the
    # HMAC is computed, bounding the work an unauthenticated caller can cause.
    max-webhook-body-bytes: 1048576
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.sync.enabled` | boolean | `true` | Runs the scheduled sweep. Touches only `scheduled`-mode marketplaces. |
| `skills-gateway.sync.poll-interval` | duration | `10m` | Sweep schedule. |
| `skills-gateway.sync.batch-size` | integer | `10` | Scheduled marketplaces ingested per pass, oldest attempt first. |
| `skills-gateway.sync.max-webhook-body-bytes` | long | `1048576` | Inbound webhook body bound; larger requests get 413 unverified. |

Modes, the webhook secret lifecycle, and the outage guarantee are described in
[Syncing from upstream automatically](../guides/upstream-sync.md).

---

## Datasource

Not present in `application.yaml` at all — supplied purely by environment.
PostgreSQL is assumed.

```console
$ export SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/skillsgateway
$ export SPRING_DATASOURCE_USERNAME=skillsgateway
$ export SPRING_DATASOURCE_PASSWORD=skillsgateway
```

The Helm chart exposes these as `postgresql.host` (**required**, no default),
`postgresql.port` (`5432`), `postgresql.database` and `postgresql.username`
(both `skillsgateway`), and `postgresql.existingSecret` (**required** — a Secret
with a `password` key).

!!! info "Flyway is deliberately unconfigured"

    There are no `spring.flyway.*` settings. The schema is built from a single
    consolidated migration on startup, because every environment — including
    Testcontainers and the e2e compose stack — builds it from scratch.

---

## OIDC login

The registration id is `idp` and must exist at AOT build time, because
native-image evaluates auto-configuration conditions then. That is why the
defaults are the placeholders below rather than being absent.

| Property | Environment variable | Placeholder default |
| --- | --- | --- |
| `…client.registration.idp.client-id` | `SGW_OIDC_CLIENT_ID` | `change-me` |
| `…client.registration.idp.client-secret` | `SGW_OIDC_CLIENT_SECRET` | `change-me` |
| `…client.provider.idp.authorization-uri` | `SGW_OIDC_AUTHORIZATION_URI` | `https://idp.invalid/authorize` |
| `…client.provider.idp.token-uri` | `SGW_OIDC_TOKEN_URI` | `https://idp.invalid/token` |
| `…client.provider.idp.jwk-set-uri` | `SGW_OIDC_JWK_SET_URI` | `https://idp.invalid/jwks` |

Fixed, not intended for override: scope `openid`, grant type
`authorization_code`, redirect URI `{baseUrl}/login/oauth2/code/idp`.

Register that redirect URI with your identity provider.

---

## Actuator

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,sbom
```

Only two endpoints are exposed. `/actuator/health` is the **only**
unauthenticated path in the application; `/actuator/sbom` serves the CycloneDX
SBOM and requires a session.

!!! warning "Probe the bare health path"

    The security chain permits exactly `/actuator/health`. Subpaths such as
    `/actuator/health/liveness` redirect to the identity provider, which is why
    both Kubernetes probes point at the bare path.

---

## API documentation

```yaml
scalar:
  enabled: true
  path: /docs        # the Scalar UI
  url: /v3/api-docs  # the OpenAPI document it renders
  theme: purple
```

Both paths sit behind the OIDC login like the rest of the web surface.

---

## Server and storage notes

**Port.** Never set; the Spring default `8080` applies. Compose publishes
`8080:8080` and the Helm chart hardcodes `containerPort: 8080`, so changing
`server.port` means editing the chart, not just values.

**Container storage.** The image sets `SKILLSGATEWAY_DATADIR=/data` — the
relaxed-binding environment form of `skills-gateway.data-dir`.

!!! warning "The Helm volume default is not durable"

    `/data` is mounted as an `emptyDir` unless `persistence.existingClaim` is
    set. The database and upstream can rebuild almost everything, but approved
    published refs live only on that volume.
