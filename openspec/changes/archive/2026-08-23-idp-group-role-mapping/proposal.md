# Proposal: idp-group-role-mapping

## Why

The role model (GW_0068–GW_0071) grants per principal: every approver, every
auditor, every admin beyond the configuration escape hatch is a row somebody
had to POST. In an enterprise identity provider the unit that already exists,
already has an owner, and is already governed by a joiner/mover/leaver process
is the **group**. Reproducing that membership as gateway grant rows means a
second source of truth that drifts the moment somebody changes teams — and it
means the gateway cannot be stood up by configuration alone, which is what
GW_0083–GW_0087 (declarative estate) otherwise achieves.

Tracked as issue
[#66](https://github.com/skillsgateway/skillsgateway/issues/66).

The target deployment sharpens two constraints. First, **one shared app
registration serves several platform services**, so the claim values the
gateway sees are the *organisation's* group ids and app-role values, not
gateway-named roles — mapping must be by claim value, never by convention.
Second, authentication stays in the application (no load-balancer OIDC, no
auth-proxy sidecar), so the gateway's own login is the integration point.

Two configuration gaps block that integration today, and a guide that did not
close them would be a guide that does not work:

- The principal name is the OIDC `sub`. On a shared registration `sub` is an
  opaque pairwise identifier, so grants, `roles.admins` and every ledger row
  would name a string no human can resolve back to a person. The provider's
  `user-name-attribute` is not settable — `application.yaml` hard-codes the
  provider block and `reference/configuration.md` documents the scope as
  "fixed, not intended for override".
- The ID token's `iss` is never compared to an expected issuer, because
  `spring.security.oauth2.client.provider.idp.issuer-uri` is unset. Against a
  multi-tenant authorization endpoint every tenant's tokens are signed by keys
  from the same JWKS, so the issuer *is* the tenant boundary; without it, a
  login from an unrelated tenant is indistinguishable from a legitimate one.

## What Changes

- **Claim-derived roles.** New `skills-gateway.roles.claim` (default `groups`,
  dotted paths supported for providers that nest it) and
  `skills-gateway.roles.mappings[]` of `claim-value` → `role` (+ `marketplace`
  for `approver`). A session's effective roles become the union of its
  configuration-admin entry, its stored grants, and its claim-derived roles;
  nothing about deny-by-default, approver scoping or the auditor's read-only
  guarantee changes.
- **Fail-closed edges.** A mapping with the wrong shape (an `approver` with no
  marketplace, an `admin`/`auditor` with one, an unknown role, a blank claim
  value) refuses startup rather than silently granting nothing. A credential
  that carries no OIDC claims — a personal access token on the facade, the
  `dev-insecure-auth` synthetic principal, the anonymous webhook request —
  derives no role at all.
- **Truncated claims are never silently under-privileged.** A provider that
  omits the group claim when a user has too many memberships (an overage
  indicator such as `hasgroups` or `_claim_names`) is detected: the gateway
  logs it, reports it on `/api/me`, and the session simply holds no derived
  role — visibly, not mysteriously.
- **Role provenance.** `/api/me` says where each effective role came from
  (`config`, `grant`, `claim`), so "why does this person have admin" is
  answerable from the session endpoint instead of by reading YAML.
- **Enterprise IdP configuration.** `SGW_OIDC_USER_NAME_ATTRIBUTE` (default
  `sub`, unchanged behaviour) and `SGW_OIDC_SCOPE` (default `openid`) become
  settable; a new `skills-gateway.oidc.issuer` pins the expected ID-token
  issuer and is enforced on every login, with a startup warning when a
  non-development deployment leaves it unset. Helm and compose pass both
  through.
- **A vendor-neutral identity-provider guide** with a worked Microsoft Entra ID
  walkthrough: app-registration steps, the redirect URI, the group/app-role
  claim configuration, the overage caveat, and why `preferred_username` is the
  principal attribute — including that it is a mutable attribute and what that
  means for grants keyed on it.
- Requirements GW_0098 (claim-to-role mapping), GW_0099 (truncated-claim
  detection), GW_0100 (principal attribute and issuer pinning), with
  SVC_GW_0098 / SVC_GW_0099 / SVC_GW_0100.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `admin-roles`: effective roles gain a third source — the identity provider's
  own group/app-role claims, mapped by configuration — with provenance
  reported on the session endpoint and truncated claims made visible.
- `auth`: the OIDC login gains a configurable principal-name attribute, a
  configurable scope set, and an enforced expected issuer.

## Impact

- **DB**: none. Claim-derived roles are never rows; there is nothing to
  migrate, nothing to revoke, and no new value for the `role_grants` CHECK.
- **Backend**: `SkillsGatewayProperties` (`Roles.claim`, `Roles.mappings`, new
  `Oidc` block), new `ClaimRoleMapper` in `dev.skillsgateway.server.roles`,
  `RoleService` (session-aware `effectiveRoles(Authentication)` alongside the
  existing principal-keyed `rolesOf(String)` the estate reconciler needs),
  `MeController` (provenance + claim-truncation flag), `SecurityConfig`
  (issuer-pinning `JwtDecoderFactory`), `application.yaml`.
- **API**: `/api/me` gains `source` on each effective role and a
  `claimsTruncated` flag. No other endpoint changes shape.
- **Portal**: `types.gen.ts` regenerated; `useMe()` widened. No UI change.
- **Ops**: `helm/skills-gateway` values and deployment env, `compose.yaml`,
  `compose.e2e.yaml` (the mock IdP starts issuing a `groups` claim so the
  acceptance suite exercises the real path through a real login).
- **Docs** (same PR): new `guides/identity-providers.md`;
  `guides/delegated-administration.md`, `reference/configuration.md`,
  `reference/api/roles.md`, `concepts/trust-boundaries.md`,
  `guides/declarative-estate.md`, `architecture.md`, `mkdocs.yml` nav.
- **Trust boundary**: this changes what makes a session privileged →
  old-coder Tier 3, adversarial and negative tests required.
- **Declarative estate obligation**: claim mappings are deliberately *not* an
  estate object — they are not API-managed runtime state, so there is nothing
  for the reconciler to converge (see `design.md`, Decision 6).
