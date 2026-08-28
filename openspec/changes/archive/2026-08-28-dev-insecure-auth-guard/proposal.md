# Proposal: dev-insecure-auth-guard

## Why

`skills-gateway.dev-insecure-auth=true` makes the **entire web surface**
unauthenticated — every `/api/**`, `/actuator/**` and `/docs` request — and
attributes it all to a synthetic principal named `dev`. Everything that follows
from a gateway's authorization model (delegated administration, approver
scoping, the audit ledger's attribution) is void while it is on.

Today the only thing standing between that and a deployed cluster is that the
flag defaults to false. That is the right default and it is not a control: a
copied `values.yaml`, a `SKILLS_GATEWAY_DEV_INSECURE_AUTH=true` inherited from
a compose file, or a laptop configuration promoted to staging all set it by
accident, and the only complaint is one `WARN` line in a log nobody reads on the
day the gateway starts. Defence in depth wants a second control that fails the
start rather than the audit.

The project also ships an Apache-2.0 `LICENSE` and declares `Apache-2.0` in its
POM, but has no `NOTICE` file. Section 4(d) of the licence makes `NOTICE` the
conventional place a redistributor looks, and its absence is a gap in a project
that is otherwise careful about its supply chain (it already generates and
serves a CycloneDX SBOM).

## What Changes

- **A startup guard on the escape hatch.** With `dev-insecure-auth` on, a
  gateway that has an identity provider configured refuses to start. "Configured"
  means any of: an OIDC client registration whose client id is not the shipped
  `change-me` placeholder; a provider endpoint (`authorization-uri`,
  `token-uri`, `jwk-set-uri`, `issuer-uri`) naming a host other than the shipped
  `idp.invalid` placeholder; or a pinned `skills-gateway.oidc.issuer`.
- **An actionable refusal.** The message names the signals it decided on and
  states both ways out — remove the flag and log in, or leave the
  identity-provider configuration unset if this really is a laptop.
- **No new configuration.** No opt-out property: an override would be a flag
  whose only purpose is to switch off the check on a flag.
- Requirement GW_0110 with SVC_GW_0110.
- **A `NOTICE` file** at the repository root: project, copyright, the Apache-2.0
  statement, and a pointer to the CycloneDX SBOM as the authoritative
  third-party inventory rather than a hand-maintained list.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `auth`: the development escape hatch gains a startup precondition — it refuses
  to start on a deployment that has an identity provider configured.

## Impact

- **DB**: none.
- **Backend**: new `DevInsecureAuthGuard` in `dev.skillsgateway.server.auth`;
  no change to `SecurityConfig`'s chains, to `SkillsGatewayProperties`, or to
  any request path. The guard runs once, at context startup.
- **API**: none.
- **Portal**: none.
- **Ops**: a deployment that already sets `dev-insecure-auth: true` *and*
  configures an identity provider will stop starting on upgrade. That is the
  point, and such a deployment is already unauthenticated to the internet.
- **Repo**: `NOTICE` added; `README.md` license section points at it.
- **Docs** (same PR): `reference/configuration.md`,
  `guides/local-development.md`, `concepts/trust-boundaries.md`.
- **Trust boundary**: this hardens the web-surface authentication boundary →
  old-coder discipline, with the guard's negative cases (the local loop, and the
  flag off) tested alongside the refusals.
- **Declarative estate obligation**: nothing to add. The guard introduces no
  API-managed runtime state and no new grantable role; it is a startup
  precondition on configuration that already exists.
