# Tasks: csrf-on-the-session-surface

Finding F4 of `docs/analysis/2026-09-08-architecture-assessment.md` — the second
half of its step 1, and the more serious of the two.

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_AUTH_0030 — A cross-site request cannot act on an ambient
      session. One requirement, not two: the token and the same-site attribute
      are two mechanisms for one guarantee, and splitting them would invite
      shipping half of it
- [x] 1.2 Add SVC_GW_AUTH_0030 covering both the token requirement and what the
      two cookies must say
- [x] 1.3 Confirm GW_AUTH_0030 is free on this branch and on `origin/main`, and
      that no in-flight change under `openspec/changes/` reserves it

## 2. The chain

- [x] 2.1 `webChain` configured branch: `.csrf(CsrfConfigurer::spa)`
- [x] 2.2 `webChain` dev-insecure branch: the same, deliberately
- [x] 2.3 `@Requirements` on the bean gains GW_AUTH_0030; the javadoc says why
      this chain needs a token and the other four do not
- [x] 2.4 The four request-authenticated chains are left exactly as they are

## 3. The cookie

- [x] 3.1 `server.servlet.session.cookie.same-site: lax` in `application.yaml`
      — a `server:` block the file did not have
- [x] 3.2 Comment states why `lax` and not `strict`, because `strict` is the
      obvious-looking choice that breaks login

## 4. The portal

- [x] 4.1 `client.ts` reads `XSRF-TOKEN` and sends `X-XSRF-TOKEN`. One wrapper,
      one `fetch` call site in the whole SPA, so this is the entire surface
- [x] 4.2 Fix the header merge while there: `...init` spread *after* `headers`
      meant a caller passing `headers` would drop the content type. It worked
      only because no caller passes one, and the token would have made that a
      real bug rather than a latent one

## 5. Tests

- [x] 5.1 `AbstractGatewayTest` applies a token by default via `defaultRequest`
- [x] 5.2 `CsrfEnforcementTests` proves the token is required: a body-less POST
      on an **administrator** session — so a 403 can only be the token, never a
      role — refused without it and accepted with it
- [x] 5.3 The same suite asks the **running server** over real HTTP for what
      MockMvc cannot show. The CSRF post-processor reflectively swaps the
      application's `CookieCsrfTokenRepository` for a session-backed one, so no
      MockMvc assertion can prove the cookie the portal reads is ever written
- [x] 5.4 The real-HTTP probe is the login redirect — the very request `strict`
      would break — so asserting `SameSite=Lax` on it is asserting login works
- [x] 5.5 Bare `csrf()`, never `csrf().asHeader()`: under `spa()` the request
      attribute holds the XOR-masked value, which round-trips as the `_csrf`
      parameter but fails as a header, since a present header switches the
      resolver to the unmasking-free path

## 6. Docs in the same PR

- [x] 6.1 `reference/api/index.md` — the paragraph documenting the exemption
- [x] 6.2 Every other place stating CSRF is off for `/api/**`
- [x] 6.3 The `lax`-not-`strict` reason, said once
- [x] 6.4 That the PAT and Bearer paths are unaffected

## 7. Gates

- [x] 7.1 `./mvnw clean verify`
- [x] 7.2 `pnpm test:stories`
- [x] 7.3 `pnpm e2e` — the real proof of the round trip: a real browser, real
      OIDC login, real POSTs through `client.ts` against the real jar
- [x] 7.4 `reqstool status local -p docs/reqstool` ends PASS
- [x] 7.5 `openspec validate --all --strict`
- [x] 7.6 `mkdocs build --strict`
- [x] 7.7 `evidence.md` with the pasted tails and the commit SHA
