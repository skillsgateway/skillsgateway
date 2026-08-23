# Tasks: idp-group-role-mapping

## 1. Requirements (SSOT first)

- [ ] 1.1 Add GW_0098 (configuration-declared mapping from identity-provider
      claim values to gateway roles: union with configuration admins and stored
      grants, approver scoping preserved, exact matching, malformed mapping
      refuses startup, a credential without OIDC claims derives nothing, role
      provenance on the session endpoint), GW_0099 (truncated group claim
      detected, logged and reported rather than silently under-privileging) and
      GW_0100 (configurable principal-name attribute and scope set, and an
      enforced expected ID-token issuer) to `docs/reqstool/requirements.yml`
- [ ] 1.2 Add SVC_GW_0098, SVC_GW_0099 and SVC_GW_0100 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Failing tests first (old-coder: prove they fail)

- [ ] 2.1 `ClaimRoleMappingTests` (extends `AbstractGatewayTest`), `@SVCs({
      "SVC_GW_0098"})`: with enforcement on and no grant rows at all — a
      session whose claim carries a mapped admin value may register a
      marketplace; a mapped approver value may approve only its own
      marketplace; a mapped auditor value reads the ledger and is refused every
      mutation; an unmapped value holds nothing; claim roles union with a
      stored grant and with `roles.admins`
- [ ] 2.2 Adversarial set, same `@SVCs`: substring, prefix, case-differing and
      whitespace-padded claim values grant nothing (F1); a claim-derived
      approver is refused another marketplace through a bare snapshot id and a
      bare waiver id (F7); a PAT on `/git/**`, the `dev-insecure-auth`
      principal, and a non-OIDC `Authentication` carrying a forged `groups`
      value all derive nothing (F2); mappings are inert for enforcement while
      `roles.enabled=false` yet still reported (F11)
- [ ] 2.3 `ClaimRoleMapperPropertyTests`, `@SVCs({"SVC_GW_0098"})`: property-
      based over arbitrary claim payloads (nested maps, nulls, numbers, wrong
      types, large lists) — never throws, and never yields a role without an
      exact match (F1, F9)
- [ ] 2.4 `ClaimOverageTests`, `@SVCs({"SVC_GW_0099"})`: a session whose token
      omits the configured claim but carries an overage indicator
      (`hasgroups`, `_claim_names`) is reported truncated on `/api/me`, logs a
      warning, and holds no derived role; a session that simply has no
      memberships is *not* reported truncated (F4)
- [ ] 2.5 `ClaimMappingStartupFailureTests`, `@SVCs({"SVC_GW_0098"})`
      (`EstateStartupFailureTests` pattern): an unknown role, a blank
      claim-value, an `approver` mapping with no marketplace and an
      `admin`/`auditor` mapping naming one each refuse startup; a mapping
      naming an unregistered marketplace starts fine and matches nothing (F5)
- [ ] 2.6 `OidcIssuerPinningTests`, `@SVCs({"SVC_GW_0100"})`: with
      `skills-gateway.oidc.issuer` set, an ID token from a different issuer
      fails validation and no session is established; the matching issuer
      succeeds; unset keeps today's behaviour and logs the startup warning
      (F3). Plus a principal-name-attribute test: with
      `SGW_OIDC_USER_NAME_ATTRIBUTE` pointing at another claim, `/api/me`
      reports that claim's value and grants key on it
- [ ] 2.7 `EstateReconciliationTests` addition, `@SVCs({"SVC_GW_0085"})`: a
      declared grant is still created for a principal who already holds the
      same role via a claim mapping (F6)
- [ ] 2.8 Confirm every new test FAILS before any implementation, and record
      the run for the evidence report

## 3. Configuration

- [ ] 3.1 `SkillsGatewayProperties.Roles`: add `claim` (default `groups`) and
      `mappings` (`List<ClaimMapping>`, default empty); new record
      `ClaimMapping(String claimValue, String role, String marketplace)`;
      `@Requirements({"GW_0098"})` on the enclosing documentation
- [ ] 3.2 `SkillsGatewayProperties`: new `Oidc(String issuer)` block, default
      null, with the compact-constructor default alongside the others
- [ ] 3.3 `application.yaml`: `user-name-attribute: ${SGW_OIDC_USER_NAME_
      ATTRIBUTE:sub}` and `scope: ${SGW_OIDC_SCOPE:openid}` on the `idp`
      provider/registration

