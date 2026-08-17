# Tasks: token-scopes-expiry

## 1. Traceability (SSOT first)

- [x] 1.1 GW_0064–GW_0067 in `docs/reqstool/requirements.yml` (scopes;
      expiry; rotation; attribution).
- [x] 1.2 SVC_GW_0064–SVC_GW_0067 in
      `docs/reqstool/software_verification_cases.yml`.

## 2. Schema

- [x] 2.1 `access_tokens` += `scopes TEXT`, `expires_at TIMESTAMPTZ`,
      `rotated_from BIGINT`; `fetch_log` += `token_id BIGINT` (V1__init.sql).

## 3. Backend

- [x] 3.1 `AccessToken` record += scopes/expiresAt/rotatedFrom (+
      `permitsMarketplace(name)`); `TokenRepository` maps them, `create` takes
      them, `findActiveByHash` excludes expired, `rotate` = revoke-then-create
      with lineage.
- [x] 3.2 `SkillsGatewayProperties.Tokens(maxTtl=null)`.
- [x] 3.3 `TokenService`: create validates scopes (against marketplaces +
      catalog name) and ttl cap; authenticate excludes expired; rotate (owner
      only, live tokens only, same grant + same deadline, revoke-first).
- [x] 3.4 `PatAuthenticationProvider` sets the `AccessToken` as details.
- [x] 3.5 Facade: out-of-scope → `RepositoryNotFoundException` in
      `resolvePublished`; `FetchAuditHook` records `token_id` on facade
      entries.
- [x] 3.6 `TokenController`: create gains scopes/expiresAt (422 on invalid),
      `POST /tokens/{id}/rotate` (audited), views expose the new fields;
      audit listing and `AuditEntry` export carry `token_id`.

## 4. API artifacts

- [x] 4.1 Regenerate `openapi.json` + `types.gen.ts`; frontend typecheck.

## 5. Tests (old-coder Tier 3: facade auth is a trust boundary)

- [x] 5.1 Scopes: scoped token clones its marketplace and the catalog when
      granted; out-of-scope clone fails with the same answer as an unknown
      marketplace (no existence oracle); empty scopes = all; invalid scope at
      creation → 422.
- [x] 5.2 Expiry: expired token → 401 at the facade with no sweep involved;
      max-ttl cap → 422 beyond it; unset ttl still unlimited.
- [x] 5.3 Rotation: new secret works, old secret → 401 immediately, grant
      identical (name, scopes, deadline), lineage recorded, foreign/revoked/
      expired token rotation refused.
- [x] 5.4 Attribution: facade fetch ledger rows carry the token id; export
      rows carry it; token created/revoked/rotated audited with name+scopes.
- [x] 5.5 Mutants (kill + restore, recorded in evidence): scope check removed;
      expiry filter removed.

## 6. Documentation

- [x] 6.1 `guides/consuming-skills.md`: scopes/expiry/rotation in the token
      step; `reference/api/tokens.md`: new fields + rotate endpoint.
- [x] 6.2 `reference/configuration.md`: `tokens` block;
      `reference/api/audit.md`: `token_id` field.
- [x] 6.3 `concepts/trust-boundaries.md` (facade section: scope enforcement),
      `concepts/glossary.md` (scope, rotation); ARCHITECTURE.md recall note.

## 7. Gates and evidence

- [x] 7.1 `./mvnw clean verify`
- [x] 7.2 `(cd src/main/frontend && pnpm e2e)`
- [x] 7.3 `reqstool status local -p docs/reqstool` → PASS
- [x] 7.4 `openspec validate --all --strict`
- [x] 7.5 `mkdocs build --strict`
- [x] 7.6 `evidence.md` with the final commit SHA.
