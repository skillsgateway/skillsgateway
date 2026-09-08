# Proposal: forwarded-headers-native-image

## Why

Behind a TLS-terminating proxy — the chart's own Ingress, an in-VPC load
balancer, anything that is not the container itself — every OIDC login fails:
`{baseUrl}` in the redirect URI expands from the request as the container sees
it, `http://<container>:8080`, and the identity provider refuses the mismatch
with an error that points at its own app registration. The documented remedy,
`server.forward-headers-strategy=framework`, is inert on the released image.
Spring Boot registers `ForwardedHeaderFilter` behind `@ConditionalOnProperty`,
which a GraalVM native image evaluates once, at build time, when the property
is unset; the bean is never compiled in and no runtime value can bring it back.
Worse, setting the value explicitly also switches off the `native` strategy
Spring Boot would otherwise have deduced on Kubernetes and ECS, so following
the guides made a deployment that might have worked fail.

Issue [#272](https://github.com/skillsgateway/skillsgateway/issues/272).

## What Changes

- **`GW_AUTH_0029 — Proxy-reported scheme and host are honoured only when
  configured, identically on every packaging`**, with SVC_GW_AUTH_0029. The gateway
  registers the `ForwardedHeaderFilter` itself, unconditionally, and enables it
  at runtime when `server.forward-headers-strategy` is `framework`. Spring's
  property keeps Spring's meanings — `none`, `native`, `framework` — and now
  means the same on the JVM jar and the native image. Boot's own registration
  backs off, so the JVM never carries two filters.
- **Default posture unchanged: off.** The gateway cannot tell a proxy's header
  from a client's, so the trust decision stays with the operator. Spring
  Boot's runtime deduction (`native` on a detected container platform) is kept
  and documented rather than replaced.
- **The Helm chart names the strategy**: `forwardHeadersStrategy: native` by
  default, rendered to `SERVER_FORWARDHEADERSSTRATEGY`, and refused at render
  for any value Spring Boot does not know. The chart ships the Ingress, so it
  owns the decision, and `native` trusts exactly the private ranges an ingress
  controller lives in. Also `oidc.redirectUri`, passed through when set.
- **`SGW_OIDC_REDIRECT_URI`**: the one OIDC setting with no environment
  placeholder gets one, in the idiom of its neighbours, so the escape hatch that
  trusts no header at all is reachable without knowing the raw Spring property.
- **The native workflow's smoke test asserts the fix on the real binary**: the
  container runs with `framework` and the authorization redirect must name the
  forwarded scheme and host. The JVM suite cannot see this defect; that step
  can.
- **Docs corrected** in the same PR: both deployment guides, the configuration
  reference, and the identity-provider guide.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `auth`: the OIDC login gains a stated contract for how the redirect URI
  learns the externally visible scheme and host, and an absolute override.

## Impact

- **DB**: none.
- **Backend**: new `ForwardedHeadersConfig` in `dev.skillsgateway.server.config`
  (one `FilterRegistrationBean`); `application.yaml` gains the
  `SGW_OIDC_REDIRECT_URI` placeholder. No request path, filter chain or
  controller changes.
- **API**: none.
- **Portal**: none.
- **Chart**: new `forwardHeadersStrategy` (default `native`) and
  `oidc.redirectUri` values; two environment variables in the Deployment; a
  render-time gate in `_helpers.tpl`. An existing install that relied on
  Spring Boot's Kubernetes deduction was already running `native`, so its pod
  now states what it was already doing. An install that followed the previous
  guide's `extraEnv` advice must remove that entry — it overrides the chart's
  and was the broken configuration.
- **CI**: `native.yml`'s smoke test grows one assertion.
- **Docs**: `guides/deploying-without-kubernetes.md` ("Running behind a
  proxy"), `guides/deploying-on-kubernetes.md` (the `extraEnv` warning becomes
  the chart value), `reference/configuration.md` (new "Forwarded headers"
  section, `SGW_OIDC_REDIRECT_URI` in the OIDC table),
  `guides/identity-providers.md`.
- **Trust boundary**: this touches the web surface's authentication flow (the
  redirect URI the login is built on) and decides whom the gateway believes
  about the request's origin → negative cases are tested (headers ignored when
  unset; the absolute URI immune to headers) and the evidence report records
  RED on the real native binary.
- **Declarative estate obligation**: nothing to add. No API-managed runtime
  state and no grantable role is introduced; this is deployment configuration.
