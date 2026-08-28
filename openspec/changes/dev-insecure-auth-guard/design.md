# Design: dev-insecure-auth-guard

## Context

`SecurityConfig.webChain` has one branch that, when
`skills-gateway.dev-insecure-auth` is true, permits every request on the web
chain and installs a filter that authenticates the caller as `dev` with
`ROLE_USER`. The git facade (`/git/**`) and the publish chain are unaffected —
they authenticate with PATs on every request — and the inbound webhook keeps its
HMAC check. So the blast radius is precisely the web surface: the whole admin
API, all actuator endpoints, and the API reference at `/docs`.

The flag is opt-in and defaults to false, and the branch logs a `WARN` on
startup. Both are correct and neither is a control that acts.

## Goals / Non-goals

- **Goal**: make the flag fail the start where it is obviously misplaced.
- **Goal**: a refusal that names what it decided on and how to resolve it.
- **Non-goal**: detecting "production" in general. That is not decidable from
  configuration this application has, and a guard that pretends otherwise would
  either refuse laptops or lull operators.
- **Non-goal**: changing the flag's default, its semantics, or any filter chain.

## Decision 1 — the signal is a configured identity provider

The escape hatch exists for exactly one situation: a developer with no identity
provider to log in to (`guides/local-development.md`, "Without one — the escape
hatch"). A deployment that has configured an identity provider has therefore
contradicted itself — the login it wired up is the login the flag switches off.
It is also close to a *necessary* condition for a real deployment, because a
gateway with no identity provider cannot log a single user in, so almost every
accidental deployment of the flag is caught.

`application.yaml` makes this cheap to detect, because the registration must
exist at AOT build time and therefore ships filled with placeholders:

| Setting | Shipped placeholder |
| --- | --- |
| `…registration.idp.client-id` | `change-me` |
| `…provider.idp.authorization-uri` | `https://idp.invalid/authorize` |
| `…provider.idp.token-uri` | `https://idp.invalid/token` |
| `…provider.idp.jwk-set-uri` | `https://idp.invalid/jwks` |
| `skills-gateway.oidc.issuer` | _(unset)_ |

Refusal happens when the flag is on and any of these has moved off its
placeholder. The guard reads the registrations from the
`ClientRegistrationRepository` Spring Boot actually built rather than from raw
property names, so a deployment that names its registration something other than
`idp`, or configures it by `issuer-uri` discovery, is judged the same way.

A second test asserts that the two placeholder constants the guard trusts are
the strings `application.yaml` really ships. Without it, a rename in the YAML
would silently turn a real default into a "placeholder" and the guard would stop
refusing anything — the classic gate that is green because it stopped measuring.

## Decision 2 — signals deliberately rejected

Each of these was considered and would refuse a documented local loop:

- **Non-loopback bind address.** `server.address` is unset in this application,
  on a laptop and in the Helm chart alike, so Spring binds `0.0.0.0` everywhere.
  "Non-loopback" is therefore *every* local run, including
  `guides/local-development.md`'s `./mvnw spring-boot:run` and the Compose loop.
  Rejected: it would break local development outright.
- **Datasource host not local.** The documented Compose loop points
  `SPRING_DATASOURCE_URL` at the host `postgres`, and the e2e harness at
  `localhost:5433`. A "not localhost" rule refuses the first; a rule that
  accepts anything a developer might name is not a rule. Rejected.
- **Active Spring profile.** The application ships only an `observability`
  profile, which says nothing about deployment. Rejected: it would require
  inventing a convention and then trusting operators to follow it — the same
  trust the flag's default already asks for.
- **Kubernetes environment markers** (`KUBERNETES_SERVICE_HOST`). Real, but it
  only catches in-cluster runs, and a developer running the gateway inside a
  local kind cluster to reproduce a chart bug would be refused for no reason.
  Rejected as the narrower and noisier of the two options; the identity-provider
  signal already covers the cluster case, because a cluster with no IdP has no
  users.

The residual gap is stated in the docs rather than papered over: a deployment
with no identity provider at all is indistinguishable from a laptop, so the
guard is defence in depth and not a substitute for keeping the flag out of
deployed configuration.

## Decision 3 — no opt-out property

An override would be a flag whose only purpose is to switch off the check on a
flag, and it would become the copy-pasted line that makes the guard ornamental.
The way out is always available and always the right one: stop setting
`dev-insecure-auth`.

## Decision 4 — refuse in a bean constructor

The guard is a `@Component` in `dev.skillsgateway.server.auth` that throws
`IllegalStateException` from its constructor. Context refresh fails, the
application exits non-zero, and the message is the last thing printed — the
behaviour an operator expects from a misconfiguration. It takes the client
registrations through an `ObjectProvider`, so a context assembled without the
OAuth2 client (any narrower test slice) degrades to "no registrations" rather
than to a missing-bean failure.

## Compatibility

- A deployment with the flag off — every default one — is untouched: the guard
  returns immediately without looking at anything.
- `DevAuthTests` (the escape hatch's own test) boots with the flag on and the
  shipped placeholders, which is exactly the local loop; it now autowires the
  guard, so the guard being absent or having refused fails that test.
- The e2e harness configures a real IdP (`SGW_OIDC_CLIENT_ID=e2e-client`, a
  mock provider on `localhost:9090`) and does **not** set the flag, so it is
  unaffected. Were the two ever combined, the guard would be right to refuse.
- `AbstractGatewayTest` sets a non-placeholder client id (`test`) and never sets
  the flag, so every SVC test context is unaffected.

## Risks

- **A deployment that today runs with both the flag and an IdP stops starting.**
  Accepted, and the point: such a gateway is serving its entire admin API
  unauthenticated. The refusal names the fix.
- **Placeholder drift** — mitigated by the test in Decision 1.

## NOTICE

Not a behaviour change, but shipped here because it is the same
"what does this project state about itself" pass. The file names the project,
the copyright ("The Skills Gateway Authors" — no holder is asserted anywhere in
the repository, so none is invented), the Apache-2.0 statement, and points at
the CycloneDX SBOM the build already generates and the gateway already serves at
`/actuator/sbom` as the authoritative third-party inventory. Deliberately no
hand-maintained dependency list: it would be wrong within one Renovate PR, and a
stale NOTICE is worse than a NOTICE that says where the truth lives.
