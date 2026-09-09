# Evidence: csrf-on-the-session-surface

One fresh run of every gate, after the last code edit, at commit `469ae66`
(`fix(auth)!: require a CSRF token on the cookie-authenticated surface`).
Run on 2026-09-09.

## 1. `./mvnw clean verify`

```
[INFO] Tests run: 621, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  07:40 min
```

621 rather than 619: `CsrfEnforcementTests` and `SessionCookieTests`.

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

## 3. `(cd src/main/frontend && pnpm e2e)`

```
  13 passed (46.3s)
```

**This is the load-bearing gate for this change.** A real browser logs in
through a real OIDC provider and drives real mutating POSTs — register, ingest,
approve, token create and revoke, webhook subscribe, soft delete and restore,
waive — through the modified `client.ts` against the real jar with the token
enforced. Had the cookie read or the header name been wrong, every mutating case
would have failed with 403. None did.

## 4. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
188/188 complete · 0 incomplete · PASS
```

188, up from 187: GW_AUTH_0030.

## 5. `openspec validate --all --strict`

```
Totals: 36 passed, 0 failed (36 items)
```

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 1.18 seconds
```

## What two failed runs taught, kept because the reasons outlive them

**A test that passed alone and failed in the suite.** The real-HTTP cookie
assertions were first written onto the shared context. They passed in isolation
and failed under `clean verify`, because
`SecurityMockMvcRequestPostProcessors.csrf()` reflectively replaces the
application's `CookieCsrfTokenRepository` with a session-backed one, and the
replacement lands on the cached context's filter chain for the rest of the JVM.
Suites written long before this change already use it. In that context there is
no cookie repository left to observe, so no assertion made there could tell a
working deployment from a broken one. Hence `SessionCookieTests` and its own
context — a division that is forced, not stylistic.

**A discriminator that did not discriminate.** That suite first also asserted
403 without a token and 401 with one, on an unauthenticated request, to show the
token is demanded ahead of identification. It answered 401 both times: a refusal
for want of a token reaches an anonymous caller through this chain's 401 entry
point. The assertion was removed rather than adjusted to match, because the pair
was the entire point of it and a single status proves nothing about CSRF. The
case that discriminates needs a session, and `CsrfEnforcementTests` is where it
lives.

**`ContextBudgetTests` caught the new context.** The suite caps distinct Spring
contexts at 32 and the new suite would have made 33. Rather than raise a ratchet
the project keeps deliberately, `SessionCookieTests` reuses
`AbstractForwardedHeadersTest`'s property set: the posture that suite needs — a
real server, a configured provider never reached, the authorization redirect as
the probe — is the posture this one needs, and it uses no MockMvc, so its
context still holds the real cookie repository. Final count: `distinct application contexts: 32`.

## What the gates do not cover

- **`SameSite=Lax` versus `Strict` is not provable by e2e**, and this is the
  reason the assessment's recommendation was not followed. The mock provider runs
  on `localhost:9090` and the gateway on `localhost:8080` — the same
  registrable domain — so the callback is same-site under test and cross-site in
  production. `Strict` would pass every gate here and break login in a real
  deployment. `SessionCookieTests` asserts the attribute on the login redirect
  directly, which is the closest a test in this repository can get.
- **No test asserts the XOR masking**, only that a token is required and that the
  cookie is readable. The masking is `spa()`'s, not this project's.
- **The Scalar reference at `/docs` sends no token**, so mutations tried from it
  are now refused. That is documented rather than fixed.
