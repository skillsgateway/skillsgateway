# Proposal: the anti-forgery token is held by the session

## Why

The web chain's anti-forgery check (`GW_AUTH_0030 — A cross-site request cannot
act on an ambient session`) used `csrf.spa()`, which compares the
`X-XSRF-TOKEN` header with the `XSRF-TOKEN` cookie and nothing else. Any equal
pair passed, including one the gateway never issued and one issued to another
session, so the check rested on nobody else being able to write a cookie for the
gateway's host. The requirement's own wording is "the anti-forgery token it
issued to that session"; this change makes the implementation say that too.

Separately, an unauthenticated request to the web chain was answered `401` and
also left a new server session behind (the request cache saved it), so every
anonymous probe wrote a row to the session store.

## What Changes

- The canonical anti-forgery token lives in the server session (the Spring
  Session JDBC store, so every instance sees it). The `XSRF-TOKEN` cookie is
  only a copy the portal reads; the value it carries is never consulted. A
  header that does not equal the token stored in the caller's own session is
  refused `403`. The portal is unchanged: it still reads the cookie and echoes
  it in `X-XSRF-TOKEN`.
- The `XSRF-TOKEN` cookie names `SameSite=Lax` and is `Secure` whenever the
  request is.
- A request the web chain answers `401` creates no session, and the
  request-authenticated chains (`/git/**`, `/publish/**`, `/status/**`,
  `/hooks/**`, bearer `/api/**`) never create one. The browser sign-in still
  creates the session that holds the authorization request.
- New `GW_AUTH_0058 — The anti-forgery token is held by the session it was
  issued to` and `GW_AUTH_0059 — An unauthenticated request leaves no session
  behind`, verified by `SVC_GW_AUTH_0058` … `SVC_GW_AUTH_0062`.
- Docs: `concepts/trust-boundaries.md`, `reference/api/index.md`,
  `reference/configuration.md` (session cookie, and a note on the OIDC redirect
  URI and the `Host` header under forwarded headers).
- Not **BREAKING**: no API path, request or response shape changes. A browser
  holding a session from before the upgrade gets a fresh token on its next
  read, so its first write after the upgrade may be refused once.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `auth`: adds `GW_AUTH_0058` and `GW_AUTH_0059`.

## Impact

- `auth/SecurityConfig` (web chain CSRF repository, request cache) and a new
  `auth/SessionBoundCsrfTokenRepository`.
- No dependency, schema, configuration-leaf or frontend change.
