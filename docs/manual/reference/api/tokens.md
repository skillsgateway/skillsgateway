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
