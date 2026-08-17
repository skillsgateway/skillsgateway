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

**Body** — `{name, scopes?, expiresAt?}`

```console
$ curl -X POST localhost:8080/api/tokens \
    -H 'Content-Type: application/json' \
    -d '{"name":"ci-runner","scopes":["acme","catalog"],"expiresAt":"2026-12-31T00:00:00Z"}'
```

```json
{"id":1,"name":"ci-runner","token":"sgw_...","createdAt":"2026-08-17T09:00:00Z",
 "scopes":["acme","catalog"],"expiresAt":"2026-12-31T00:00:00Z","rotatedFrom":null}
```

`scopes` (GW_0064) lists marketplace names the token may fetch — the
[virtual catalog](../../guides/virtual-catalog.md)'s name is a valid entry —
and every entry is validated against the registered marketplaces at creation.
Empty or omitted grants every marketplace, which is what every pre-scoping
token meant. An out-of-scope fetch answers exactly like a marketplace that
does not exist, so a scoped token is not a directory of what else the gateway
governs.

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
Issues a fresh secret with the **identical** grant — name, scopes, and the same
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
