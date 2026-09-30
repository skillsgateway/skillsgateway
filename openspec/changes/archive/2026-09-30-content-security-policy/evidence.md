# Evidence: content-security-policy (Tier 3, security header)

- **Spec approval:** not obtained (autonomous run). The owner delegated the
  #550 decisions in advance, and `acceptance.md` is the after-the-fact review
  artifact. It was committed at `9e51b9c5`, before any implementation.
- **Source state:** `05bcd037` on `feat/content-security-policy`. Every gate
  below ran fresh over that commit, after the last code edit. The only later
  commits are this file and the archive.
- **Entry points:** the project gates (below) and
  `openspec/changes/content-security-policy/mutants.sh`.
- **Isolation:** worktree `.claude/worktrees/csp` from `origin/main` at
  `ba0367c6`. Dependencies were rebuilt there by `./mvnw verify`, which runs the
  pinned node/pnpm install.
- **Independent verification:** not performed.

## RED — the header test against the unchanged `SecurityConfig`

`ContentSecurityPolicyTests` was run at `origin/main` plus the new test only:

```console
[ERROR] Tests run: 4, Failures: 4, Errors: 0, Skipped: 0 <<< FAILURE! -- in dev.skillsgateway.server.ContentSecurityPolicyTests
[ERROR]   a_portal_route_carries_the_strict_policy_and_keeps_the_other_default_headers:33 Response header 'Content-Security-Policy' expected:<[default-src 'self'; …]> but was:<[]>
[ERROR]   a_session_api_route_carries_the_strict_policy:43 Response header 'Content-Security-Policy' expected:<[…]> but was:<[]>
[ERROR]   the_anonymous_login_redirect_carries_the_strict_policy:60 Response header 'Content-Security-Policy' expected:<[…]> but was:<[]>
[ERROR]   the_api_reference_alone_allows_inline_script:51 Response header 'Content-Security-Policy' expected:<[…'unsafe-inline'…]> but was:<[]>
```

The first RED run of the login-redirect case got 401 instead of a redirect,
because MockMvc sends no `Accept: text/html`. The test was given a browser's
`Accept` header while still RED, before any implementation, and then failed on
the missing header like the other three.

## Gates (one fresh run, `05bcd037`)

```console
$ ./mvnw clean verify
[INFO] Tests run: 997, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  27 passed (27)
[INFO]       Tests  208 passed (208)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:23 min
  (ContentSecurityPolicyTests 4/4, ConfigSurfaceBudgetTests 1/1 — "configuration leaves: 109",
   unchanged; ContextBudgetTests 1/1 — no new context)

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  17 passed (17)
      Tests  88 passed (88)
  (first attempt: 5 files "Failed to fetch dynamically imported module" — the known harness
   flake; green after rm -rf node_modules/.cache/storybook node_modules/.vite. No file under
   src/main/frontend/src/ is changed by this change.)

$ (cd src/main/frontend && pnpm e2e)
  25 passed (1.4m)
  (24 existing tests, each now failing on any policy violation, plus
   the_portal_and_the_api_reference_load_under_the_content_security_policy)

$ reqstool status local -p docs/reqstool
325/325 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.41 seconds
```

## Scenario mapping (acceptance.md)

| # | Verified by | Result |
|---|---|---|
| A1, N3 | `a_portal_route_carries_the_strict_policy_and_keeps_the_other_default_headers` | pass |
| A2 | `a_session_api_route_carries_the_strict_policy` | pass |
| A3 | `the_api_reference_alone_allows_inline_script` | pass |
| A4 | `the_anonymous_login_redirect_carries_the_strict_policy` | pass |
| A5 | the e2e `afterEach` violation guard, across 25 tests | 0 violations |
| A6 | `the_portal_and_the_api_reference_load_under_the_content_security_policy` | pass; every route's header asserted, `/docs` rendered its heading, and no off-origin request |
| N1 | A1, A2 and A4 compare exact strings; mutant M1 | killed |
| N2 | diff touches `webChain` only; the full suite is green | pass |
| N4 | ConfigSurfaceBudgetTests 109 (unchanged), ContextBudgetTests green | pass |
| N5 | e2e `signing_out_ends_the_session` and every login, under the policy | pass |

## Mutation (manual, `mutants.sh`)

```console
M1-portal-allows-inline-script killed
M2-portal-policy-dropped killed
M3-reference-path-hardcoded killed
M4-reference-gets-strict-policy killed
M5-no-policy-on-login-redirect killed
all mutants killed
```

## Negative controls for the browser guard

- **An external font refused.** The whole e2e flow was re-run with
  `SCALAR_WITHDEFAULTFONTS=true` and
  `-g the_portal_and_the_api_reference`. **1 failed**, on two counts:
  - 14 `requests that left the gateway's origin` (`https://fonts.scalar.com/*.woff2`);
  - 14 `content security policy violations` of the form "Loading the font
    'https://fonts.scalar.com/inter-latin.woff2' violates the following Content
    Security Policy directive: "font-src 'self'"".

  This shows the console guard reaches its failure path, that the off-origin
  check does too, and that the font switch is needed.
- **Inline script refused.** The jar was rebuilt with the API reference matcher
  pointed at `/nowhere/**`, so `/docs` got the strict policy, and the same test
  was run. **1 failed**:
  - "Executing inline script violates the following Content Security Policy
    directive 'script-src 'self''";
  - the `/docs` header assertion.

  The source was restored with `git checkout`, and `git diff --exit-code` was
  clean. This shows that the guard catches a script violation, and that the
  Scalar page cannot run without `'unsafe-inline'`.

The portal itself raised no violation under `script-src 'self'` in any of the
25 tests. There was nothing to park.

## Layers skipped, and why

- **Property-based tests:** not applicable. The change is two fixed strings
  chosen by one path matcher, with no input space to generate over.
- **Changed-line coverage tool:** not run as a separate layer. Every changed
  Java line runs on each web-chain request, which `ContentSecurityPolicyTests`
  makes, and the mutants cover each branch of the matcher.
- **The escape-hatch branch:** not tested separately, because doing so would
  need a second Spring context. The header configuration runs before that
  branch's early return, and mutant M5 shows that removing it is caught.

## Known limits

- The browser guard reads Chromium's console wording ("Content Security
  Policy"). A browser that phrased violations differently would pass silently.
  The suite runs Chromium only.
- `connect-src 'self'` assumes that the "Test request" client of the API
  reference calls the servers named in the OpenAPI document. Those are
  generated from the request, so they are the gateway's own origin.
