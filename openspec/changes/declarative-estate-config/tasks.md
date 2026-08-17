# Tasks: declarative-estate-config

## 1. Traceability (SSOT first)

- [ ] 1.1 GW_0083–GW_0087 in `docs/reqstool/requirements.yml`.
- [ ] 1.2 SVC_GW_0083–SVC_GW_0087 in
      `docs/reqstool/software_verification_cases.yml`.

## 2. Config

- [ ] 2.1 `SkillsGatewayProperties.Estate` (marketplaces, grants, webhooks,
      audit-sinks; all default empty).

## 3. Shared creation paths (extraction, behavior unchanged)

- [ ] 3.1 `admin/MarketplaceRegistrationService.register(name, url, actor)`
      — name pattern, reserved catalog name (GW_0063), scheme allowlist
      (GW_0016), duplicate check, forge metadata, ledger entry;
      `AdminController` delegates (keeps the GW_0017 ref check on the
      request shape).
- [ ] 3.2 `RoleService.grant(principal, role, marketplace, actor)` — role
      vocabulary, approver scoping, marketplace existence, duplicate 409,
      ledger entry (GW_0071); `RoleController` delegates.
- [ ] 3.3 `WebhookService.register(name, url, events, secret, actor)` —
      name pattern, scheme allowlist, event validation, null secret →
      generated (GW_0024); `WebhookController` delegates.
- [ ] 3.4 `AuditExportService.registerSink(name, url, after, batchSize,
      secret, actor)` — name pattern, scheme allowlist, cross-namespace
      duplicate check (GW_0028); `AuditController` delegates.
- [ ] 3.5 Repository update methods:
      `WebhookSubscriberRepository.update(id, url, secret, events)`,
      `AuditSinkRepository.updateBatchSize(id, batchSize)`.

## 4. Reconciler and API

- [ ] 4.1 `estate/EstateReconciler`: synchronized diff-then-write convergence
      over marketplaces → grants → subscribers → sinks; per-entry failure
      isolation; `config-reconciler` actor; last-report holder
      (SVC_GW_0083–SVC_GW_0087, `@Requirements` on the implementing
      methods).
- [ ] 4.2 `estate/EstateBootstrap` (`SmartInitializingSingleton`): reconcile
      at startup, after migrations, before the web surface serves.
- [ ] 4.3 `estate/EstateController`: `POST /api/estate/reconcile` (admin,
      audits `estate-reconcile-triggered`), `GET /api/estate`
      (auditor-or-admin, last report).
- [ ] 4.4 Classify both routes in `RoleEnforcementTests`
      (`ROLE_GATED_MUTATIONS` / `PRIVILEGED_READS`).

## 5. API artifacts

- [ ] 5.1 Regenerate `openapi.json` + `types.gen.ts`; frontend typecheck.

## 6. Tests (old-coder Tier 3; RED observed before GREEN)

- [ ] 6.1 `EstateReconciliationTests` (own context: roles enabled, declared
      estate): startup applied the declaration — marketplace with sync mode,
      grants effective, subscriber and sink rows with the declared secrets
      (SVC_GW_0083, SVC_GW_0084, SVC_GW_0085, SVC_GW_0086).
- [ ] 6.2 Converged no-op: a second reconcile makes zero writes and appends
      zero ledger entries; the endpoint trigger appends exactly its own
      admin-actor entry (SVC_GW_0083).
- [ ] 6.3 Secret rotation: changed declared secret updates the row, audits
      `webhook-subscriber-updated`/`audit-sink-updated` without the value;
      same secret again → no write; sink cursor untouched (SVC_GW_0086).
- [ ] 6.4 Adversarial (SVC_GW_0084, SVC_GW_0085, SVC_GW_0086, SVC_GW_0087):
      disallowed scheme, reserved name, URL drift, `webhook` sync mode,
      grant for an unknown marketplace, blank/short secret — each an
      isolated `failed` entry with a ledger record, other entries applied,
      no partial row; secret value absent from report and ledger.
- [ ] 6.5 Additive guarantee: API-created objects and grants absent from the
      declaration survive a reconcile untouched (SVC_GW_0083).
- [ ] 6.6 `EstateStartupFailureTests` (own context with one invalid entry):
      the application starts, the valid entry is applied, the invalid one is
      on the ledger and in the report (SVC_GW_0087).
- [ ] 6.7 Trust-boundary mutants (kill + restore, recorded in evidence):
      scheme validation bypassed for declared marketplaces; diff check
      removed (always-update); secret value included in the update detail.

## 7. Documentation

- [ ] 7.1 `reference/configuration.md`: the `estate` block, exhaustively.
- [ ] 7.2 New `guides/declarative-estate.md` (GitOps-shaped) + `mkdocs.yml`
      nav.
- [ ] 7.3 New `reference/api/estate.md` + nav.
- [ ] 7.4 `concepts/trust-boundaries.md` note (declarative registration goes
      through the same boundary); glossary entry.
- [ ] 7.5 CLAUDE.md: #65/#66 are continuous obligations for future
      API-managed estate state.

## 8. Gates and evidence

- [ ] 8.1 `./mvnw clean verify`
- [ ] 8.2 `(cd src/main/frontend && pnpm e2e)`
- [ ] 8.3 `reqstool status local -p docs/reqstool` → PASS
- [ ] 8.4 `openspec validate --all --strict`
- [ ] 8.5 `mkdocs build --strict`
- [ ] 8.6 `evidence.md` with the final commit SHA.
