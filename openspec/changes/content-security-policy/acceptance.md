# Acceptance: content-security-policy (Tier 3, security header)

Spec approval: **not obtained (autonomous run)**. The owner delegated the
decisions for #550 in advance. The policies below are the ones that
delegation names, and this file is what the owner reviews after the fact.

The strict policy (S) and the API-reference policy (R) are defined as follows.

- **S:** `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`
- **R:** S, with `script-src 'self' 'unsafe-inline'`

## Scenarios

| # | Given / When | Then | Verified by |
|---|---|---|---|
| A1 | An authenticated session GETs a portal route (`/marketplaces`) | `Content-Security-Policy` is exactly S, sent once | `ContentSecurityPolicyTests` |
| A2 | An authenticated session GETs a session API route (`/api/v1/marketplaces`) | exactly S | `ContentSecurityPolicyTests` |
| A3 | An authenticated session GETs the API reference (`/docs`) | exactly R, never S, with 200 | `ContentSecurityPolicyTests` |
| A4 | An anonymous browser GETs `/` and is redirected to login | exactly S on the redirect | `ContentSecurityPolicyTests` |
| A5 | The acceptance suite drives the packaged jar in Chromium | no `securitypolicyviolation` in any test | `e2e/portal.spec.ts` guard |
| A6 | A browser visits every SPA route and `/docs` | each renders, with no violation, and `/docs` requests nothing from `fonts.scalar.com` | `the_portal_and_the_api_reference_load_under_the_content_security_policy` |

## Must not

| # | Invariant | Verified by |
|---|---|---|
| N1 | The portal never receives `'unsafe-inline'` or `'unsafe-eval'` in `script-src` | A1, A2 and A4 assert the exact string |
| N2 | The git, publication, revocation-check, hooks and machine chains are unchanged | They are untouched in the diff; the existing suites stay green |
| N3 | Spring Security's other default headers survive (`X-Content-Type-Options`, `X-Frame-Options`) | `ContentSecurityPolicyTests` asserts `nosniff` beside the policy |
| N4 | No new configuration leaf and no new Spring context | `ConfigSurfaceBudgetTests` and `ContextBudgetTests` are unchanged and green |
| N5 | Sign-out and login still work | e2e `signing_out_ends_the_session` and every login |

## Failure model

- **Wrong page gets the loose policy.** If the matcher were too broad, the portal
  would run with inline script allowed. A1, A2 and A4 fail on any `'unsafe-inline'`
  in S.
- **The API reference gets the strict policy.** It renders blank. A3 and A6 catch
  this.
- **Two policies written on one response.** Browsers would apply both, which is
  the stricter intersection, so the API reference would break. A3 asserts a
  single header value.
- **The escape-hatch branch has no policy.** This is covered by construction:
  the header configuration precedes the early return. It is not tested
  separately, because that needs a second context.
- **A runtime resource the policy refuses.** A5 and A6 catch it in a real
  browser.
