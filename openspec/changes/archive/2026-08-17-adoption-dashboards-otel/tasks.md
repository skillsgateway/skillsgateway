# Tasks: adoption-dashboards-otel

## 1. Traceability (SSOT first)

- [x] 1.1 Add GW_0075–GW_0078 to `docs/reqstool/requirements.yml`
      (adoption reporting; staleness reporting; always-recorded
      observability; the adoption portal page). GW_0073/GW_0074 are
      reserved by another in-flight change.
- [x] 1.2 Add SVC_GW_0075–SVC_GW_0078 to
      `docs/reqstool/software_verification_cases.yml`.

## 2. Backend

- [x] 2.1 `FetchLogRepository`: windowed `upload-pack` aggregation grouped by
      (marketplace, sha) and the latest-fetch-per-(principal, marketplace)
      query; covering partial index in `V1__init.sql`.
- [x] 2.2 `adoption/AdoptionService`: fold aggregation rows into
      per-marketplace summaries with served-tip resolution via
      `GitStorage.publishedIfServing`; compute staleness (latest fetch ≠ tip,
      including no-longer-serving marketplaces). `@Requirements` GW_0075,
      GW_0076.
- [x] 2.3 `adoption/AdoptionController`: `GET /api/adoption?days=`,
      `GET /api/adoption/staleness`, both `requireAuditor` first line,
      OpenAPI annotations, `@Schema` on DTOs.
- [x] 2.4 `observability/GatewayMetrics`: `skills_gateway.ingestion` and
      `skills_gateway.approval` observations, `skills_gateway.facade.fetches`
      counter; wire into `IngestionService`, `ApprovalService`,
      `FetchAuditHook` (additive wrapping only). `@Requirements` GW_0077.

## 3. API artifacts

- [x] 3.1 Regenerate `src/main/frontend/openapi.json` and `types.gen.ts`.

## 4. Tests

- [x] 4.1 Adoption (SVC_GW_0075): two identities fetch two marketplaces
      through the facade with real git; the summary carries fetch counts,
      distinct identities and the per-SHA breakdown; an entry older than the
      window is excluded; the read is auditor-gated (classified in the
      RoleEnforcementTests walk).
- [x] 4.2 Staleness (SVC_GW_0076): an identity that fetched a superseded SHA
      is reported with the served tip; an identity on the tip is not; after
      unpublish, every holder is reported with a null served tip.
- [x] 4.3 Observability (SVC_GW_0077): with the default (no export) config,
      the meter registry carries `skills_gateway.ingestion`,
      `skills_gateway.approval` (per decision) and
      `skills_gateway.facade.fetches` (per event) after the flows run, and a
      blocked approval still surfaces its error.
- [x] 4.4 RoleEnforcementTests: the two new reads added to PRIVILEGED_READS.

## 5. Portal

- [x] 5.1 `useAdoption(days)` / `useStaleness()` queries; MSW handlers typed
      from the regenerated types.
- [x] 5.2 `pages/adoption.tsx`: stat chips, per-marketplace row cards with
      per-SHA tables, staleness table, window selector, loading/empty/error
      states; JSDoc `@Requirements` GW_0075, GW_0076, GW_0078; route +
      Governance nav + breadcrumb; component tests.
- [x] 5.3 Playwright e2e (SVC_GW_0078, snake_case title): mint a PAT in the
      portal, clone through the facade, see the fetch and identity on the
      Adoption page.
- [x] 5.4 `/impeccable audit` + `harden` + `critique` on the new page;
      findings fixed or dismissed in the PR body.

## 6. Documentation

- [x] 6.1 New `docs/manual/reference/api/adoption.md`; nav entry.
- [x] 6.2 New `docs/manual/reference/observability.md` (metric names, how a
      deployment enables export, the local `observability` profile); nav.
- [x] 6.3 `reference/portal.md`: the Adoption page.

## 7. Gates and evidence

- [x] 7.1 `./mvnw clean verify`
- [x] 7.2 `(cd src/main/frontend && pnpm e2e)`
- [x] 7.3 `reqstool status local -p docs/reqstool` → PASS
- [x] 7.4 `openspec validate --all --strict`
- [x] 7.5 `mkdocs build --strict`
- [x] 7.6 `evidence.md` with the final commit SHA.
