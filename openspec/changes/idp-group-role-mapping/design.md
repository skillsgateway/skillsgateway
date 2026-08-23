# Design: idp-group-role-mapping

## Context

`RoleService` is the whole authorization surface of the web chain. Every
privileged controller method opens with a `require*` call, and each of those
resolves privilege from exactly two places:

```java
private boolean isAdmin(String principal) {
    return properties.roles().admins().contains(principal)
        || hasGlobalRole(principal, RoleGrant.ADMIN);
}
```

— a configuration list, and rows in `role_grants`. Both are keyed on a bare
`String principal` taken from `Authentication.getName()`. Nothing in the tree
looks at an OIDC claim: there is no `GrantedAuthoritiesMapper`, no
`OidcUserService`, and `oauth2Login(Customizer.withDefaults())` in
`SecurityConfig.webChain` is entirely unconfigured. The only `GrantedAuthority`
the application ever manufactures is the synthetic `ROLE_USER` of
`dev-insecure-auth`.

So the identity provider's own answer to "who is an approver" — a group
membership that already has an owner and a joiner/mover/leaver process — is
invisible to the gateway, and reproducing it as grant rows creates a second
source of truth that drifts.

Two properties of the target deployment shape the design:

- The app registration is **shared across several platform services**. Its
  groups and app roles are the organisation's, not the gateway's, so a
  convention like "a claim value equal to a gateway role name grants that role"
  cannot work. Mapping is by claim *value*.
- Authentication stays in the application. There is no load-balancer OIDC and
  no auth-proxy sidecar, so the gateway's own login is where claims arrive.

Two configuration gaps block that login today, both verified against the
dependency set rather than assumed:

- `application.yaml` hard-codes the provider block and never sets
  `user-name-attribute`, so the principal is the OIDC `sub`. On a shared
  registration that is an opaque pairwise identifier.
- `OidcIdTokenValidator.validate` compares the token's `iss` only when
  `ProviderDetails.getIssuerUri()` is non-null (confirmed by decompiling
  `spring-security-oauth2-client` 7.1.0: the comparison sits behind
  `metadataIssuer != null && jwtIssuer != null`). Our provider block sets
  `authorization-uri`, `token-uri` and `jwk-set-uri` but no `issuer-uri`, so
  **no issuer check runs at all**. Against a multi-tenant authorization
  endpoint every tenant's tokens verify against the same JWKS, which makes the
  issuer the tenant boundary — and it is not being enforced.

## Goals / Non-Goals

**Goals**

- Configuration-declared mapping from an identity provider's group / app-role
  claim values to the existing three gateway roles, with approver scoping
  intact.
- Claim-derived roles compose with — never replace — configuration admins and
  stored grants.
- Every edge fails closed: a malformed mapping refuses startup, a credential
  without OIDC claims derives nothing, an unmatched claim value grants nothing.
- A truncated group claim is visible rather than silently under-privileging.
- `/api/me` answers "why does this session hold this role".
- The gateway can actually be pointed at an enterprise IdP: settable principal
  attribute, settable scope, enforced issuer.

**Non-Goals**

- **No new role and no schema change.** Claim-derived roles are never rows.
- **No claim-based authorization on the git facade.** `/git/**` authorizes by
  token scopes (GW_0064); a PAT carries no claims and derives no role.
- **No group-membership lookup against a provider's directory API.** Resolving
  a truncated claim by calling out to the IdP would add an outbound dependency
  to the authorization path and a vendor-specific client. The gateway reports
  the truncation instead.
- **No portal UI for mappings.** They are configuration; the deployment owns
  them. `/api/me` gains provenance, but no screen changes.
- **No prune semantics.** Removing a mapping removes the derived role at the
  next request. That is the point, and it needs no reconciliation.
- **No mapping to `dev-insecure-auth`.** That mode has no identity provider.

## Failure model (Tier 3)

