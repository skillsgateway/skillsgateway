# Evidence: csrf-session-bound-token

Source state: `ca38a2c9`, on `main` at `b1155dfa`, after the review fixes
(see "Review fixes" below). Every result below is from one fresh run on that
commit, on 2026-10-30. The first round of gates had passed on `6e2cc931`; the
branch was already up to date with `main` when the review started, so it was
not rebased again.

Tier 3 (authentication, the web chain's trust boundary). Spec approval: not
obtained, autonomous run (the owner asked for implementation without a review
stop). Independent verification: not performed. Both are declared downgrades.

## Spec → tests

| Scenario | Test | Seen failing first |
|---|---|---|
| SVC_GW_AUTH_0058 — the portal flow is accepted; a matching cookie and header the session was never issued are refused `403` | `SessionBoundCsrfTests.the_portal_flow_is_accepted_and_a_pair_the_session_was_never_issued_is_refused` | yes: `expected: 403 but was: 200` |
| SVC_GW_AUTH_0059 — a token issued to session A, sent as cookie and header with session B, is refused; B's own token is accepted | `SessionBoundCsrfTests.a_token_issued_to_one_session_is_refused_on_another` | yes: `expected: 403 but was: 200` |
| SVC_GW_AUTH_0060 — the token is stored in the session; the copy is `SameSite=Lax`, script-readable, `Secure` exactly on a secure request; a cookie alone is no token and creates no session; a differing copy is rewritten, a matching one is not; removal clears both; a sign-in may start the session | `SessionBoundCsrfTokenRepositoryTests` (7 methods) | yes: all 7 against a stub that threw |
| SVC_GW_AUTH_0061 — unauthenticated `/api/v1/me`, `/status/v1/snapshots`, `/git/…/info/refs` (each with a wildcard and an HTML `Accept`), a portal page asked for without HTML, and an anonymous POST are refused, set no `SESSION` cookie, and the store does not grow | `SessionBoundCsrfTests.an_unauthenticated_request_is_answered_without_creating_a_session` | yes: `/api/v1/me` set `SESSION=…`; after the first fix, `/status/v1/snapshots` still did (the `/error` dispatch), which is what D5's dispatcher-type rule answers; in review, `/approvals` with `Accept: */*` was answered `401` and set `SESSION=…`, which D5's page-navigation rule answers |
| SVC_GW_AUTH_0061 — a machine credential (POST, no token) and a PAT on the facade are served and create no session | `SessionBoundCsrfTests.a_request_authenticated_by_its_own_credential_needs_no_token_and_creates_no_session` | no: pre-existing behaviour, kept as regression armour; the request-authenticated chains were not changed, so no mutant targets them |
| SVC_GW_AUTH_0062 — the sign-in redirect still creates exactly one session | `SessionBoundCsrfTests.the_browser_sign_in_still_creates_the_session_that_holds_its_authorization_request` | no: pre-existing behaviour, kept as regression armour; no code mutant removes the sign-in session without removing sign-in, so its count assertion is not separately mutation-proven |
| SVC_GW_AUTH_0062 — a page navigation is redirected to the sign-in and creates exactly one session, the one that keeps the page | `SessionBoundCsrfTests.a_page_navigation_is_redirected_to_the_sign_in_and_kept_to_return_to` | no: pre-existing behaviour, added in review as armour for the narrowed cache; M11 proves it |
| Invariant — existing GW_AUTH_0030 tests unchanged in assertions | `CsrfEnforcementTests`, `SessionCookieTests` (comment wording only) | — |

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, UI gate, jar | `flock /tmp/claude-1000/sgw-verify.lock ./mvnw clean verify` | `Tests run: 1192, Failures: 0, Errors: 0, Skipped: 9` · UI `Test Files 31 passed (31)` · `Tests 234 passed (234)` · `BUILD SUCCESS` · `Total time: 08:28 min` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)`, after deleting `node_modules/.cache/storybook` and `node_modules/.vite`; the run before it hit the known harness flake (`adoption.stories.tsx` iframe not ready within 60000ms, 19 of 21 files passed, no story failed) |
| e2e (real sign-in against the mock IdP, approvals, sign-out) | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | exit 0 · requirements `350 (100.00%)` verified · tests `1210` passed, `0` failed · SVCs missing tests `0` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 29 passed, 0 failed (29 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.67 seconds` |
| Mutation | `openspec/changes/archive/2026-10-30-csrf-session-bound-token/mutants.sh` | `baseline green` · `12 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Real execution, escape hatch | the jar with `SKILLSGATEWAY_DEVINSECUREAUTH=true` against a throwaway PostgreSQL, driven with `curl` | see below |
| Supply chain | no dependency added or changed | — |

```
M1-cookie-read-as-token killed
M2-cookie-repository-back killed
M3-token-creates-session killed
M4-api-request-saved killed
M5-error-dispatch-saved killed
M6-sign-in-starts-no-session killed
M7-no-same-site killed
M8-never-secure killed
M9-copy-not-repaired killed
M10-non-page-get-saved killed
M11-page-navigation-not-saved killed
M12-spa-handler-dropped killed
12 mutants run, all killed
```

The runner now requires its three test classes to pass unmutated before it
counts a kill (`baseline green`). In review that mattered: a strengthened
probe was failing unmutated on an expectation, not on the rule, and M4, M5
and M10 had been "killed" by that failure. With the probe corrected, each is
killed by its own assertion (`/api/v1/me`, `/status/v1/snapshots` and a
`*/*` page request respectively setting `SESSION=…`).

The runner failed closed twice during the work, both times correctly: once on
a mutant that did not compile (`INVALID (no test report)`), and once when the
escape-hatch edit made a mutant's anchor text appear twice (`mutant does not
apply exactly once`). Both anchors were fixed and the full run repeated.

### Escape hatch, run for real

The development branch of the web chain (D4) has no automated test, because a
dev-hatch context over real HTTP would be another Spring test context
(`ContextBudgetTests`). It was run instead, on the jar built from `f9bacc36`,
before the rebase; the rebase changed nothing under `src/main/java/…/auth`:

```
sessions after health probes: 0
== facade 401
HTTP/1.1 401
== status 401
HTTP/1.1 401
sessions after facade/status: 0
== read (portal load)
HTTP/1.1 200
Set-Cookie: XSRF-TOKEN=58da1fe9-…; Path=/; SameSite=Lax
Set-Cookie: SESSION=…; Path=/; HttpOnly; SameSite=Lax
== write with the echoed token
200
== forged matching pair on the same session
403
== second session, first session's token
403
== overwritten copy is repaired on the next read
Set-Cookie: XSRF-TOKEN=<session A token>; Path=/; SameSite=Lax
sessions at end: 2
```

The first run of this check, before `f9bacc36`, showed health probes creating
sessions under the hatch; that commit excludes `/actuator/**` from the hatch's
session start.

## Review fixes

- The request cache saved any unauthenticated `GET` outside `/api/**`, so one
  without an HTML `Accept` (curl's `*/*`, `application/json`, none) was
  answered `401` and still created a session. It now saves only a request that
  asks for `text/html` or `application/xhtml+xml` by name, which the OIDC entry
  point redirects to sign-in (D5).
- The copied SPA request handler is gone: the web chain calls `csrf.spa()` and
  replaces only its repository (D3). M12 proves the handler is still in force.
- Docs: the `/api` page's 401 sentence moved next to the 401 paragraph, and
  the trust-boundaries page names both requests that may create an anonymous
  session.

## Known limits

- A browser asking for HTML on another chain (`/status/**`, `/git/**`) whose
  `401` is rendered through `/error` is redirected to sign-in instead; no
  session is created. This predates the change and is left as is.

- The escape hatch's session-start rule is verified by the run above, not by a
  test, and no mutant covers it. That run predates the review fixes; they leave
  the hatch's matcher as it was and change only how its request handler is
  configured, to the same one, which M12 proves on the configured branch.
- A browser holding a session from before the upgrade has no stored token; its
  first write may be refused once, and the response carries the new copy.
- Two concurrent first reads in one session can store different tokens; the
  copy is rewritten on the next read (D2).
