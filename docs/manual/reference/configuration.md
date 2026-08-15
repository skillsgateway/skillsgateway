# Configuration

Every setting the gateway reads, with its default and what consumes it.

## Summary

| Block | Purpose | Required in production |
| --- | --- | --- |
| [`skills-gateway.*`](#skills-gateway) | Storage location, the URL-scheme allowlist, and the development auth escape hatch. | No — all defaulted. |
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