| # | Failure mode | Layer that catches it |
| --- | --- | --- |
| F1 | A claim value matches by prefix, substring or case, granting the wrong people | Exact `equals` after trim; property test asserting no non-equal string ever matches |
| F2 | Claims are honoured on a credential that is not an OIDC session (PAT, dev principal, anonymous webhook) | `instanceof OidcUser` gate; negative tests on the facade chain, `dev-insecure-auth`, and a `UsernamePasswordAuthenticationToken` carrying a forged `groups` "claim" |
| F3 | A token from an unrelated tenant/issuer is accepted because `iss` is unchecked | `skills-gateway.oidc.issuer` + `JwtIssuerValidator`; test that a differing issuer fails the login |
| F4 | The group claim is truncated (overage) or absent and the session is silently under-privileged | GW_0099 detection, `/api/me` flag, WARN log; test asserts all three |
| F5 | A mapping typo silently grants nothing | Startup refuses a malformed mapping; a well-formed mapping that matches nothing is legitimate and stays quiet (documented) |
| F6 | The estate reconciler treats a claim-derived role as an existing grant and skips the declared one | `rolesOf(String)` stays stored-only; regression test that a declared grant is still created for a principal who holds the same role via claim |
| F7 | A claim-derived approver reaches another marketplace through a bare snapshot or waiver id | Reuses the existing gateway-side resolution; adversarial tests mirroring SVC_GW_0069 with claim-derived identities |
| F8 | A route ships ungated | Existing `RoleEnforcementTests.ROLE_GATED_MUTATIONS` route-table equality assertion (this change adds no route) |
| F9 | A hostile or malformed claim payload throws inside the authorization path — a 500 where a 403 belongs, or worse | Property-based test over arbitrary claim payloads (nested maps, nulls, numbers, huge lists, wrong types): never throws, never grants without an exact match |
| F10 | The principal attribute is mutable, so a rename orphans stored grants | Not fixable in code — documented in the guide, and precisely the argument for mapping groups instead of granting principals |
| F11 | Mappings leak privilege while `roles.enabled=false` | Enforcement short-circuits before any lookup, unchanged; test asserts derived roles are reported but never enforced |

## Decisions

1. **Resolve claims per request, not into authorities at login.**
   `ClaimRoleMapper.rolesFrom(Authentication)` reads the configured claim from
   the session's `OidcUser` on each authorization check.
   *Alternative rejected:* a `GrantedAuthoritiesMapper` or custom
   `OidcUserService` mapping claims to `GrantedAuthority` at login. It would
   create a second authorization surface next to the one the codebase
   deliberately keeps greppable as `requireA`, and it would freeze the mapping
   into every live session, so a corrected mapping would not take effect until
   everyone logged out. Reading the claim per request keeps `RoleService` the
   single decision point and makes a configuration change effective at once.

2. **`RoleService` grows a session-aware path; the principal-keyed one stays.**
   `effectiveRoles(Authentication)` = configuration admin ∪ stored grants ∪
   claim-derived roles, and every `require*` consults it. `rolesOf(String
   principal)` keeps its exact current meaning — stored grants plus the
   configuration admin list — because `EstateReconciler.reconcileGrant` uses it
   as its "already converged" check and has no session at startup. Folding
   claim roles into it would make a group-derived role suppress a *declared*
   grant, and losing the group would then silently lose the grant too (F6).

3. **Exact, case-sensitive match on a trimmed claim value.** The values are
   opaque identifiers copied from the provider (group object ids, app-role
   values). Prefix, glob or case-insensitive matching can only ever broaden who
   is privileged, which is the wrong direction for a trust boundary. Trimming
   is the one concession, because YAML makes trailing whitespace easy and
   invisible.

4. **The claim may be a list or a single string, and may sit at a dotted
   path.** `Collection` elements are taken as strings; a lone `String` is one
   value. A delimited string is *not* split — inventing a delimiter would be
   guessing. The dotted path (`realm_access.roles`) keeps the feature
   vendor-neutral without a per-provider adapter. Values are read from
   `OidcUser.getClaims()`, which is the union of ID-token and UserInfo claims;
   both are provider-authenticated over the back channel.

