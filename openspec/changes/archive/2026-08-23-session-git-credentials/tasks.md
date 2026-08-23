# Tasks: session-git-credentials

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0104 (a git credential derived from an authenticated browser
      session: lifetime set by the gateway and not by the caller, no
      publication authority, marked as session-derived wherever the credential
      appears, and rotation that can neither extend it nor launder the mark) to
      `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0104 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Tests first — observed failing before the implementation exists

- [x] 2.1 `SessionCredentialTests` (extends `AbstractGatewayTest`),
      `@SVCs({"SVC_GW_0104"})`: the endpoint mints a token whose expiry is the
      configured TTL; supplying `expiresAt` in the body does not move it (F1);
      the credential is reported `sessionDerived` while an ordinary token is not
      (F3); it fetches through the facade like any token
- [x] 2.2 Negative set, same `@SVCs`: a session credential cannot push to a
      hosted marketplace (F2); a narrowed one is refused another marketplace
      (F7); rotation keeps both the deadline and the mark (F4)
- [x] 2.3 A context with a near-zero `session-ttl` asserts the credential fails
      authentication once past it, by comparison rather than by any sweep (F5)
- [x] 2.4 Record the RED run

## 3. Schema, model and policy

- [x] 3.1 `V1__init.sql`: `access_tokens.session_derived BOOLEAN NOT NULL
      DEFAULT FALSE`
- [x] 3.2 `AccessToken` gains `sessionDerived`; `TokenRepository.create` and
      `map` carry it
- [x] 3.3 `SkillsGatewayProperties.Tokens` gains `sessionTtl` (default 8h)

## 4. Issuing path

- [x] 4.1 `TokenService.createSessionCredential(principal, name, scopes)`: the
      expiry is `now + sessionTtl`, no push scopes, `session_derived` set;
      `@Requirements({"GW_0104"})`
- [x] 4.2 `TokenService.rotate` copies `session_derived` alongside the deadline
- [x] 4.3 `TokenController`: `POST /api/tokens/session` with a body carrying a
      name and optional scopes and nothing else; audit detail says
      session-derived; `TokenView` gains `sessionDerived`

## 5. Portal types

- [x] 5.1 Regenerate `openapi.json` → `types.gen.ts` (no portal UI in this
      change; the endpoint is what a "get a git credential" control will call)

## 6. Documentation (same PR)

- [x] 6.1 `docs/decisions/0008-serving-surface-stays-the-embedded-facade.md`
      (the #59 decision), indexed from `reference/decisions.md` and
      `architecture.md`
- [x] 6.2 `guides/consuming-skills.md`: mint a session credential instead of a
      standing PAT, and what its TTL does and does not guarantee
- [x] 6.3 `reference/api/tokens.md`, `reference/configuration.md`,
      `concepts/trust-boundaries.md`

## 7. Gates and evidence (old-coder gauntlet)

- [x] 7.1 `./mvnw clean verify`
- [x] 7.2 `(cd src/main/frontend && pnpm test:stories)` and `pnpm e2e`
- [x] 7.3 `reqstool status local -p docs/reqstool` ends PASS;
      `openspec validate --all --strict`; `mkdocs build --strict`
- [x] 7.4 Mutation pass over the issuing path with a negative control;
      adversarial pass; `evidence.md` with one fresh final run and the SHA
