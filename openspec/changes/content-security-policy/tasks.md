# Tasks: content-security-policy

These are security headers next to the trust boundary, so the work follows
`.claude/skills/old-coder`. The header test is written first and shown failing
against the unchanged `SecurityConfig`. That failure is recorded in
`evidence.md`.

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_AUTH_0052 — The web surface declares a content security policy, and SVC_GW_AUTH_0052, to `docs/reqstool/`. Verify the id is still free in `requirements.yml` and in `openspec/changes/`, and that `reqstool status` lists both

## 2. Tests first (shown failing)

- [x] 2.1 `ContentSecurityPolicyTests` (extends `AbstractGatewayTest`, @SVCs SVC_GW_AUTH_0052) asserts the exact strict policy on a portal route (`/marketplaces`) and a session API route (`/api/v1/marketplaces`), the exact API-reference policy on `/docs`, and the strict policy on the unauthenticated login redirect. Run it against the unchanged code and record the failure
- [x] 2.2 `e2e/portal.spec.ts`: a per-test guard that fails any test during which the browser raised a policy violation, plus `the_portal_and_the_api_reference_load_under_the_content_security_policy` (@SVCs SVC_GW_AUTH_0052), which visits every SPA route and `/docs`

## 3. Implementation

- [x] 3.1 `SecurityConfig.webChain`: two `DelegatingRequestMatcherHeaderWriter`s keyed by the `${scalar.path}` matcher and its negation, configured before the escape-hatch branch, with `@Requirements GW_AUTH_0052`. Verify 2.1 passes
- [x] 3.2 `application.yaml`: `scalar.with-default-fonts: false`. Verify `/docs` loads in the browser without a `fonts.scalar.com` request or a violation
- [x] 3.3 Repackage the jar, run `pnpm e2e`, and drive every portal page and dialog and `/docs` against it. Verify there are no violations; if the only fix would be `script-src 'unsafe-inline'` on the portal, park the change instead

## 4. Docs

- [x] 4.1 `docs/manual/…/trust-boundaries.md`: a short "Browser hardening" paragraph (the two policies, which chain, why the API reference differs)
- [x] 4.2 `configuration.md`: `scalar.with-default-fonts` and why it is off. Verify `mkdocs build --strict`

## 5. Gates and evidence

- [ ] 5.1 Run all gates fresh after the last edit, write `evidence.md`, then archive