## 4. Claim-to-role mapping

- [ ] 4.1 `dev.skillsgateway.server.roles.ClaimRoleMapper`: constructor-time
      shape validation that refuses startup; `rolesFrom(Authentication)`
      returning claim-derived `EffectiveRole`s; dotted claim-path resolution;
      `Collection`-or-`String` claim shapes; exact trimmed match;
      `truncated(Authentication)` overage detection;
      `@Requirements({"GW_0098", "GW_0099"})`
- [ ] 4.2 `RoleService`: add `effectiveRoles(Authentication)` (configuration
      admin ∪ stored grants ∪ claim roles, deduped on role+marketplace with
      `config` > `grant` > `claim` provenance) and route every `require*`
      through it; leave `rolesOf(String)` stored-only with a comment saying why
      (the estate reconciler); extend `EffectiveRole` with `source`;
      `@Requirements({"GW_0098"})`
- [ ] 4.3 `MeController`: report `source` per role and a `claimsTruncated`
      flag, with `@Schema` descriptions; `@Requirements({"GW_0098", "GW_0099"})`

## 5. Issuer pinning and principal attribute

- [ ] 5.1 `SecurityConfig`: `JwtDecoderFactory<ClientRegistration>` bean
      wrapping `OidcIdTokenDecoderFactory` with a `JwtIssuerValidator` when
      `skills-gateway.oidc.issuer` is set; startup WARN when it is unset and
      `dev-insecure-auth` is off; `@Requirements({"GW_0100"})`

## 6. Ops wiring

- [ ] 6.1 `helm/skills-gateway`: `oidc.userNameAttribute`, `oidc.scope`,
      `oidc.issuer` values → deployment env; `compose.yaml` passthrough
- [ ] 6.2 `compose.e2e.yaml`: mock IdP `JSON_CONFIG` issues a group claim;
      `src/main/frontend/e2e/run-e2e.sh` enables role enforcement and maps that
      value to `admin` (fallback per design Decision 8 if the mock server
      cannot carry the claim alongside interactive login — record which
      arrangement shipped in the evidence report)
- [ ] 6.3 Playwright: `@SVCs SVC_GW_0098` test asserting `/api/me` reports the
      admin role with source `claim` after a real login

## 7. Portal types

- [ ] 7.1 Regenerate `src/main/frontend/openapi.json` → `src/api/types.gen.ts`
      from `OpenApiDocsTests`; widen `useMe()` to the generated `MeView`; update
      MSW handlers

## 8. Documentation (same PR)

- [ ] 8.1 New `docs/manual/guides/identity-providers.md`: the generic OIDC
      contract, the mapping configuration, and a worked Microsoft Entra ID
      section — app registration, redirect URI
      `https://<host>/login/oauth2/code/idp`, issuer, the group/app-role claim
      configuration, group overage and what to do instead, and why
      `preferred_username` is the principal attribute including its mutability
- [ ] 8.2 `docs/manual/reference/configuration.md`: `skills-gateway.roles.claim`
      / `.mappings` rows, the `skills-gateway.oidc.issuer` row, and correct the
      OIDC section that currently calls the scope "fixed, not intended for
      override"
- [ ] 8.3 `docs/manual/guides/delegated-administration.md`: groups as a third
      source of roles and when to prefer them over grants;
      `docs/manual/concepts/trust-boundaries.md`: claims as an input to the
      role boundary; `docs/manual/reference/api/roles.md` and the `/api/me`
      contract: `source` and `claimsTruncated`;
      `docs/manual/guides/declarative-estate.md`: mappings are configuration,
      not an estate object
- [ ] 8.4 `docs/manual/architecture.md` implemented-today note and `mkdocs.yml`
      (+ `mkdocs-print.yml`) nav entry for the new guide

## 9. Gates and evidence (old-coder gauntlet)

- [ ] 9.1 `./mvnw clean verify`
- [ ] 9.2 `(cd src/main/frontend && pnpm test:stories)` and
      `(cd src/main/frontend && pnpm e2e)`
- [ ] 9.3 `reqstool status local -p docs/reqstool` ends PASS;
      `openspec validate --all --strict`; `mkdocs build --strict`
- [ ] 9.4 Mutation pass over `ClaimRoleMapper` and the changed `RoleService`
      paths; adversarial pass; `evidence.md` with one fresh final run of every
      gate after the last edit, pasted tails and the commit SHA
