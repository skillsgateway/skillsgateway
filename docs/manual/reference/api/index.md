# REST API

The gateway exposes three HTTP surfaces with different authentication:

| Surface | Paths | Authentication |
| --- | --- | --- |
| Web / API | everything except `/git/**` | OIDC session cookie |
| Machine API | `/api/**` with an `Authorization: Bearer` header | [Machine API credential](tokens.md#machine-api-credentials) |
| Git facade | `/git/**` | Personal access token over HTTP Basic |

The three do not overlap. A personal access token reaches the facade and
nothing else; a machine API credential reaches the API and nothing else — it
cannot clone a marketplace, including the marketplaces an empty fetch scope
would otherwise grant. Which surface answers a request is decided by what it
carries, not by what it asks for.

This section documents the first. For the second see
[Git smart-HTTP facade](../git-facade.md).

!!! tip "A live reference ships with the gateway"

    `/docs` renders the OpenAPI document at `/v3/api-docs`. Both are behind the
    OIDC login. These pages describe the same surface with the surrounding
    reasoning.

## Conventions

**Authentication.** Every `/api/**` endpoint is reached by exactly one of two
paths:

- an **authenticated OIDC session**, which is what the portal uses; or
- a **machine API credential** presented as `Authorization: Bearer <secret>`,
  which is what infrastructure-as-code and CI use.

A request carrying no bearer header takes the session path, exactly as it always
has. A request carrying one takes the machine path and is authenticated
strictly — including when `skills-gateway.dev-insecure-auth=true`, which opens
the browser surface and never the bearer path.

!!! warning "A machine request must carry no cookie"

    The machine path refuses a request that presents both a bearer credential
    and a `Cookie` header, rather than resolving it to either. Ambiguity about
    which credential authorised a request is where confused-deputy defects live.

    The practical consequence: a client behind a load balancer that injects its
    own session-affinity cookie (`AWSALB`, `GCLB`-style) is refused with a bare
    **401**. Strip the cookie, or exclude the gateway's hostname from affinity.

**What a machine credential can reach** is an allowlist, described in
[Machine API credentials](tokens.md#machine-api-credentials). Every act of human
judgement — approving, rejecting, waiving — every operation that retracts or
republishes content, every role grant and the whole of `/api/tokens/**` is
outside it, and no combination of scopes and no role reaches them.

**Authorization.** Every mutation and the audit surface require a role and
answer **403** without one; the browsing surface stays open. There is no
configuration that relaxes this.
Each endpoint's page states its requirement, and
[Delegated administration](../../guides/delegated-administration.md) has the
matrix. Access tokens are scoped to the calling principal server-side in either
mode.

**Unauthenticated requests** to `/api/**` receive a clean **401**, not a 302 to
the identity provider, so an expired session surfaces as an error rather than an
HTML login page rendered into a `fetch()`.

**CSRF** is enabled for the web surface but disabled for `/api/**`, which is
consumed by the portal with a session cookie. The machine path earns its own
exemption the way the git facade does and does not borrow the session path's: it
is stateless, creates no session, honours no cookie and refuses a request that
carries one, so every request there authenticates itself.

**Compatibility.** Within a major, this surface only grows; a breaking change
moves the path prefix and ships as a major. See
[The API contract](../compatibility.md#the-api-contract) for what counts as
breaking and how it is enforced.

**Errors** are RFC 7807 `ProblemDetail` documents:

```json
{"type":"about:blank","title":"Bad Request","status":400,
 "detail":"url scheme must be one of [http, https]"}
```

**Status codes** used across the API:

| Status | Meaning |
| --- | --- |
| 400 | The request violated a trust-boundary rule — a disallowed URL scheme, or a non-default ref. |
| 403 | Role enforcement is enabled and the session lacks the role the endpoint requires; or a machine credential reached an endpoint its scopes do not cover, or one no scope covers. |
| 404 | No such marketplace, snapshot or token. |
| 409 | A state conflict — a duplicate name, a decision on a snapshot that is already `approved` or `rejected`, or a re-vet of one that is not `approved`. |
| 422 | A name failed `^[a-z0-9][a-z0-9_-]*$`. |
| 502 | Ingestion failed against the upstream. |

## Endpoint index

| Area | Endpoints |
| --- | --- |
| [Marketplaces and snapshots](marketplaces.md) | Register, list, ingest, inspect contents, licenses, vetting, waivers, re-vet, fetchers, approve, reject, provenance |
| [Access tokens](tokens.md) | Create, list, revoke and rotate personal access tokens; provision and administer machine API credentials |
| [Audit](audit.md) | Read the ledger; stream it as NDJSON; register, replay and delete export sinks |
| [Adoption](adoption.md) | Windowed adoption report per marketplace and SHA; identities not on the served tip |
| [Webhooks](../../guides/lifecycle-webhooks.md) | Register, list and delete subscribers; list delivery attempts |
| [Retention](../retention.md#endpoints) | Preview candidates, evaluate, compact, soft-delete and restore snapshots |
| [Roles](roles.md) | List, grant and revoke delegated-administration roles |
| [Estate](estate.md) | Read the last declarative-estate reconciliation report; trigger a reconcile |
| [Policy](policy.md) | Create, list, update and delete CEL deny rules; test expressions in the playground |

## Session

`GET /api/me` returns the current principal, whether role enforcement is
enabled, the session's effective roles with the **source** of each, and whether
the identity provider truncated the membership claim. The portal uses it for
the sidebar footer and, with roles, to adapt its controls.

```json
{"username": "alice@example.com",
 "roles": [{"role": "approver", "marketplace": "acme", "source": "grant"},
           {"role": "auditor", "marketplace": null, "source": "claim"}],
 "claimsTruncated": false}
```

| `source` | Where the role came from |
| --- | --- |
| `config` | `skills-gateway.roles.admins`. No grant row; unrevocable through the API. |
| `grant` | A row in [the grants API](roles.md). |
| `claim` | An identity-provider claim value mapped by `skills-gateway.roles.mappings`. |

The same role and marketplace is reported once, attributed to the most durable
source that produced it. `claimsTruncated: true` means the provider dropped the
membership claim rather than the session having none — the roles listed are
then incomplete; see [Identity providers](../../guides/identity-providers.md).

## Non-API endpoints

| Path | Notes |
| --- | --- |
| `/actuator/health` | The only unauthenticated path. Use the bare path for probes. |
| `/actuator/sbom` | CycloneDX SBOM. Authenticated. |
| `/v3/api-docs`, `/docs` | OpenAPI document and Scalar UI. Authenticated. |
| `/oauth2/authorization/idp`, `/login/oauth2/code/idp` | OIDC login and callback. |
| `/`, `/marketplaces`, `/marketplaces/{name}`, `/audit`, `/adoption`, `/tokens`, `/webhooks` | Forwarded to the single-page application. |
