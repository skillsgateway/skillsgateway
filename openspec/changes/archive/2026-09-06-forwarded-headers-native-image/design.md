# Design: forwarded-headers-native-image

## Context

See proposal.md — Why. The facts the design rests on, read from Spring Boot
4.1.1's sources rather than remembered:

- `ServletWebServerConfiguration.forwardedHeaderFilter()` is
  `@ConditionalOnProperty(server.forward-headers-strategy=framework)` and
  `@ConditionalOnMissingFilterBean(ForwardedHeaderFilter.class)`. Spring
  Boot's native-image documentation lists "properties that change if a bean is
  created (`@ConditionalOnProperty`, `.enabled` properties)" as unsupported:
  the condition is evaluated at build time, when nothing sets the property.
- `TomcatWebServerFactoryCustomizer.customizeRemoteIpValve()` reads
  `ServerProperties.getForwardHeadersStrategy()` at runtime — the customizer
  is registered on class and web-application-type conditions only, both true
  at build time — and installs Tomcat's `RemoteIpValve` for `native`, with
  `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Port` and the default
  internal-proxy ranges (`10/8`, `172.16/12`, `192.168/16`, `100.64/10`,
  loopback, IPv6 counterparts).
- When the strategy is unset, `getOrDeduceUseForwardHeaders()` returns
  `CloudPlatform.getActive(environment).isUsingForwardHeaders()`: `native`
  on Kubernetes (`KUBERNETES_SERVICE_HOST`/`_PORT`) and on ECS
  (`AWS_EXECUTION_ENV=AWS_ECS*`, since Boot 4.0), among others; nothing on
  plain Docker.
- The gateway has no IP-based access control. `request.getRemoteAddr()` is
  recorded on the fetch ledger and nothing else reads it.

## Goals / Non-Goals

**Goals:**

- `server.forward-headers-strategy` means the same thing on the JVM jar and on
  the native image, for every value.
- The trust decision is explicit and documented; the chart makes it for the
  deployment shape it ships.
- The issue's option 1 is answered with evidence, not reasoning alone.

**Non-Goals:**

- A gateway-specific property. Spring's property already carries the three
  meanings operators know and the docs already name it; a second knob would
  be a second thing to get wrong.
- Trusted-proxy address filtering for the `framework` strategy. Tomcat's valve
  already does that for `native`; Spring's filter deliberately does not, and
  re-implementing the valve in a filter is not a change this issue asks for.
- Changing the default. See Decision 2.

## Decisions

### Decision 1 — an unconditional `FilterRegistrationBean`, enabled at runtime

`ForwardedHeadersConfig` declares
`FilterRegistrationBean<ForwardedHeaderFilter>` with no condition, and calls
`setEnabled(strategy == FRAMEWORK)` from the runtime-bound `ServerProperties`.
AOT freezes bean *definitions*, not the code a `@Bean` method runs, so the
registration is compiled in on every packaging and the decision is made from
the deployed environment. Order and dispatcher types are Boot's own.

Alternatives considered:

- **Bake `server.forward-headers-strategy: framework` into
  `application.yaml`** (issue option 2). Makes the condition true at build
  time, and trusts every peer's headers by default on every packaging, which
  is a posture change nobody asked for. Rejected.
- **Document `native` and leave `framework` broken** (issue option 1 as a
  docs-only fix). `native` does work — verified below — but a documented
  Spring value that is silently inert on the released image is a trap that
  will be re-added by the next reader of Spring's docs. Rejected as
  incomplete; `native` is still the recommended value.
- **Key the bean on a new `skills-gateway.*` boolean.** Cleaner in isolation,
  but then `SERVER_FORWARDHEADERSSTRATEGY=framework` — the value Spring's
  documentation, this project's previous guides, and every search result
  name — would remain inert, now by design. Rejected.

### Decision 2 — the default stays off; the chart says `native`

Trusting `X-Forwarded-*` is a trust decision the gateway cannot make for
itself: a client that reaches the listener directly can send any scheme and
host, and Spring's filter believes it. The whirlylabs article the issue links
makes the same point about `X-Forwarded-For` and IP filters. So the
application default is Spring's — unset, meaning `none` unless a container
platform is detected — and the JVM jar and native image behave identically.

