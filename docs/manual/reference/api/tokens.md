# Access tokens

Personal access tokens are the credential git clients use against the
[facade](../git-facade.md). They are the only part of the API scoped per user.

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

**Body** — `{name}`

```console
$ curl -X POST localhost:8080/api/tokens \
    -H 'Content-Type: application/json' -d '{"name":"my-laptop"}'
```

```json
{"id":1,"name":"my-laptop","token":"sgw_...","createdAt":"2026-08-15T09:00:00Z"}
```

**201.** The `token` field appears in this response and nowhere else, ever.

---

## `GET /tokens`

List the calling principal's tokens. Never returns the hash, and never returns
another user's tokens.

```json
[{"id":1,"name":"my-laptop","createdAt":"...","revokedAt":null}]
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
