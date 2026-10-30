# Tasks: csrf-session-bound-token

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_AUTH_0058, GW_AUTH_0059 and SVC_GW_AUTH_0058 … SVC_GW_AUTH_0062
  in `docs/reqstool/`.

## 2. Token held by the session (SVC_GW_AUTH_0058, SVC_GW_AUTH_0059, SVC_GW_AUTH_0060)

- [x] 2.1 `SessionBoundCsrfTests` over real HTTP: the portal flow is accepted, a
  pair the session was never issued is refused `403`, a token issued to one
  session is refused on another. Watch them fail first.
- [x] 2.2 `SessionBoundCsrfTokenRepositoryTests` against a stub: session
  storage, cookie attributes, cookie never read, no session created, copy
  repaired. Watch them fail first.
- [x] 2.3 `SessionBoundCsrfTokenRepository` and `SpaTokenHandler` on both web
  chain branches (`@Requirements GW_AUTH_0058`), with `CsrfEnforcementTests` and
  `SessionCookieTests` unchanged.

## 3. No session for unauthenticated requests (SVC_GW_AUTH_0061, SVC_GW_AUTH_0062)

- [x] 3.1 `SessionBoundCsrfTests`: unauthenticated `/api/v1/me`,
  `/status/v1/snapshots`, `/git/…` and an anonymous POST set no session cookie
  and the store does not grow; a machine credential and a PAT create none; the
  sign-in redirect still creates exactly one. Watch the first fail.
- [x] 3.2 The web chain's request cache (`@Requirements GW_AUTH_0059`).

## 4. Documentation

- [x] 4.1 `concepts/trust-boundaries.md`, `reference/api/index.md`,
  `reference/configuration.md` (session cookie; OIDC redirect URI and `Host`
  under forwarded headers). Verify with `mkdocs build --strict`.

## 5. Gates and evidence

- [x] 5.1 `mutants.sh`, a real-execution check of the escape hatch, then every
  AGENTS.md gate fresh, recorded in `evidence.md`.
