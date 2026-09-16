# Tasks: facade-idp-bearer-tokens

Issue [#392](https://github.com/skillsgateway/skillsgateway/issues/392), options
2 and 4. Trust boundary — the old-coder loop applies: the acceptance criteria in
`design.md` are the spec, every test is watched failing before it passes, and
`evidence.md` carries the mapping.

## 1. Requirements (SSOT first)

- [ ] 1.1 Add `GW_AUTH_0040` — Identity-provider bearer tokens on the git facade
- [ ] 1.2 Add `GW_AUTH_0041` — Facade fetches record the kind of credential that authenticated them
- [ ] 1.3 Add `GW_AUTH_0042` — The administrative interface refuses identity-provider bearer tokens
- [ ] 1.4 Add `GW_AUTH_0043` — The client wizard leads on a serving marketplace and states the held-snapshot outcome
- [ ] 1.5 Add `SVC_GW_AUTH_0040`–`SVC_GW_AUTH_0043`, one per requirement
- [ ] 1.6 Confirm the block `GW_AUTH_0040`–`0043` is free on this branch, on
      `origin/main`, and in every unarchived change under `openspec/changes/`;
      the gap from `0031` is deliberate (concurrent allocation for #394)

## 2. Configuration

- [ ] 2.1 `SkillsGatewayProperties.Facade` → `IdpBearer(Boolean enabled, String audience)`,
      null-filled by the compact constructor like every sibling
- [ ] 2.2 Startup refusal when `enabled` is true and `skills-gateway.oidc.issuer`
      is unset or blank, naming both properties (D3)
- [ ] 2.3 `ConfigSurfaceBudgetTests`: measure, and raise `BUDGET` only if the
      measured count exceeds it, citing `design.md` D8 in the docstring
- [ ] 2.4 `docs/manual/reference/configuration.md` gains both leaves

## 3. The bearer path (GW_AUTH_0040)

- [ ] 3.1 `IdpBearerTokenDecoder` — a `@ConditionalOnProperty` bean building
      `NimbusJwtDecoder` from the `idp` registration's `jwk-set-uri`, with
      `JwtTimestampValidator` (default leeway), `JwtIssuerValidator` and an
      audience validator; nothing registered when the feature is off (F5)
- [ ] 3.2 `IdpBearerAuthenticationProvider` — decodes, maps the configured
      username claim to the principal, grants `ROLE_GIT`, leaves `details` null
- [ ] 3.3 `FacadeBearerAuthenticationFilter` — acts only on `Authorization: Bearer`,
      sets and clears the context per request, one indistinguishable 401
- [ ] 3.4 `SecurityConfig.gitChain` and `publishChain`: the filter is added only
      when the feature bean exists; the entry point is stated as
      `BasicAuthenticationEntryPoint` so the challenge does not change (F7)
- [ ] 3.5 `@Requirements({"GW_AUTH_0040"})` on the provider and the chain bean

## 4. The ledger (GW_AUTH_0041)

- [ ] 4.1 `V1__init.sql`: `CREATE TYPE fetch_log_credential_kind AS ENUM ('pat','idp')`
      and a nullable `credential_kind` column, with the comment saying why it is
      not inferred from `token_id`
- [ ] 4.2 `CredentialKind` enum, mirroring `ActorType`'s shape
- [ ] 4.3 `FetchLogRepository.append` overload taking the kind, written through
      an explicit cast; existing overloads pass null
- [ ] 4.4 `FetchAuditHook` derives the kind from the authentication and records it
- [ ] 4.5 `docs/manual/reference/api/audit.md` entry-field table

## 5. `/api/**` stays shut (GW_AUTH_0042)

- [ ] 5.1 Test proving a valid IdP token reaches no `/api/**` endpoint, on an
      endpoint an admin session can reach, with the feature **on**

## 6. Tests — RED before GREEN, each watched failing

- [ ] 6.1 A JWKS fixture: an in-process key pair and key-set endpoint, plus a
      token builder that can produce every token in the failure model
- [ ] 6.2 Positive: clone with a bearer token; ledger principal, kind and null
      `token_id` (criteria 1–2)
- [ ] 6.3 Positive: a PAT clone on the same gateway still records `pat` (3)
- [ ] 6.4 Positive: no mapped role still fetches (4, D6)
- [ ] 6.5 Negative matrix: wrong issuer, wrong audience, expired, not-yet-valid,
      tampered signature, PAT-shaped bearer, no `aud` (5–10, 12)
- [ ] 6.6 Feature off: a valid token is refused (11, F5) — separate context
- [ ] 6.7 Challenge unchanged: anonymous gets 401 + `WWW-Authenticate: Basic` (13)
- [ ] 6.8 Startup refusal when enabled without an issuer (15)
- [ ] 6.9 `@SVCs` annotations on the verifying tests

## 7. Portal (GW_AUTH_0043)

- [ ] 7.1 Lead panel on `marketplace-detail.tsx` above the Upstream card, gated
      on an approved snapshot existing; the held case carries the 404 statement
- [ ] 7.2 `setup-wizard.tsx`: `git credential approve` becomes the primary copy
      target; a lifetime select bounded by the server policy with a bounded
      default; a default token name; the held-snapshot statement inside the
      wizard too
- [ ] 7.3 Component tests next to the existing ones (disabled-until-valid rules)
- [ ] 7.4 `setup-wizard.stories.tsx` — serving and held states, axe clean
- [ ] 7.5 Playwright step in the existing wizard spec, `@SVCs SVC_GW_AUTH_0043`
- [ ] 7.6 `/impeccable audit` and `/impeccable harden` on the changed surfaces;
      findings fixed or dismissed with a reason in the PR body

## 8. Documentation (same PR)

- [ ] 8.1 `guides/consuming-skills.md` — the SSO bearer option and the worked
      Git Credential Manager generic-OAuth recipe against the mock IdP
- [ ] 8.2 `reference/git-facade.md` — the second authentication path, the
      sequence diagram, and the stated limit that revocation is the provider's
- [ ] 8.3 `concepts/trust-boundaries.md` §2
- [ ] 8.4 `reference/portal.md` — Set up a client
- [ ] 8.5 `architecture.md` — the façade component paragraph and §14's auth line
- [ ] 8.6 **ADR 0019** — the facade accepts IdP-issued bearer tokens,
      superseding ADR 0002's "PATs only" clause; indexed in `reference/adrs.md`
- [ ] 8.7 `CLAUDE.md` Boundaries bullet
- [ ] 8.8 Regenerate `openapi.json` and `types.gen.ts` if the ledger field
      reaches the document

## 9. Gates and evidence

- [ ] 9.1 `./mvnw clean verify`
- [ ] 9.2 `pnpm test:stories`
- [ ] 9.3 `pnpm e2e`
- [ ] 9.4 `reqstool status local -p docs/reqstool` ends `PASS`
- [ ] 9.5 `openspec validate --all --strict`
- [ ] 9.6 `mkdocs build --strict`
- [ ] 9.7 `evidence.md`: the acceptance-criteria mapping, every gauntlet layer
      with pasted numbers, the config-leaf count before and after, layers
      skipped and why, and the commit SHA
- [ ] 9.8 Archive the change as the final commit
