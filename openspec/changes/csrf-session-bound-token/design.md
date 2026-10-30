# Design: csrf-session-bound-token

## Decisions

### D1 — The session holds the token; the cookie is a copy

`SessionBoundCsrfTokenRepository` replaces `CookieCsrfTokenRepository` on the
web chain. It stores the token with `HttpSessionCsrfTokenRepository`, so the
token is a session attribute in the Spring Session JDBC store and every
instance reads the same one (`GW_AUTH_0053 — A browser session does not depend
on which instance answers`). Every save also writes `XSRF-TOKEN` with the same
value. `loadToken` reads the session only, so a cookie the server did not issue
to this session never becomes the expected value.

The cookie keeps the name, path and script-readability the portal relies on, so
the portal needs no change. It now names `SameSite=Lax`, the same as the session
cookie: the server never reads this cookie, so `Strict` would protect nothing.
It is `Secure` when the request is, which is `request.isSecure()` after the
forwarded-header strategy has run.

The alternative, an HMAC of the token with the session id, was not taken. It
needs a key shared by every instance, and Spring Security changes the session id
at sign-in, so the token would be derived from the wrong id unless that step
were also rewritten. A session attribute has neither problem.

### D2 — The copy is repaired when it differs

The repository defers loading the same way as the interface's default. In
addition, when the token comes from the session and the request's
`XSRF-TOKEN` is missing or different, the response writes the copy again. A
browser whose cookie was lost or overwritten therefore recovers on its next
read instead of being refused until its session ends.

### D3 — The SPA request handler, kept

`csrf.spa()` pairs the cookie repository with a private handler: the
`X-XSRF-TOKEN` header carries the raw token, a form parameter the XOR-masked
one, and the token is resolved on every request so its copy reaches the
portal. `SecurityConfig.SpaTokenHandler` is a copy of that handler, so the
masking for anything rendered into a page is unchanged.

### D4 — Which requests may create a session

Because the handler resolves the token on every request, a repository that
created a session to hold it would create one for every anonymous request. It
creates none, with one exception passed in by the chain:

- the configured chain: the request that starts a browser sign-in,
  `/oauth2/authorization/{registrationId}`, which creates the session for the
  authorization request anyway. The existing assertion that the sign-in
  redirect sets a readable `XSRF-TOKEN` keeps holding.
- the development escape hatch: any request dispatched as `REQUEST`. Nothing
  signs in there, so without this the hatch would have no token at all and the
  portal could not write. An error dispatch is excluded, so the facade's `401`
  rendered through `/error` creates no session there either.

Without a session, a generated token is stored nowhere, so a state-changing
request has nothing to match and is refused.

### D5 — The request cache saves only what it can return to

The other source of anonymous sessions was the request cache: the exception
translation filter saved every unauthenticated request before answering it,
including `/api/**` requests answered `401`, and including the `/error`
dispatch that renders another chain's `401` (which is how `/git/**` and
`/status/**` refusals created sessions). The web chain now uses an
`HttpSessionRequestCache` that saves only a `REQUEST`-dispatched `GET` outside
`/api/**`, excluding favicons and `XMLHttpRequest` calls. Those are the
requests answered with a sign-in redirect, so a deep link still survives the
sign-in.

### Not in this change

- No change to the session cookie, the request-authenticated chains or the
  portal.
- No new configuration leaf, Spring test context or dependency.
