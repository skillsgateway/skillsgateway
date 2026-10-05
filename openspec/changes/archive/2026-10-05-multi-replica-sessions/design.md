# Design: multi-replica-sessions

## Context

The web chain (`SecurityConfig.webChain`) is `oauth2Login` with Spring Security's defaults:
`HttpSessionOAuth2AuthorizationRequestRepository` and `HttpSessionSecurityContextRepository`, both in
Tomcat's in-memory session. The security context holds the `OidcUser`, whose claims
`ClaimRoleMapper` reads on every request to map IdP groups to roles. CSRF is already a cookie
(`CsrfConfigurer::spa`) and needs nothing. Spring Security 7's generated login page prints the fixed
string "Invalid credentials" for `?error` whatever the cause. The four facade, publication, hooks and
machine chains are `STATELESS` and are unaffected.

## Goals / Non-Goals

**Goals:** a login and the session after it work whichever replica answers each request; a failed
login is diagnosable from the log and from the page; the lease holder is observable without database
access; a prefix-scoped grant is enough to start.

**Non-Goals:** a portal-rendered login or error page; reworking `/logout`; a lease admin endpoint;
an INFO line per lease taken (the webhook pass takes one every five seconds).

## Decisions

**Sessions in PostgreSQL through Spring Session JDBC.** This was chosen over the issue's first
preference, a stateless encrypted-cookie `AuthorizationRequestRepository` and
`SecurityContextRepository`. The reasons:

- A context carrying the ID token and a directory's group claim, once encrypted, often goes over a
  browser's 4 KB cookie limit, so the context would have to be slimmed or split across cookies.
- The cookie approach puts hand-written AEAD and a key setting on the authentication boundary.
- A stateless cookie cannot be invalidated by logout.

Affinity alone was rejected because it leaves a gateway behind a plain round-robin balancer unable to
log anyone in. Spring Session's two tables (`spring_session`, `spring_session_attributes`, its
published PostgreSQL schema) go into `V1__init.sql`, and `spring.session.jdbc.initialize-schema=never`
leaves Flyway as the only writer of the schema. The cleanup of expired sessions is Spring Session's
own scheduled `DELETE`. It runs on every replica and is idempotent, so it needs no lease. The cookie
takes Spring Session's name, `SESSION`, and keeps the configured `same-site: lax`.

**An unreadable session is no session.** Attributes are JDK-serialized, and Spring Security changes
its `serialVersionUID` on every minor release. A rolling upgrade across one would otherwise answer
every existing session with a 500 until it expired. A `springSessionConversionService` bean
deserializes with the default deserializer and turns a failure into `null` at DEBUG. The request then
has no context, and the user logs in again. A stale row is never trusted partially: an attribute is
either deserialized intact or absent.

**The failure handler answers on the spot rather than redirecting.** A redirect to `/login?error`
needs a second request and session state to carry the reason, which is the failure mode itself. An
`AuthenticationFailureHandler` in `auth` therefore does two things:

- It logs WARN `browser sign-in failed: registration=<id> error=<code> description=<text>`, with
  control characters stripped from the IdP-supplied description.
- It writes a 401 HTML page with the reason class, the error code, and a link to
  `/oauth2/authorization/<registration>` to start again.

The page has no script, which keeps it inside the existing CSP. It sorts the error code into one of
three classes:

- `authorization_request_not_found` means the sign-in was lost and should be started again.
- The codes an authorization server returns in the redirect (RFC 6749 §4.1.2.1, OpenID Connect Core
  §3.1.2.6) mean the identity provider declined it.
- Anything else means the gateway could not accept the identity provider's answer, for example
  `invalid_id_token` or `invalid_token_response`.

**The lease counter lives in `SweepLeases`**, with the registry taken through `ObjectProvider` as
`GitStorageConfiguration` takes it, so a context without one still runs. It is a Micrometer counter
`skills_gateway.sweep.lease` tagged `pass` (the lease key) and `outcome` (`taken`/`skipped`). It is
per replica by construction, and the scrape's instance label tells replicas apart. Taking a lease
also logs at DEBUG with holder and `leased_until`, beside the existing DEBUG line for a skipped one.

**`ObjectStoreClient.probe(String keyPrefix)`.** The client is bucket-level and the prefix belongs to
`ObjectStoreGitStorage`, so the storage passes its normalised prefix to the probe rather than the
client learning about prefixes. The probe object becomes `<prefix>_probe/<uuid>`.

## Risks / Trade-offs

- [Every authenticated portal request now reads a session row] → one primary-key lookup on a small
  table, plus a last-access write that Spring Session batches per request. This is negligible beside
  the request's own queries.
- [Sessions now live in the database's backups] → they hold the OIDC user's claims and ID token,
  which the gateway already treats as identity data. Rows expire after the session timeout and the
  cleanup removes them.
- [The cookie name changes from `JSESSIONID` to `SESSION`] → existing sessions end at the upgrade,
  which a V1 edit implies anyway. Anything matching the old name must be updated, and no
  documentation does.

## Migration Plan

`V1__init.sql` is edited in place (pre-1.0), so databases are recreated and users log in once more.
Rollback is the previous image.
