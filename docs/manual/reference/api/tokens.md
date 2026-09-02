# Access tokens

Personal access tokens are the credential git clients use against the
[facade](../git-facade.md). They are the only part of the API scoped per user,
and they stay owner-scoped with
[role enforcement](../../guides/delegated-administration.md) enabled: managing
your own tokens never requires a role.

All paths are relative to `/api`.

## Token format and storage

`sgw_` followed by 32 random bytes from a `SecureRandom`, Base64url-encoded
without padding.

The gateway stores **only an unsalted SHA-256 hex digest**. This is deliberate:
these are high-entropy random tokens, not user-chosen passwords, so key
stretching would buy nothing. The cleartext is returned exactly once and cannot
be recovered afterwards.

---

## `POST /tokens`

Create a token for the calling principal.

**Body** — `{name, scopes?, expiresAt?, pushScopes?}`

```console
$ curl -X POST localhost:8080/api/tokens \
    -H 'Content-Type: application/json' \
    -d '{"name":"ci-runner","scopes":["acme","catalog"],"expiresAt":"2026-12-31T00:00:00Z"}'
```

```json
{"id":1,"name":"ci-runner","token":"sgw_...","createdAt":"2026-08-17T09:00:00Z",
 "scopes":["acme","catalog"],"expiresAt":"2026-12-31T00:00:00Z","rotatedFrom":null,
 "pushScopes":[]}
```

`scopes` (GW_0064) lists marketplace names the token may fetch — the
[virtual catalog](../../guides/virtual-catalog.md)'s name is a valid entry —
and every entry is validated against the registered marketplaces at creation.
Empty or omitted grants every marketplace, which is what every pre-scoping
token meant. An out-of-scope fetch answers exactly like a marketplace that
does not exist, so a scoped token is not a directory of what else the gateway
governs.

`pushScopes` (GW_0102) lists **hosted** marketplaces the token may publish to
through [`/publish/{name}`](../../guides/publishing-first-party-skills.md), and
is a different grant from `scopes` in the one way that matters: omitting it
grants **none**, not all. There is no every-marketplace push scope, so no token
issued before publication existed can write anything and none can be granted
publication to everything by forgetting a field. Entries are validated against
the registered hosted marketplaces at creation. An out-of-scope push answers
exactly like a marketplace that does not exist, as an out-of-scope fetch does.

