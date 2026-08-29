# Configuration

Every setting the gateway reads, with its default and what consumes it.

## Summary

| Block | Purpose | Required in production |
| --- | --- | --- |
| [`skills-gateway.*`](#skills-gateway) | Storage location, the URL-scheme allowlist, and the development auth escape hatch. | No — all defaulted. |
| [`skills-gateway.webhooks.*`](#webhooks) | Outbound lifecycle-webhook dispatch: poll interval, retry budget and backoff. | No — all defaulted. |
| [`skills-gateway.audit-export.*`](#audit-export) | Ledger export: the commit-settling lag, batch and page sizes. | No — all defaulted. |
| [`skills-gateway.retention.*`](#retention) | Snapshot retention policies and the schedules that apply them. **Off by default.** | No — all defaulted. |
| [`skills-gateway.approval.*`](#separation-of-duties-four-eyes) | Separation of duties on approval: whether a reviewer may publish content they themselves supplied. **Records by default; enforcement is opt-in.** | No — all defaulted. |
| [`skills-gateway.sync.*`](#upstream-sync) | Upstream sync: the polling sweep's schedule and batch, and the inbound webhook body bound. | No — all defaulted. |
| [`skills-gateway.catalog.*`](#virtual-catalog) | The global virtual catalog and its reserved name. | No — all defaulted. |
| [`skills-gateway.tokens.*`](#access-tokens) | Access-token policy: the maximum lifetime creation accepts. | No — defaulted (unlimited). |
| [`skills-gateway.roles.*`](#delegated-administration) | Role enforcement and the bootstrap admins. **Off by default.** | No — all defaulted. |
| [`skills-gateway.estate.*`](#declarative-estate) | The declared estate: marketplaces, role grants, webhook subscribers, audit sinks — reconciled at startup and on demand. **Empty by default.** | No — empty by default. |
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
  # Root of local storage. On the filesystem backend three subdirectories are
  # created beneath it:
  #   {data-dir}/quarantine/{marketplace}.git   — never served
  #   {data-dir}/published/{marketplace}.git    — what the facade reads
  #   {data-dir}/hosted/{marketplace}.git       — a hosted marketplace's origin
  # On the object-store backend the repositories are in the bucket and this
  # holds only the local pack cache, which is never authoritative.
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

!!! note "The gateway refuses to start where the flag cannot belong"

    The escape hatch exists for a development loop that has no identity
    provider to log in to. So a gateway with `dev-insecure-auth: true` **and an
    identity provider configured** refuses to start, naming what it decided on
    and both ways out. It counts a provider as configured when any of these is
    true:

    - an OIDC client registration carries a client id other than the shipped
      `change-me` placeholder;
    - a provider endpoint (`authorization-uri`, `token-uri`, `jwk-set-uri`,
      `issuer-uri`) names a host other than the shipped `idp.invalid`
      placeholder;
    - `skills-gateway.oidc.issuer` is pinned.

    There is no property that switches the guard off — the way out is to stop
    setting `dev-insecure-auth`. Note what the guard cannot see: a deployment
    with no identity provider at all is indistinguishable from a laptop, so it
    is not a substitute for keeping the flag out of your deployed configuration.

---

## Git storage

Which storage holds the repositories, and how to reach it. The backend is
**named, never inferred**: an absent block is the filesystem, an unrecognised
name fails startup, and an `object-store` selection missing what it needs fails
startup naming the missing setting. There is no fallback in either direction —
a gateway serving from local disk while the operator believes it is serving from
a bucket reports healthy and is wrong from the outside.

For choosing between the two, and for moving an estate from one to the other,
see [Choosing and migrating the storage backend](../guides/storage-backends.md).

```yaml
skills-gateway:
  storage:
    # filesystem (default) | object-store
    backend: filesystem

    object-store:
      bucket: skills-gateway
      region: eu-north-1
      # Empty for the regional AWS endpoint; set it for an S3-compatible store
      # or for an S3 VPC endpoint.
      endpoint: ""
      # Key prefix, so one bucket can hold more than one gateway.
      prefix: ""

      credentials:
        # default | web-identity | static
        mode: web-identity
        # web-identity: both fall back to AWS_ROLE_ARN and
        # AWS_WEB_IDENTITY_TOKEN_FILE, which is what an annotated service
        # account projects into the pod.
        role-arn: ""
        token-file: ""
        # static only, for stores with no role mechanism.
        access-key-id: ""
        secret-access-key: ""

      cache:
        # Local pack cache; nothing in it is authoritative and deleting it at
        # any moment is safe. Defaults to {data-dir}/object-store-cache.
        dir: ""
        max-bytes: 2147483648
        block-size-bytes: 65536
        block-cache-bytes: 268435456
        # How long a replica may keep serving a reference map it has not
        # re-read. This is the upper bound on how long a revoked snapshot can
        # still be advertised by a replica that did not do the revoking.
        ref-freshness: 10s
        # How long a pack nothing references is kept before its objects are
        # deleted, so a fetch already streaming from it is not cut off.
        pack-grace: 1h

      # Below the store's own idle timeout: a connection the store has already
      # closed reads, on the first request after a quiet period, as a storage
      # fault rather than as the pooling artefact it is.
      connection-max-idle-time: 20s
      connection-time-to-live: 1m

    # The offline one-shot copy between backends. Off unless asked for.
    migration:
      enabled: false
      # The destination; the source is whatever `backend` above names.
      to: object-store
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.storage.backend` | `filesystem` \| `object-store` | `filesystem` | An unrecognised value fails startup listing the accepted ones. |
| `skills-gateway.storage.object-store.bucket` | string | — | Required on `object-store`. |
| `skills-gateway.storage.object-store.region` | string | — | Required on `object-store`; an unsigned-for region fails mid-approval rather than at startup. |
| `skills-gateway.storage.object-store.endpoint` | URL | SDK regional | An S3-compatible store, or an S3 VPC endpoint. |
| `skills-gateway.storage.object-store.prefix` | string | `""` | Key prefix inside the bucket. |
| `skills-gateway.storage.object-store.credentials.mode` | `default` \| `web-identity` \| `static` | `default` | See below. |
| `skills-gateway.storage.object-store.credentials.role-arn` | string | `AWS_ROLE_ARN` | `web-identity` only. |
| `skills-gateway.storage.object-store.credentials.token-file` | path | `AWS_WEB_IDENTITY_TOKEN_FILE` | `web-identity` only. |
| `skills-gateway.storage.object-store.credentials.access-key-id` | string | — | `static` only. |
| `skills-gateway.storage.object-store.credentials.secret-access-key` | string | — | `static` only. Never logged, audited or echoed by any API. |
| `skills-gateway.storage.object-store.cache.dir` | path | `{data-dir}/object-store-cache` | Safe to delete at any time. |
| `skills-gateway.storage.object-store.cache.max-bytes` | bytes | `2 GiB` | Bound on the on-disk pack cache. |
| `skills-gateway.storage.object-store.cache.block-size-bytes` | bytes | `64 KiB` | JGit `DfsBlockCache` block size. |
| `skills-gateway.storage.object-store.cache.block-cache-bytes` | bytes | `256 MiB` | JGit `DfsBlockCache` size. |
| `skills-gateway.storage.object-store.cache.ref-freshness` | duration | `10s` | Upper bound on cross-replica revocation latency. |
| `skills-gateway.storage.object-store.cache.pack-grace` | duration | `1h` | Grace before an unreferenced pack's objects are deleted. |
| `skills-gateway.storage.object-store.connection-max-idle-time` | duration | `20s` | Keep below the store's idle timeout. |
| `skills-gateway.storage.object-store.connection-time-to-live` | duration | `1m` | Upper bound on a pooled connection's life. |
| `skills-gateway.storage.migration.enabled` | boolean | `false` | Makes this start a migration instead of a service. |
| `skills-gateway.storage.migration.to` | `filesystem` \| `object-store` | — | Required when enabled; must differ from `backend`. |

### Credential modes

| Mode | Where credentials come from | Use it for |
| --- | --- | --- |
| `default` | The AWS SDK's own provider chain | A host where the chain already resolves, and where an instance metadata service exists |
| `web-identity` | A projected service-account token exchanged for a role | Workload identity (IRSA and its equivalents). The gateway holds no secret at all, and it is the only mode that works where there is no instance metadata service |
| `static` | An access key pair | Stores with no role mechanism |

!!! warning "Write access to the bucket is publication"

    On the `object-store` backend the served reference map is an object in the
    bucket. Anyone who can write it can put content on the wire without going
    through approval. Give the gateway a narrow policy — object read, write and
    delete under its own prefix, and no bucket administration — and treat the
    bucket as part of the trust boundary the volume already was. See
    [Trust boundaries](../concepts/trust-boundaries.md).

!!! note "Conditional writes are the portability boundary"

    The backend serializes every reference transition with a conditional write
    (`If-Match` / `If-None-Match`) on one small object. A store that does not
    implement those cannot be supported by weakening the model, so the gateway
    probes the configured bucket at startup and refuses to run where the probe
    fails. The stores this has actually been exercised against are listed in
    [the storage guide](../guides/storage-backends.md#which-object-stores-work).

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

    # The cooling-off window: how long a commit must have been in quarantine
    # before it can be approved. 0 — the default — is no window at all.
    minimum-release-age: 0s

    # The organisation-level license policy, evaluated by the built-in
    # license-scan connector. Both lists default to empty, under which
    # identified licenses are informational and an unknown or missing license
    # only warns — an upgrade blocks nothing.
    license:
      # SPDX ids. Once non-empty, any license not on the list — and any
      # unknown or missing license — is a blocking finding.
      allowed: [MIT, Apache-2.0, BSD-3-Clause]
      # SPDX ids whose detection is a blocking finding. Checked before the
      # allow list: a license on both is reported as banned.
      banned: [AGPL-3.0]
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.vetting.timeout` | duration | `30s` | Per connector, per run. Exceeding it is an `ERROR` verdict, which blocks. |
| `skills-gateway.vetting.max-file-bytes` | integer | `1048576` | Zero or negative falls back to the default. |
| `skills-gateway.vetting.waiver-sweep-interval` | duration | `1h` | How often `waiver-expired` ledger entries are written. Has no effect on the gate. |
| `skills-gateway.vetting.waiver-sweep-batch-size` | integer | `200` | Lapsed waivers recorded per pass. |
| `skills-gateway.vetting.minimum-release-age` | duration | `0s` | How long the gateway must have held a commit before it may be approved. `0` disables the gate. |
| `skills-gateway.vetting.license.allowed` | string list | empty | SPDX ids, case-insensitive. Empty means no allow list is enforced. |
| `skills-gateway.vetting.license.banned` | string list | empty | SPDX ids, case-insensitive. Evaluated before the allow list. |

### Minimum release age

The gate refuses `POST /api/snapshots/{id}/approve` with `409` while the
snapshot is younger than this, and the problem document names the setting, the
snapshot's current age and the time remaining. Nothing has to run for the wait
to end: the age is compared at the instant of each approval request, exactly as
waiver expiry is, so a snapshot becomes approvable on its own.

!!! warning "The clock is the gateway's first sighting, not the commit's date"

    The name mirrors Renovate's, but what is measured is **ingestion age**: the
    instant *this gateway* first ingested that commit, recorded when the
    snapshot row was created. The commit's own author and committer dates are
    never read — they are written by whoever made the commit, so a control that
    trusted them could be defeated by backdating one.

    Re-ingesting the same commit does not restart the clock: ingestion
    recognises the SHA and keeps the existing snapshot, so a re-push cannot
    reset the window either.

The rejection path is not gated — suspicious content can always be refused at
once — and neither is anything about serving already-approved content.

There is deliberately **no exemption and no per-approval override**, including
for the first snapshot of a newly registered marketplace: an exemption is a
special case an attacker can arrange to land in. Getting an urgent fix out
before the window elapses means changing this setting, which is a deployment
someone reviews.

!!! note "The license policy is configuration on purpose"

    Vetting policy must be attributable per chain run: the `license-scan`
    connector stamps a digest of these lists into its recorded version, so a
    changed answer about unchanged content can be traced to the policy change
    that caused it. That is why the lists change by deploy, not by API — see
    [License compliance for skills](../guides/license-compliance.md). After
    changing them, trigger a re-vet to turn the new policy into fresh evidence.

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

## Separation of duties (four-eyes)

Whether the identity that supplied a snapshot may also be the one that approves
it. Java-side defaults; nothing appears in `application.yaml`.

```yaml
skills-gateway:
  approval:
    four-eyes:
      # WARN (default): a conflict is recorded on the audit ledger and the
      # approval proceeds.
      # ENFORCE: the approval is refused, and the snapshot stays held.
      mode: warn
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.approval.four-eyes.mode` | `warn` \| `enforce` | `warn` | What a detected conflict does. `warn` never refuses an approval; `enforce` refuses it and publishes nothing. |

A reviewer conflicts with a snapshot when they are any of:

| Conflict | Meaning |
| --- | --- |
| `registered-by` | They registered the marketplace the snapshot came from. |
| `ingested-by` | They triggered the ingestion that pinned it. |
| `waiver-author` | They wrote a waiver this approval relies on — the response names which one. |

The two automated sync triggers, `scheduler` and `webhook`, are recorded as the
ingestion actor but are never conflicts: a scheduled poll is nobody's judgement
about the content. Neither is an unrecorded actor, which is what marketplaces
and snapshots that predate this release carry.

!!! note "There is no way to switch detection off"

    `warn` is the floor, not a disabled state. Whatever the mode, every
    detected conflict is appended to the audit ledger as a `four-eyes-conflict`
    entry naming the acting identity, the snapshot, the mode, whether the
    approval proceeded, and each conflicting act. That is what makes
    `warn` measurable rather than merely permissive — and what lets an operator
    size up `enforce` from evidence before turning it on.

!!! warning "`enforce` needs at least two principals who can approve"

    Under `enforce` a marketplace whose only approver also registered it, or
    ingests its content, has no one left who may publish it. Before switching,
    check that every marketplace that needs deciding has a second identity with
    approval rights — a second admin, or an approver scoped to that marketplace
    under [delegated administration](#delegated-administration). This is why the
    default is `warn`: a single-administrator deployment must keep working
    across an upgrade.

Identities are compared as exact strings, as the identity provider reports them
through the configured principal claim. The comparison assumes what is true of a
single provider — that one person is one principal string — and makes no attempt
to reconcile two spellings of the same human.

Refusals are visible to reviewers before they act: the portal's approve dialog
says which acts conflict, and `GET /api/snapshots/{id}/four-eyes` answers the
same question for the calling identity. Rejecting a snapshot is never gated —
refusing content quickly must not need a second pair of eyes.

The rule as part of the approval boundary is described in
[Approving snapshots](../guides/approving-snapshots.md).

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

## Virtual catalog

The synthesized one-URL catalog of the whole served estate. Java-side defaults;
nothing appears in `application.yaml`.

```yaml
skills-gateway:
  catalog:
    # Whether approvals and revocations rebuild the catalog and the /api/catalog
    # endpoints answer. Turning this off never deletes an existing catalog repo.
    enabled: true

    # The catalog's facade path (/git/{name}) and its RESERVED name: a
    # marketplace cannot be registered under it.
    name: catalog
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.catalog.enabled` | boolean | `true` | Gates rebuild triggers and the API endpoints. |
| `skills-gateway.catalog.name` | string | `catalog` | Facade path segment; reserved at registration. |

Composition, freshness, and provenance are described in
[The virtual catalog](../guides/virtual-catalog.md).

---

## Access tokens

Token policy (GW_0065). Java-side default; nothing appears in
`application.yaml`.

```yaml
skills-gateway:
  tokens:
    # The longest lifetime creation accepts. When set, a request beyond it —
    # including one with no expiry at all — is refused with 422, never
    # silently shortened. Unset (the default) accepts tokens that never
    # expire, which is what every pre-cap deployment had.
    max-ttl: 90d
    # What a session-derived credential is GRANTED (GW_0104), as opposed to
    # what a holder may ask for. Not derived from max-ttl on purpose: a
    # deployment may allow year-long CI tokens and still want a credential
    # minted from a browser session to die at the end of the working day.
    session-ttl: 8h
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.tokens.max-ttl` | duration | unset (unlimited for personal access tokens; **90 days** for machine API credentials) | Cap on accepted token lifetime; refusal, not clamping. |
| `skills-gateway.tokens.session-ttl` | duration | `8h` | Lifetime granted to a session-derived credential. The caller cannot influence it. |

!!! warning "A machine API credential is always capped, even with `max-ttl` unset"

    A [machine API credential](api/tokens.md#machine-api-credentials) must state
    an expiry, but "mandatory expiry" alone admits `now + 100 years` whenever no
    cap is configured — which is the never-expiring credential in a CI variable,
    spelled differently. Machine credentials are therefore held to a built-in
    cap of **90 days** when `max-ttl` is unset.

    Ninety days is a quarter: short enough that a forgotten credential expires
    within one planning cycle rather than outliving the service it was minted
    for, and long enough that rotating it is a scheduled chore rather than an
    interruption. A configured `max-ttl`, longer or shorter, always wins — and
    that is the point. A long-lived control-plane credential should be a stated
    choice, not the consequence of leaving a property blank.

    The cap does not change personal access tokens, whose behaviour under an
    unset `max-ttl` is exactly what it was.

Scopes, expiry, and rotation are described in
[Access tokens](api/tokens.md); session-derived credentials in
[Consuming skills](../guides/consuming-skills.md).

---

## Delegated administration

Role enforcement for the web surface (GW_0068, GW_0071). Java-side defaults;
nothing appears in `application.yaml`.

```yaml
skills-gateway:
  roles:
    # Off by default: every authorization check passes and an upgrade never
    # locks anyone out. Once true, enforcement is deny-by-default: every
    # mutation and the ledger surface need a role.
    enabled: true
    # Admins by configuration: effective without a grant row and unrevocable
    # through the API — the escape hatch that survives a bad grant edit.
    admins:
      - admin@example.com
    # Roles from the identity provider's own claims (GW_0098). The claim name
    # and every value are yours: on a shared app registration the values are
    # the organisation's group ids or app-role values, not gateway role names.
    claim: groups
    mappings:
      - claim-value: 8f1c0a2e-0000-0000-0000-000000000000
        role: admin
      - claim-value: gateway-approvers-acme
        role: approver
        marketplace: acme
      - claim-value: security-auditors
        role: auditor
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.roles.enabled` | boolean | `false` | With `false` every check passes; grants stay writable as staging data. |
| `skills-gateway.roles.admins` | list of strings | `[]` | Principals that are admins by configuration; DB grants add to them. |
| `skills-gateway.roles.claim` | string | `groups` | Claim carrying membership. A dotted path walks nested claims (`realm_access.roles`). |
| `skills-gateway.roles.mappings` | list | `[]` | Claim value → role. `approver` names a marketplace; `admin` and `auditor` must not. |

Claim values are matched **exactly**, after trimming surrounding whitespace: no
prefix, glob or case-insensitive matching, because a looser match can only ever
widen who is privileged. The claim may be a list of strings or a single string;
a delimited string is one value and is never split. A malformed mapping — an
unknown role, a blank value, an unscoped `approver`, a scoped global role —
refuses startup rather than silently granting nothing. A mapping may name a
marketplace that is not registered yet; until it is, the mapping matches
nothing.

Claims are read only from a browser session established through the identity
provider. A personal access token on the git facade, the `dev-insecure-auth`
principal and the anonymous webhook request carry no claims and derive no role,
whatever authorities they hold.

The roles, the enforcement matrix, and the staging workflow are described in
[Delegated administration](../guides/delegated-administration.md); the mapping
walkthrough in [Identity providers](../guides/identity-providers.md); the
grants API in [Roles](api/roles.md).

---

## Declarative estate

The estate defined as configuration (GW_0083–GW_0087, GW_0089): marketplaces,
role grants, webhook subscribers, audit export sinks and policy deny rules,
reconciled at startup —
after schema migration, before the web surface serves — and on demand via
[`POST /api/estate/reconcile`](api/estate.md). Empty by default; an empty
declaration reconciles nothing.

Reconciliation is **additive and idempotent**. A declared object is created if
missing and converged if drifted; an object absent from the declaration is
**never** deleted, deregistered or revoked — removing a line from this block
retracts nothing. A converged estate reconciles with zero writes and zero
ledger entries; every applied change lands on the append-only ledger under the
same event name as its API equivalent, attributed to the actor
`config-reconciler`. An entry that fails validation is skipped and reported —
in the log at ERROR, on the ledger as `estate-reconciliation-failed`, and in
the [reconciliation report](api/estate.md) — and never prevents startup or the
other entries.

```yaml
skills-gateway:
  estate:
    # Registered through the exact same gate as POST /api/marketplaces:
    # name rules, the reserved catalog name, the URL scheme allowlist.
    # There is no ref key — the ingested ref is always the gateway's
    # decision (the upstream default branch).
    marketplaces:
      - name: corp-marketplace
        url: https://github.com/acme/skills-marketplace.git
        # on-demand or scheduled; applied through the same audited path as
        # PUT /api/marketplaces/{name}/sync. Omit to leave the stored mode
        # alone. webhook mode is refused: its inbound HMAC secret is
        # generated and shown once, which has no declarative form.
        sync-mode: scheduled
      # A gateway-hosted marketplace declares no url and is published to by
      # pushing (GW_0101). Its sync mode is fixed at on-demand: the push is
      # its ingestion trigger. push-policy defaults to append-only.
      - name: platform-skills
        origin: hosted
        push-policy: append-only

    # The exact shape of POST /api/roles: approver grants name one
    # marketplace that must exist at reconcile time (declared above, or
    # registered through the API); admin and auditor grants must not.
    grants:
      - principal: alice@example.com
        role: approver
        marketplace: corp-marketplace
      - principal: audit@example.com
        role: auditor

    # Webhook subscribers with an OPERATOR-SUPPLIED signing secret — the
    # inversion of the API's generated show-once secret. Reference an
    # environment variable; never inline a literal in a committed file.
    webhooks:
      - name: ci-bot
        url: https://ci.example.com/hooks/skills-gateway
        events: snapshot.approved,snapshot.rejected   # omit for all events
        secret: ${SGW_ESTATE_CI_BOT_SECRET}

    # Audit export sinks; same secret contract as webhooks.
    audit-sinks:
      - name: siem
        url: https://siem.example.com/ingest/skills-gateway
        secret: ${SGW_ESTATE_SIEM_SECRET}
        after: 0          # cursor seed — applied at creation ONLY
        batch-size: 500

    # CEL policy deny rules, through the same compiled, audited path as
    # POST /api/policy/rules: an expression that does not compile to a
    # boolean is an isolated entry failure, never a stored rule.
    policy-rules:
      - name: no-shell-tools
        description: deny skills declaring shell tools
        expression: 'skills.exists(s, s.tools.exists(t, t.startsWith("Bash")))'
        # enabled defaults to true: a declared rule is declared to enforce
```

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.estate.marketplaces` | list | `[]` | Each entry: `name`, `url`, optional `sync-mode` (`on-demand`/`scheduled`). |
| `skills-gateway.estate.marketplaces[].name` | string | — | Same rules as the API: `^[a-z0-9][a-z0-9_-]*$`, catalog name reserved. |
| `skills-gateway.estate.marketplaces[].url` | string | — | Scheme must be allowlisted. Immutable once registered: a differing declared URL is a reconciliation failure, never an update. |
| `skills-gateway.estate.marketplaces[].sync-mode` | string | unset (not managed) | `on-demand` or `scheduled`; `webhook` is refused. Unset never touches the stored mode. |
| `skills-gateway.estate.grants` | list | `[]` | Each entry: `principal`, `role` (`admin`/`approver`/`auditor`), `marketplace` (required for approver, forbidden otherwise). |
| `skills-gateway.estate.webhooks` | list | `[]` | Each entry: `name`, `url`, optional `events`, `secret`. |
| `skills-gateway.estate.webhooks[].secret` | string | — | Operator-supplied, minimum 16 characters, write-only. A changed value rotates the stored secret idempotently. |
| `skills-gateway.estate.audit-sinks` | list | `[]` | Each entry: `name`, `url`, `secret`, optional `after`, optional `batch-size`. |
| `skills-gateway.estate.audit-sinks[].after` | long | `0` | Seeds the ledger cursor **at creation only**; never re-applied to an existing sink. |
| `skills-gateway.estate.audit-sinks[].batch-size` | int | audit-export default | Converged when set; unset never touches the stored value. |
| `skills-gateway.estate.policy-rules` | list | `[]` | Each entry: `name`, optional `description`, `expression`, optional `enabled`. See [Policy deny rules](../guides/policy-rules.md). |
| `skills-gateway.estate.policy-rules[].name` | string | — | Same rules as the API: `^[a-z0-9][a-z0-9_-]*$`, at most 100 characters. |
| `skills-gateway.estate.policy-rules[].expression` | string | — | CEL over the policy variables; compiled to a boolean at reconcile time — a non-compiling expression is an isolated entry failure. |
| `skills-gateway.estate.policy-rules[].enabled` | boolean | `true` | Enabled rules gate every approval fail-closed; description, expression and this flag are converged on drift. |

!!! warning "Declared secrets are sensitive property values"

    The gateway treats a declared secret as write-only — it never appears in
    the log, the ledger, the reconciliation report, or any API response — but
    it cannot control where the *configuration* lives. Supply secrets by
    environment-variable reference (`${VAR}`) from a secret store; a literal in
    a committed values file is a leaked credential. A blank or shorter-than-16
    secret is refused as a reconciliation failure.

Personal access tokens are deliberately not declarable: they are user-owned
credentials, API-only by design. The GitOps workflow is described in
[Declarative estate configuration](../guides/declarative-estate.md); the
report and trigger endpoints in [Estate](api/estate.md).

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
| `…client.provider.idp.user-name-attribute` | `SGW_OIDC_USER_NAME_ATTRIBUTE` | `sub` |
| `…client.registration.idp.scope` | `SGW_OIDC_SCOPE` | `openid` |

`user-name-attribute` decides what the principal is called everywhere else —
grants, `roles.admins`, and every ledger row. On an app registration shared
between services, `sub` is an opaque per-application identifier, so set this to
a readable claim such as `preferred_username` there. Widen `SGW_OIDC_SCOPE`
when your provider needs a scope before it will emit group or role claims.

Fixed, not intended for override: grant type `authorization_code`, redirect URI
`{baseUrl}/login/oauth2/code/idp`. Register that redirect URI with your
identity provider.

Moving any of these off its placeholder is what tells the gateway an identity
provider exists — and a gateway with one configured refuses to start with
`skills-gateway.dev-insecure-auth` on. See
[`skills-gateway`](#skills-gateway) above.

### Expected issuer

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `skills-gateway.oidc.issuer` | string | _(unset)_ | ID-token issuer to require. Unset means the issuer is not compared at all. |

The gateway configures its provider endpoints explicitly rather than by issuer
discovery, and Spring Security compares an ID token's `iss` only when the
registration carries an issuer — so with this unset, nothing checks it. That
matters most where one authorization endpoint serves many tenants: every
tenant's tokens verify against the same signing keys, so the issuer is the only
thing that says which organisation the person logging in belongs to. The
gateway logs a warning at startup while it is unset.

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

**Volume durability is a decision the chart makes you state.** There is no
default: `persistence.mode` must be `existingClaim` (a PersistentVolumeClaim
that already exists), `ephemeral` (an `emptyDir`, and everything on it is lost
on restart), or `none` (no volume at all, accepted only on the `object-store`
backend). Anything else — including leaving it empty — stops the render with a
message that spells out the consequence. See
[Choosing and migrating the storage backend](../guides/storage-backends.md).

**What the chart refuses.** Alongside the durability choice it will not render
an `object-store` selection with no bucket or no region, a static credential
mode with no secret to take the keys from, a `web-identity` mode with no
annotation on the service account, `persistence.mode: none` on the filesystem
backend, more than one replica on the filesystem backend, or more than one
replica while any of the background pollers is still enabled. Each refusal
names the value it was decided by.