5. **A malformed mapping refuses startup.** `ClaimRoleMapper`'s constructor
   validates shape — an unknown role, a blank claim value, an `approver`
   without a marketplace, an `admin`/`auditor` with one — and throws, following
   the `EstateStartupFailureTests` precedent. A mapping naming a marketplace
   that is not registered *yet* is not an error: registration can happen later,
   including by the estate reconciler in the same startup, and until then the
   mapping simply matches nothing.
   *Alternative rejected:* validation annotations on the properties record —
   `reference/configuration.md` documents the house rule that there are none.

6. **Mappings are not an estate object.** `CLAUDE.md` requires any new
   API-managed runtime state to extend `skills-gateway.estate.*`. Claim
   mappings are not runtime state: no endpoint creates, reads or deletes them,
   there is nothing in the database to converge, and removing one takes effect
   immediately. They belong in `skills-gateway.roles.*` next to `admins`, which
   is the same kind of thing — privilege asserted by whoever controls
   deployment. `estate.grants` is unchanged and still the way to declare *rows*.

7. **Issuer pinning through a `JwtDecoderFactory` bean, not
   `provider.issuer-uri`.** Setting `issuer-uri` would send Spring Boot down
   `getBuilderFromIssuerIfPossible`, which discovers the provider's metadata and
   then overwrites it with whatever `authorization-uri` / `token-uri` /
   `jwk-set-uri` are set — and ours always are, because `application.yaml`
   gives them `https://idp.invalid/...` defaults so the registration exists at
   AOT build time. An operator who set only the issuer would silently get the
   real issuer with invalid endpoints. Instead a `JwtDecoderFactory<
   ClientRegistration>` bean wraps `OidcIdTokenDecoderFactory` with a
   `JwtIssuerValidator` when `skills-gateway.oidc.issuer` is set. Self-
   contained, no interaction with the property mapper, no AOT risk.
   Unset is the default (today's behaviour); a startup WARN says the issuer is
   unpinned whenever `dev-insecure-auth` is off.

8. **The acceptance suite is made to depend on the mapping.** `compose.e2e.yaml`
   teaches the mock IdP to issue a group claim, `run-e2e.sh` enables role
   enforcement and maps that claim value to `admin`. Every existing Playwright
   test then passes *only* if claim-derived admin works end to end, through a
   real browser and a real login redirect — a much stronger signal than one
   assertion. A focused test additionally asserts the provenance on `/api/me`.
   If the mock server turns out unable to carry a stable claim alongside its
   interactive login, the fallback is enforcement left off plus the focused
   provenance test, and the evidence report will say so rather than claim the
   stronger arrangement.

## Configuration

```yaml
skills-gateway:
  roles:
    enabled: true
    admins: [break-glass@example.com]
    claim: groups                     # default; dotted paths allowed
    mappings:
      - claim-value: 8f1c…-group-object-id
        role: admin
      - claim-value: gateway-approvers-acme
        role: approver
        marketplace: acme
      - claim-value: security-auditors
        role: auditor
  oidc:
    issuer: https://idp.example.com/tenant/v2.0
```

Plus two new environment passthroughs with today's values as defaults:
`SGW_OIDC_USER_NAME_ATTRIBUTE` (`sub`) and `SGW_OIDC_SCOPE` (`openid`).

## Risks

- **A wrong mapping grants real privilege.** Mitigated by exact matching,
  startup validation, and `/api/me` provenance, but the configuration is
  authoritative by design — same as `roles.admins` today. The guide leads with
  the break-glass admin for exactly this reason.
- **`preferred_username` is mutable.** A rename orphans stored grants and
  breaks `roles.admins`. Documented; the immutable `oid`/`sub` alternative is
  offered with its readability cost stated (F10).
- **Claim size.** A user in many groups makes each authorization check scan a
  larger list. Bounded by the token the provider already issued, and the scan
  is over an in-memory list against a small map. No cap is imposed.
