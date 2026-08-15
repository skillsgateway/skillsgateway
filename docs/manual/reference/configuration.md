# Configuration

Every setting the gateway reads, with its default and what consumes it.

## Summary

| Block | Purpose | Required in production |
| --- | --- | --- |
| [`skills-gateway.*`](#skills-gateway) | Storage location, the URL-scheme allowlist, and the development auth escape hatch. | No — all defaulted. |
| [`skills-gateway.webhooks.*`](#webhooks) | Outbound lifecycle-webhook dispatch: poll interval, retry budget and backoff. | No — all defaulted. |
| [`skills-gateway.audit-export.*`](#audit-export) | Ledger export: the commit-settling lag, batch and page sizes. | No — all defaulted. |
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
