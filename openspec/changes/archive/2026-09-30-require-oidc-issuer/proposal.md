# Proposal: require-oidc-issuer

## Why

The browser login compares an identity token's issuer only when
`skills-gateway.oidc.issuer` is set, and when it is not the gateway logs a
warning and starts. Against an identity provider whose authorization endpoint
serves many tenants with one key set, a token issued to any tenant for this
client id then verifies, so the issuer is the tenant boundary. Every other
credential boundary in the gateway refuses to start rather than defaulting
(the development escape hatch guard, the role bootstrap guard, machine
credential lifetimes, and the facade's own identity-provider bearer path on
exactly this condition). This one warns, and the warning's only symptom is
"tokens accepted too broadly", which nobody observes. Issue #546; the
2026-09-29 review, finding 1.

## What Changes

- **BREAKING** The gateway refuses to start when an identity provider is
  configured and `skills-gateway.oidc.issuer` is not. "Configured" is what the
  development escape hatch guard already recognises: a client id other than
  the shipped placeholder, or a provider endpoint off the placeholder host.
  The refusal names the property and the signals it was decided on.
- Unchanged: the unconfigured state (shipped placeholders, no issuer) starts,
  so a bare local run still works; the development escape hatch with no
  issuer starts; a provider with a pinned issuer starts and validates as
  before.
- No opt-out property. Every OIDC provider has an issuer, so there is no
  legitimate unchecked deployment to carve out.
- Helm chart: `oidc.issuer` becomes `required`; `helm template` fails without
  it.
- Harnesses that configure the mock provider (the portal e2e runner and the
  container smoke test) pin its issuer.
- Docs: configuration reference, trust boundaries, the Kubernetes and
  Compose deployment guides, local development and identity providers say the
  issuer is mandatory once a provider is configured.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `auth`: GW_AUTH_0017 — Enterprise identity-provider session integrity —
  changes from "warn at startup when the issuer is unconfigured" to "refuse
  to start when an identity provider is configured and the issuer is not".
  New verification case SVC_GW_AUTH_0017.2 covers the four startup cases.

## Impact

- `SecurityConfig` loses the ID-token decoder bean; it moves, with the
  refusal, to a small configuration class of its own so it can be exercised
  as a real context without the whole security configuration.
- Test contexts that configure a non-placeholder client id (the shared
  context, the forwarded-headers and session-cookie contexts) pin a
  placeholder issuer. Each list changes by the same line, so no new Spring
  context is created.
- Deployments that configure a provider without an issuer stop starting on
  upgrade. Pre-1.0, declared as a breaking change in the commit footer.
- No REST API, OpenAPI, schema or configuration-leaf change.
