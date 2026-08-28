# Tasks: dev-insecure-auth-guard

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0110 (the development-only unauthenticated mode refuses to
      start on a deployment that has an identity provider configured, and the
      refusal states what decided it and how to resolve it) to
      `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0110 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Failing test first (old-coder: prove it fails)

- [x] 2.1 `DevInsecureAuthGuardTests`, `@SVCs({"SVC_GW_0110"})`: real Spring
      contexts that start or refuse — the local loop (flag on, placeholders)
      starts; a real client id, a real provider endpoint, and a pinned issuer
      each refuse; a fully configured identity provider with the flag off starts
- [x] 2.2 Same `@SVCs`: the placeholder constants the guard trusts are the ones
      `src/main/resources/application.yaml` ships
- [x] 2.3 Prove both fail before the guard throws (recorded in `evidence.md`)

## 3. Implementation

- [x] 3.1 `DevInsecureAuthGuard` in `dev.skillsgateway.server.auth`,
      `@Requirements({"GW_0110"})`: no-op when the flag is off; otherwise
      collects identity-provider signals from the `ClientRegistrationRepository`
      and `skills-gateway.oidc.issuer` and throws with an actionable message
- [x] 3.2 `DevAuthTests` autowires the guard, so the local loop the guard must
      never refuse is asserted by the escape hatch's own test

## 4. NOTICE

- [x] 4.1 Add `NOTICE` at the repository root — project, copyright,
      Apache-2.0 statement, SBOM as the third-party inventory
- [x] 4.2 `README.md` license section points at it

## 5. Documentation (same PR)

- [x] 5.1 `docs/manual/reference/configuration.md`: the guard's rule under
      `skills-gateway`, and the cross-reference from the OIDC login section
- [x] 5.2 `docs/manual/guides/local-development.md`: what the escape hatch now
      refuses, and why the local loop is unaffected
- [x] 5.3 `docs/manual/concepts/trust-boundaries.md`: the second control on the
      web-chain boundary

## 6. Gates

- [x] 6.1 `./mvnw clean verify`
- [x] 6.2 `pnpm test:stories`
- [x] 6.3 `pnpm e2e`
- [x] 6.4 `reqstool status local -p docs/reqstool`
- [x] 6.5 `openspec validate --all --strict`
- [x] 6.6 `mkdocs build --strict`
- [x] 6.7 `openspec/changes/dev-insecure-auth-guard/evidence.md`
