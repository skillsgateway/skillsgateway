# Tasks: scoped-admin-roles

## 1. Traceability (SSOT first)

- [x] 1.1 GW_0068–GW_0071 in `docs/reqstool/requirements.yml`.
- [x] 1.2 SVC_GW_0068–SVC_GW_0071 in
      `docs/reqstool/software_verification_cases.yml`.

## 2. Schema and config

- [x] 2.1 `role_grants` table (principal, role CHECK, marketplace_id nullable
      FK, granted_by, granted_at, UNIQUE(principal, role, marketplace_id)).
- [x] 2.2 `SkillsGatewayProperties.Roles(enabled=false, admins=[])`.

## 3. Backend

- [x] 3.1 `roles/RoleGrantRepository` + `roles/RoleGrant` record.
- [x] 3.2 `roles/RoleService`: `requireAdmin`, `requireAuditor`,
      `requireApprover(marketplaceName)`, `requireApproverOfSnapshot(id)`,
      `rolesOf(principal)`; disabled → all pass; config admins merged in.
- [x] 3.3 Enforcement wired as first-line calls: register/sync-mode/catalog
      rebuild/retention ops/webhook subscriber mutations/audit sink mutations
      → admin; ingest/approve/reject/revet/waiver create+delete →
      approver-or-admin (marketplace-resolved); ledger read + export +
      deliveries + sinks list + retention candidates → auditor-or-admin.
- [x] 3.4 `roles/RoleController`: GET/POST/DELETE `/api/roles` (admin only
      when enabled), grants audited (`role-granted`/`role-revoked` with
      target, role, marketplace).
- [x] 3.5 `MeController` gains `rolesEnabled` + effective `roles`.

## 4. API artifacts

- [x] 4.1 Regenerate `openapi.json` + `types.gen.ts`; frontend typecheck.

## 5. Tests (old-coder Tier 3; own context with roles enabled)

- [x] 5.1 Deny-by-default walk: a no-role session gets 403 from every known
      mutation route and from the ledger/export, while the browsing surface
      and own tokens still work; the walk asserts the route count so a new
      unprotected mutation must be added deliberately.
- [x] 5.2 Approver scoping: approver of marketplace A can ingest/approve/
      reject/waive/re-vet A and gets 403 for marketplace B, including via
      snapshot-id routes (no confused-deputy through the id).
- [x] 5.3 Auditor: reads the ledger and export, 403 on every mutation tried.
- [x] 5.4 Grants lifecycle: non-admin cannot grant (403); admin grants and
      revokes with audit entries; approver grant without marketplace (and for
      an unknown marketplace) refused; config-bootstrapped admin works and
      cannot be revoked via API; /api/me reports effective roles.
- [x] 5.5 Compatibility: default context (disabled) — existing suites pass
      unchanged, and a no-role user can do everything (explicit test).
- [x] 5.6 Mutants (kill + restore, in evidence): requireAdmin short-circuited;
      approver marketplace comparison removed.

## 6. Documentation

- [x] 6.1 New guide `guides/delegated-administration.md`; `mkdocs.yml` nav.
- [x] 6.2 `reference/configuration.md`: `roles` block;
      new `reference/api/roles.md` (+ nav) and /api/me change in api docs.
- [x] 6.3 `concepts/trust-boundaries.md`: replace the "no role model yet"
      section; glossary entries; ARCHITECTURE recall note.
- [x] 6.4 Endpoint reference pages gain their role requirements.

## 7. Gates and evidence

- [ ] 7.1 `./mvnw clean verify`
- [ ] 7.2 `(cd src/main/frontend && pnpm e2e)`
- [ ] 7.3 `reqstool status local -p docs/reqstool` → PASS
- [ ] 7.4 `openspec validate --all --strict`
- [ ] 7.5 `mkdocs build --strict`
- [ ] 7.6 `evidence.md` with the final commit SHA.