The Helm chart is a different matter: it ships the Ingress, so it knows a
proxy is there and where it sits — inside the cluster's private ranges. It
therefore names `forwardHeadersStrategy: native`, the value Spring Boot was
already deducing for it, and refuses any value Spring Boot does not know. The
chart's own philosophy applies (the storage backend is "named, never
inferred"): a deployment that works because of a deduction is one nobody can
read. `native` over `framework` for the default because Tomcat's valve
believes only peers in the internal ranges, which is exactly the ingress
controller and nobody else, and because it also fixes the remote address the
fetch ledger records.

### Decision 3 — `SGW_OIDC_REDIRECT_URI` is the escape hatch, not the fix

The placeholder `${SGW_OIDC_REDIRECT_URI:{baseUrl}/login/oauth2/code/idp}`
resolves to today's template when the variable is unset (Spring's placeholder
parser balances the inner braces — proven by `ForwardedHeadersRedirectUriTests`, whose
context leaves `redirect-uri` to `application.yaml` rather than overriding
it as the shared fixture does). Set, it replaces the derived URI outright and
no header is consulted. The docs say plainly that it fixes the login and
nothing else: every other URL built from the request still names the
container. The chart passes it through as `oidc.redirectUri`, empty by
default, in the same conditional shape as `oidc.issuer`. It is not added to
`compose.yaml`: an empty-string default there would resolve the placeholder to
an empty redirect URI and stop the registration from building.

### Decision 4 — the native workflow's smoke test asserts the fix

The JVM test suite cannot observe this defect: on the JVM, Boot's own
registration works. The one packaging on which it shows is the native binary,
and the only place the project builds one is `native.yml`. Its smoke test now
runs the container with `framework` and asserts the forwarded scheme and host
reach the authorization redirect. Without this, the next AOT-related
regression would again ship to consumers before anyone noticed.

## Option 1 — what was verified

Built with the project's own `-Pnative` profile (GraalVM CE 25.2.4) and run
against PostgreSQL, before and after the fix. The probe is the authorization
redirect's `redirect_uri` for a request carrying `X-Forwarded-Proto: https`
and `X-Forwarded-Host: gateway.example.com`; full transcripts in
`evidence.md`.

| Strategy | Pre-fix native binary | Fixed native binary |
| --- | --- | --- |
| unset | ignored | ignored |
| `none` | — | ignored |
| `native` | **honoured** | honoured |
| `framework` | **ignored** (the issue) | honoured |
| unset, `AWS_EXECUTION_ENV=AWS_ECS_FARGATE` | honoured (deduced `native`) | honoured |
| `framework`, `AWS_EXECUTION_ENV=AWS_ECS_FARGATE` | — | honoured |
| unset, `KUBERNETES_SERVICE_HOST`/`_PORT` | — | honoured (deduced `native`) |
| `SGW_OIDC_REDIRECT_URI` set | — | the stated URI, headers or not |

So option 1 holds: `native` already worked on the released image, and on ECS
and Kubernetes it was already deduced when the variable was left unset. The
issue's deployment most plausibly failed because the guide's
`SERVER_FORWARDHEADERSSTRATEGY=framework` both switched that deduction off
and did nothing itself — which is why the docs now say to name `native`, and
why `framework` had to be made real rather than merely un-recommended.

## Risks / Trade-offs

- [An operator sets `framework` on a listener a client can reach directly] →
  Documented as the operator's decision, with the conditions under which it is
  safe; `native` is the recommended and chart-default value and believes only
  private-range peers. Same exposure as any Spring Boot application with the
  property set.
- [A chart user who followed the old guide keeps `extraEnv:
  SERVER_FORWARDHEADERSSTRATEGY: framework`] → It now works rather than being
  inert, so the upgrade fixes their login either way; the guide tells them to
  remove it because it overrides the chart's value.
- [A chart user relying on Boot's Kubernetes deduction] → The chart now states
  `native` explicitly, which is what they were already running. No behaviour
  change.
- [`@ConditionalOnMissingFilterBean` fails to see the bean and the JVM runs
  two filters] → Asserted directly: exactly one `ForwardedHeaderFilter`
  registration under `framework`.
- [The `Unset` posture test is environment-dependent] → Pinned with
  `spring.main.cloud-platform=none` so "unset" means unset on any CI host.

## Migration Plan

No data change. Rolling the image forward is the migration; the chart default
changes a pod's environment from "deduced" to "stated" with the same effect.
Rollback is rolling the image back, at which point `framework` is inert again
and `native` still works.
