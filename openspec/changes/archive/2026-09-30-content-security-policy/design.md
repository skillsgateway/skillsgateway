# Design: content-security-policy

## Context

`SecurityConfig` has six filter chains. Five of them never serve a document a
browser renders:

| Chain | Matcher | Serves |
|---|---|---|
| `gitChain` | `/git/**` | git smart-HTTP |
| `publishChain` | `/publish/**` | git push |
| `revocationCheckChain` | `/status/**` | JSON |
| `hooksChain` | `/hooks/**` | webhook acknowledgements |
| `machineApiChain` | `/api/**` with a bearer header | JSON |
| `webChain` | everything else | the portal, `/api/**` under a session, the login flow, the API reference, actuator probes |

A CSP header on a JSON or git response does nothing. The policy therefore goes
on `webChain` only.

What the web chain serves:

- **The portal.** This is a Vite build. `index.html` has no inline script, the
  fonts are bundled through `@fontsource-variable/geist`, and every request goes
  to the same origin. At run time, sonner and Base UI inject `<style>` elements
  and set `style` attributes.
- **The API reference.** The Scalar webjar `com.scalar.maven:scalar:0.4.4`
  serves it at `${scalar.path}` (`/docs`), and its bundle at
  `${scalar.path}/scalar.js`. The page template ends in an inline
  `<script>Scalar.createApiReference('#app', {...})</script>`, whose argument is
  the configuration serialised by the webjar's controller. The bundle injects
  `<style>`. With `withDefaultFonts` left at its default, it also loads
  `@font-face` sources from `https://fonts.scalar.com`.
- **The MkDocs manual is not served by the gateway.** It is published to GitHub
  Pages, and is out of scope here.

## Goals / Non-Goals

**Goals:** the policy is on every web-chain response, in both the configured
and the development-escape-hatch branches, and the portal runs under it with
`script-src 'self'`.

**Non-Goals:**

- Nonces and hashes.
- `report-uri` / `report-to`. A reporting endpoint would be a new surface, and
  would add configuration to say where reports go.
- Changing the other chains.
- A configurable policy.

## Decisions

1. **Two fixed policies, chosen per request by a path matcher.** Each policy is a
   `ContentSecurityPolicyHeaderWriter` wrapped in a
   `DelegatingRequestMatcherHeaderWriter`. One writer's matcher is the API
   reference's path. The other's is the negation of that matcher, so exactly
   one of them writes on any request. The alternative, one writer with a
   conditional inside it, is shorter by a line but less clear than two
   declarations.

2. **The API reference matcher comes from `scalar.path`, not a literal
   `/docs`.** The webjar maps its controller with `${scalar.path:/scalar}`. The
   matcher reads the same property, with the same default, so it always names
   the page the webjar actually serves. If a deployment moves the reference, it
   does not lose the page to the strict policy.

   The pattern is `${scalar.path}/**`. That covers the page and `scalar.js`, and
   the policy on a JavaScript response has no effect.

3. **`script-src 'unsafe-inline'` only on the API reference.** The only
   alternative that keeps script strict there is a hash of the inline script.
   Its content includes the serialised configuration, so any change to a
   `scalar.*` property, or to a webjar upgrade, would break the page silently.
   The page renders the gateway's own OpenAPI document and no user content.

4. **`style-src 'unsafe-inline'` on the portal.** sonner 2.x and Base UI inject
   styles at run time, and inline CSS is not the injection vector a CSP is
   bought for here. Scripts stay `'self'`.

5. **`scalar.with-default-fonts: false`.** This is a third-party property
   (present in the webjar's `spring-configuration-metadata.json`). It stops the
   page from requesting `fonts.scalar.com`. That is the only external fetch the
   page makes in normal use, and `font-src 'self'` would refuse it and log a
   violation on every load. `ConfigSurfaceBudgetTests` walks
   `SkillsGatewayProperties` only, so its count does not change.

6. **`form-action 'self'`.** The portal submits no native form. Its forms are
   React handlers that call `fetch`, and sign-out is a `fetch` `POST` to
   `/logout` followed by `location.assign("/")`. Login is a top-level
   navigation to `/oauth2/authorization/idp`, which redirects to the provider.
   `form-action` governs neither. The IdP's own login page is a different
   origin and carries its own policy.

7. **Applied before the escape-hatch branch.** `webChain` returns early when
   `dev-insecure-auth` is on. The headers configuration is placed ahead of that
   branch, so the local development surface runs under the same policy as
   production. A violation shows up where developers work, not first in a
   deployment.

8. **Tests.** The Java suite `ContentSecurityPolicyTests` extends
   `AbstractGatewayTest`, so it adds no context. It asserts the exact header on
   a portal route, a session API route, the API reference, and an
   unauthenticated response that is redirected to login.

   In the browser, `e2e/portal.spec.ts` collects every policy violation raised
   during each test and fails the test if there is one. That turns the whole
   acceptance suite into a check that the policy does not break the portal. A
   dedicated test visits every portal route and the API reference.

## Risks / Trade-offs

- **A future dependency that injects inline script or loads a remote resource
  breaks under the policy.** → The e2e violation guard fails the suite on the
  first page that does it.
- **Scalar telemetry or its "try it" client connects elsewhere.** The proxy
  URL is unset, so the client calls the servers in the OpenAPI document, which
  are the gateway's own origin. → If the browser run shows another connection,
  the matching `scalar.*` switch is turned off, rather than widening
  `connect-src`.
- **A proxy in front of the gateway that sets its own CSP.** Browsers apply
  every policy they receive, so the effective policy is at least as strict as
  this one. → Documented in `trust-boundaries.md`.
