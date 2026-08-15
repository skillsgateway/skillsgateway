# REST API

The gateway exposes two HTTP surfaces with different authentication:

| Surface | Paths | Authentication |
| --- | --- | --- |
| Web / API | everything except `/git/**` | OIDC session only |
| Git facade | `/git/**` | Personal access token only |

This section documents the first. For the second see
[Git smart-HTTP facade](../git-facade.md).

!!! tip "A live reference ships with the gateway"

    `/docs` renders the OpenAPI document at `/v3/api-docs`. Both are behind the
    OIDC login. These pages describe the same surface with the surrounding
    reasoning.

## Conventions

**Authentication.** Every `/api/**` endpoint requires an authenticated OIDC
session. There is no method-level authorization — authorization is chain-level,
so any authenticated session may call any endpoint. The single exception is
access tokens, which are scoped to the calling principal server-side.

**Unauthenticated requests** to `/api/**` receive a clean **401**, not a 302 to
the identity provider, so an expired session surfaces as an error rather than an
HTML login page rendered into a `fetch()`.

**CSRF** is enabled for the web surface but disabled for `/api/**`, which is
consumed by the portal with a session cookie.

**Errors** are RFC 7807 `ProblemDetail` documents:

```json
{"type":"about:blank","title":"Bad Request","status":400,
 "detail":"url scheme must be one of [http, https]"}
```

**Status codes** used across the API:

| Status | Meaning |
| --- | --- |
| 400 | The request violated a trust-boundary rule — a disallowed URL scheme, or a non-default ref. |
| 404 | No such marketplace, snapshot or token. |
| 409 | A state conflict — a duplicate name, or a decision on a snapshot that is not `held`. |
| 422 | A name failed `^[a-z0-9][a-z0-9_-]*$`. |
| 502 | Ingestion failed against the upstream. |

## Endpoint index

| Area | Endpoints |
| --- | --- |
| [Marketplaces and snapshots](marketplaces.md) | Register, list, ingest, inspect contents, approve, reject, provenance |
| [Access tokens](tokens.md) | Create, list, revoke |
| [Audit](audit.md) | Read the ledger |
| [Webhooks](../../guides/lifecycle-webhooks.md) | Register, list and delete subscribers; list delivery attempts |

## Session

`GET /api/me` returns the current principal — the portal uses it for the sidebar
footer.

```json
{"username": "alice@example.com"}
```

## Non-API endpoints

| Path | Notes |
| --- | --- |
| `/actuator/health` | The only unauthenticated path. Use the bare path for probes. |
| `/actuator/sbom` | CycloneDX SBOM. Authenticated. |
| `/v3/api-docs`, `/docs` | OpenAPI document and Scalar UI. Authenticated. |
| `/oauth2/authorization/idp`, `/login/oauth2/code/idp` | OIDC login and callback. |
| `/`, `/marketplaces`, `/marketplaces/{name}`, `/audit`, `/tokens`, `/webhooks` | Forwarded to the single-page application. |
