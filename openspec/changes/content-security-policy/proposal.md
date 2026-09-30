# Proposal: content-security-policy

## Why

The web surface sends no `Content-Security-Policy` (#550, from the 2026-09-29
review). The portal has no HTML pipeline, so this is defence in depth rather than
a fix. It is also cheap. The portal loads nothing external, its fonts are
bundled, and the API reference is a webjar, so a `default-src 'self'` policy
is within reach. If a script injection is ever found, the policy is what stops
it from loading script from elsewhere or sending data off-site.

## What Changes

- **The web chain declares a content security policy on every response.** The
  web chain serves the portal, its API under a session, the login flow and the
  API reference. The policy allows only same-origin script, connections, fonts,
  frames' ancestors and form targets:
  `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`.
- **The API reference gets the same policy with inline script allowed.** Scalar's
  page at `scalar.path` (`/docs`) initialises itself from an inline `<script>`, so
  that page alone gets `script-src 'self' 'unsafe-inline'`. The portal never gets
  it.
- **The API reference stops fetching external fonts.** `scalar.with-default-fonts:
  false` is set in `application.yaml`, so the page makes no request to
  `fonts.scalar.com` and makes no request the policy would refuse.
- The git facade, publication, revocation-check, hooks and machine API chains are
  unchanged. They serve no documents.
- No new configuration leaf. The Scalar property is third-party and outside
  `SkillsGatewayProperties`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `auth`: adds GW_AUTH_0052 — The web surface declares a content security policy.

## Impact

- **Server:** `SecurityConfig.webChain` gains a headers configuration, applied in
  both the configured and the development-escape-hatch branches.
- **Configuration:** `application.yaml` sets `scalar.with-default-fonts: false`.
  `ConfigSurfaceBudgetTests` counts only `SkillsGatewayProperties`, so its number
  does not change.
- **Tests:** a MockMvc suite in the shared context (no new Spring context)
  asserts the header on a portal route, an API route and the API reference. The
  packaged jar is then driven in a real browser across every portal page and the
  API reference to check for policy violations.
- **Docs:** `trust-boundaries.md` ("Browser hardening") and `configuration.md`
  (the Scalar property).
- **reqstool:** GW_AUTH_0052 and SVC_GW_AUTH_0052 are added.
- **API contract:** unchanged.
