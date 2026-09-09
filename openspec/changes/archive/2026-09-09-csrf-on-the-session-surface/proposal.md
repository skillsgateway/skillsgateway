# Proposal: csrf-on-the-session-surface

## Why

The cookie-authenticated chain carried
`.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))` and the comment
"revisit CSRF with the portal". The portal arrived; the revisit did not.

The session cookie is the only ambient credential the gateway has, so it is the
only surface where a browser will attach the caller's authority to a request the
caller never made. Two things were assumed to close the gap and only one does.

- **The JSON content-type requirement holds** — a plain HTML form cannot set it,
  and `PUT`/`DELETE` cannot come from a form at all. Every route that takes a
  body is genuinely unreachable this way.
- **`SameSite` did not.** It was never set, and Firefox and Safari apply no
  default. In those browsers the session cookie rode a cross-site top-level form
  POST.

What was left exposed is every **body-less mutating POST**, because a form can
produce one with no content type worth the name: `.../ingest`,
`.../snapshots/{id}/approve` (its body is `required = false`), `.../reject`,
`.../revet`, `.../restore`, `/api/retention/evaluate`, `/api/retention/compact`,
`/api/catalog/rebuild`, `/api/estate/reconcile`, `/api/mirror/reconcile` and
`/api/tokens/{id}/rotate`.

**An approval is in that list.** A logged-in reviewer visiting a hostile page in
Firefox could be made to approve a snapshot, against a product whose single
principle is that nothing is served that a person did not approve.

## What Changes

- **A CSRF token on the session chain**, via Spring Security 7's built-in
  `CsrfConfigurer.spa()`: an `XSRF-TOKEN` cookie the portal's own script reads,
  echoed in `X-XSRF-TOKEN`, XOR-masked per response. On 6.x this was forty lines
  of hand-rolled `SpaCsrfTokenRequestHandler` and a `CsrfCookieFilter`; on 7.1.1
  it is one method reference, and the eager-token path inside it means the
  cookie is written without a filter to force it.
- **`server.servlet.session.cookie.same-site: lax`**, named rather than left to
  the browser. `lax`, **not** the `strict` the assessment recommended: `strict`
  withholds the session cookie on the identity provider's redirect back to
  `/login/oauth2/code/*`, which is a cross-site top-level navigation, and login
  fails outright. `lax` still withholds it on every cross-site POST, which is
  the vector. Belt and braces with the token, not a substitute for it — a
  sibling host on the same registrable domain is "same-site".
- **The development escape hatch gets the same posture.** It opens
  authentication, not forgery protection, and a token path exercised only in
  production is one discovered broken in production.
- **The four request-authenticated chains are untouched.** Each is `STATELESS`,
  authenticates every request on its own and honours no cookie; their existing
  comments already justify the exemption correctly.
- **One `defaultRequest` in the test base rather than 143 edits.** MockMvc's
  `defaultRequest` post-processors merge ahead of the per-request ones instead of
  replacing them, so `.with(oidcLogin())` still applies and no suite's call sites
  change. `CsrfEnforcementTests` then carries the proof on its own, because a
  default token means no other suite can fail for a missing one.

## Capabilities

### New Capabilities

**auth.** GW_AUTH_0030 — A cross-site request cannot act on an ambient session.

### Modified Capabilities

_None._ No existing requirement changes. GW_AUTH_0002 — Web authentication via
OIDC gains the new id on the same bean, which is annotation, not amendment.

## Impact

**Breaking for one caller class, and it is a class the documentation never
sanctioned.** Anything driving `/api/**` with a session cookie from outside the
portal — a script that logged in through a browser and reused the cookie — now
needs the token. The documented machine paths are unaffected: PATs on `/git/**`
and `/publish/**`, and Bearer credentials on `/api/**`, all reach chains that
never had CSRF and still do not.

No path prefix moves and the OpenAPI document does not change: CSRF is a filter
concern that springdoc does not describe. As with the blast-radius report, that
means the contract gate cannot see this narrowing —
`docs/manual/reference/compatibility.md` already records why a security
correction narrows in place rather than moving the prefix.