`sessionDerived` on a returned token says the credential came from
[`POST /api/tokens/session`](#post-apitokenssession) rather than from a
deliberate provisioning.

`expiresAt` (GW_0065): an expired token fails authentication exactly like a
revoked one, decided by comparing the stamp at authentication time — no
background process is involved. When
[`skills-gateway.tokens.max-ttl`](../configuration.md#access-tokens) is set,
a request beyond the cap (including one with no expiry) is refused, never
silently shortened.

| Status | Cause |
| --- | --- |
| 201 | Issued. The `token` field appears in this response and nowhere else, ever. |
| 422 | Unknown scope, or lifetime beyond the configured cap. |

---

## `GET /tokens`

List the calling principal's tokens. Never returns the hash, and never returns
another user's tokens.

```json
[{"id":1,"name":"my-laptop","createdAt":"...","revokedAt":null,
  "scopes":[],"expiresAt":null,"rotatedFrom":null}]
```

A non-null `revokedAt` means the token no longer authenticates. Revoked tokens
are retained rather than deleted, so the record of what existed survives.

**200.**

---

## `DELETE /tokens/{id}`

Revoke a token. Takes effect immediately — the facade's lookup excludes revoked
rows.

| Status | Cause |
| --- | --- |
| 204 | Revoked. |
| 404 | No such token **owned by the caller**. Another user's token id is indistinguishable from a nonexistent one. |

---

## `POST /tokens/{id}/rotate`

Retire a possibly-exposed secret without renegotiating the grant (GW_0066).
Issues a fresh secret with the **identical** grant — name, scopes (fetch and
push alike), and the same
expiry deadline — records which token it replaced (`rotatedFrom`), and revokes
the old token *before* the new one is issued: a failure between the two steps
leaves no live secret, never two.

```json
{"id":7,"name":"ci-runner","token":"sgw_...","createdAt":"...",
 "scopes":["acme"],"expiresAt":"2026-12-31T00:00:00Z","rotatedFrom":1}
```

| Status | Cause |
| --- | --- |
| 200 | Rotated; the only response carrying the new cleartext. |
| 404 | No such token **owned by the caller**. |
| 409 | The token is revoked or expired — a dead grant is not a template for a live one; issue a new token instead. |

---

## Using a token

The facade reads only the password field of HTTP Basic; the username is ignored.

```console
$ git ls-remote https://token:sgw_...@skills.corp.example/git/acme
```

See [Consuming approved skills](../../guides/consuming-skills.md) for credential
helper setup.

!!! tip "One token per consumer"

    Issue a separate token per laptop and per pipeline. Revocation is then
    surgical, and ledger entries attribute fetches to something meaningful.

---

## `POST /api/tokens/session`

Mint a short-lived git credential from the calling principal's browser session
(GW_0104). The identity half of
[ADR 0008](../decisions.md): a human who has just proved who they are should
not have to create a second, standing credential in order to fetch.

**Body** — `{name, scopes?}`

There is deliberately **no** lifetime field. The gateway grants
[`skills-gateway.tokens.session-ttl`](../configuration.md#access-tokens)
(8 hours by default) and a caller who sends `expiresAt` anyway does not get it —
a credential whose life the holder chooses is a personal access token reached
through another URL.

```console
$ curl -X POST localhost:8080/api/tokens/session \
    -H 'Content-Type: application/json' -d '{"name":"my-laptop"}'
```

```json
{"id":9,"name":"my-laptop","token":"sgw_...","createdAt":"2026-08-23T09:00:00Z",
 "scopes":[],"expiresAt":"2026-08-23T17:00:00Z","rotatedFrom":null,
 "pushScopes":[],"sessionDerived":true}
```

| | Session credential | Personal access token |
| --- | --- | --- |
| Lifetime | the gateway's, not negotiable | the caller's, capped by `max-ttl` |
| Publication | never | `pushScopes` if granted |
| Marked on the ledger | yes, `session-derived` | no |
| Survives having no browser | no — it is minted from a session | yes |

`scopes` narrows it to named marketplaces exactly as for any token. Rotation
(`POST /api/tokens/{id}/rotate`) keeps both the expiry deadline and the
session-derived mark, so it can neither extend the credential nor launder it
into a standing one.

!!! warning "Not tied to the session's end"

    It is revoked by its timer, by `DELETE /api/tokens/{id}`, or not at all —
    logging out does not kill it, because the gateway does not track session
    lifetime.

| Status | Cause |
| --- | --- |
| 201 | Issued; the only response carrying the cleartext. |
| 401 | No authenticated session. |
| 422 | Unknown scope. |


## Machine API credentials

A **machine API credential** is what a Terraform provider or a CI job holds. It
is not a second credential type: it is an access token whose **API scope list**
is non-empty, sharing one issue-rotate-revoke-expire lifecycle with everything
else in this table.

### Three scope dimensions, three different empty values

This is the part that is easy to get wrong, so it is stated in one place:

| Dimension | Field | Empty means | Why |
| --- | --- | --- | --- |
| Fetch | `scopes` | every marketplace — **unless `apiScopes` is non-empty, in which case nothing** | What every pre-scoping token meant, minus the machine-credential hole |
| Publish | `pushScopes` | nowhere | Added after scoping existed, so it never had a permissive default |
| API | `apiScopes` | nothing | Reaching the control plane is a grant and never a baseline |

A credential holding API scopes therefore reaches **no** marketplace through the
git facade even though its fetch list is empty, and a personal access token
reaches **no** `/api/**` endpoint even though it is the most permissive fetch
grant the system has. The guarantee is symmetric and holds in the authentication
layer, not in a check a controller could forget to make.

### The scopes

There is no wildcard, no implicit "all", and **no scope implies another** —
`policy:write` does not confer `policy:read`. Scopes compose additively: a
credential reaches the union of its scopes' endpoints and nothing more.

| Scope | Reaches |
| --- | --- |
| `marketplaces:read` | `GET /api/marketplaces`, `GET /api/catalog`, and a snapshot's `/content`, `/content-diff`, `/licenses`, `/provenance`, `/release-age` |
| `snapshots:read` | A snapshot's `/diff`, `/file`, `/files`, `/vetting`, `/fetchers`, `/four-eyes` |
| `marketplaces:register` | `POST /api/marketplaces` |
| `marketplaces:ingest` | `POST /api/marketplaces/{name}/ingest` |
| `vetting:run` | `POST /api/marketplaces/{name}/revet`, `POST /api/snapshots/{id}/revet` |
| `waivers:read` | `GET /api/marketplaces/{name}/waivers` |
| `sync:write` | `PUT /api/marketplaces/{name}/sync` |
| `catalog:rebuild` | `POST /api/catalog/rebuild` |
| `webhooks:read` | `GET /api/webhooks`, `/deliveries`, `/events` |
| `webhooks:write` | `POST /api/webhooks`, `DELETE /api/webhooks/{id}` |
| `audit:read` | `GET /api/audit`, `GET /api/audit/export` |
| `audit-sinks:read` | `GET /api/audit/sinks` |
| `audit-sinks:write` | `POST /api/audit/sinks`, `DELETE /api/audit/sinks/{id}`, `PUT /api/audit/sinks/{id}/cursor` |
| `policy:read` | `GET /api/policy/rules`, `POST /api/policy/playground` |
| `policy:write` | `POST /api/policy/rules`, `PUT`/`DELETE /api/policy/rules/{name}` |
| `retention:read` | `GET /api/retention/candidates` |
| `estate:read` | `GET /api/estate` |
| `estate:reconcile` | `POST /api/estate/reconcile` |
| `adoption:read` | `GET /api/adoption`, `GET /api/adoption/staleness` |
| `roles:read` | `GET /api/roles` |

`POST /api/policy/playground` sits under `policy:read` because it evaluates a
policy against a candidate and persists nothing. It reads; the verb is an
artefact of needing a request body.

### What no credential reaches

An allowlist, not a denylist: an endpoint is unreachable until it is named, so a
new one added later is refused rather than admitted by silence. **No combination
of scopes and no role reaches any of these:**

| Endpoint | Why |
| --- | --- |
| `POST /api/snapshots/{id}/approve`, `/reject` | Publishes or refuses content; human judgement |
| `POST /api/snapshots/{id}/waivers`, `DELETE /api/waivers/{id}` | Overrides the vetting chain, and withdraws the override |
| `POST /api/retention/evaluate`, `/compact` | Soft-deletes and permanently purges: retracts content |
| `DELETE /api/snapshots/{id}`, `POST /api/snapshots/{id}/restore` | Retracts and republishes content |
| `POST /api/roles`, `DELETE /api/roles/{id}` | Privilege granting; see below |
| All of `/api/tokens/**` | Credential minting, including this page's own endpoints |
| `GET /api/me` | A session identity page; a machine has no session |

Role grants are **declarative only**: `estate.grants` already serves them with
the same validation and **no credential in the pipeline at all**, so a machine
write path would add escalation surface for something that has a safer route.
See [Declarative estate](../../guides/declarative-estate.md).

`GET /api/roles` **is** reachable, because denying the read would not prevent
configuration drift — the estate never prunes, so it cannot discover a grant
made by hand — it would only make that drift undetectable. The reconnaissance
cost is paid for instead: **every authorized read of `/api/roles` is recorded on
the audit ledger**, by a person and by a machine alike, with the entry's actor
type telling them apart. Reads of `/api/audit` are deliberately *not* recorded,
because logging a read of the ledger would make a polling exporter append an
entry that is itself new content to export.

### Roles still apply

Effective authority is the **intersection** of the allowlist, the credential's
scopes, and its principal's roles. A credential scoped `audit:read` still gets
**403** unless its principal holds
`auditor` or `admin`.

Scope and allowlist enforcement, unlike role enforcement, does **not** consult
that flag and is always on. The flag exists so an upgrade does not lock out
sessions that predate role enforcement, and nothing predates a credential kind
that did not exist.

A machine principal acquires a role from the deployment's configuration
(`skills-gateway.roles.admins`) or from a grant, which `estate.grants` can
declare. It never acquires one from identity-provider claims: a credential has
none.

### `POST /api/tokens/machine`

Provisions a credential. Requires the `admin` role **whether or not role
enforcement is enabled** — a credential outlives the session that created it,
and under a default-off flag any user who completed a login could otherwise mint
one that keeps working after their own account is deprovisioned.

```json
{
  "principal": "terraform-ci",
  "name": "platform-pipeline",
  "apiScopes": ["marketplaces:register", "estate:read"],
  "expiresAt": "2026-11-01T00:00:00Z"
}
```

`principal` is not a person. It is what the ledger attributes this credential's
actions to and what a role grant names.

**Everything here is a refusal rather than a default:**

| Condition | Result |
| --- | --- |
| An unknown or misspelled scope value | **422** — it fails loudly rather than silently never matching |
| `"*"`, `"all"`, or an empty `apiScopes` | **422** — there is no value that grants every scope |
| No `expiresAt` | **422** — mandatory, and never defaulted |
| `expiresAt` beyond the cap | **422** — refused, never shortened |
| The caller is not an admin | **403** |

The cap is `skills-gateway.tokens.max-ttl` when set, and otherwise a built-in
**90 days**; see [Configuration](../configuration.md). The cleartext is returned
exactly once.

### `GET /api/tokens/machine`

Every machine credential, whoever provisioned it, never a secret. Deliberately
not scoped to the caller: a machine credential's principal is not an identity
anyone logs in as, so an owner-scoped listing would leave every one of them
invisible — and unrevokable — during an incident. Each row carries its
`machineOwner`, the person who provisioned it. `GET /api/tokens` is unaffected
and still shows only the caller's own tokens.

### `POST /api/tokens/machine/{id}/rotate`

Same grant, new secret. The principal, name, expiry **deadline** and every one
of the API scope values carry over, and the old credential is revoked before the
new one is issued, so no moment has two live secrets.

### `DELETE /api/tokens/machine/{id}`

Revokes it. Checked at authentication time rather than swept, so it takes effect
on the credential's very next request.

### Using one

```bash
curl -H "Authorization: Bearer $SKILLS_GATEWAY_TOKEN" \
     https://gateway.example.com/api/estate
```

Send no cookie. See [REST API](index.md) for why a request carrying both is
refused.

!!! note "The first credential is minted by a person"

    `/api/tokens/**` is unreachable by machine by design, so bootstrapping means
    an administrator driving this endpoint from a browser session — once, to
    create something with a stated expiry and named scopes, rather than
    continuously to run a pipeline. A portal screen is what removes that step
    properly, and is not part of this release.
