# Tasks: add-admin-portal

## 1. reqstool wiring (gate stays green before UI work)

- [x] 1.1 Add GW_0018/GW_0019 + SVC_GW_0018/SVC_GW_0019 to the SSOT
- [x] 1.2 Merge step: combine Java annotations + ui/reqstool/annotations.yml into target/reqstool/annotations-combined.yml during mvnw verify
- [x] 1.3 reqstool_config.yml: point annotations at the combined file; add ui JUnit XML globs to test_results

## 2. UI scaffold and harness

- [x] 2.1 Scaffold ui/ with Vite + React + TS strict + Tailwind + shadcn/ui (registry default primitives) + ESLint
- [x] 2.2 Wire vitest + Testing Library with JUnit reporter; Storybook + axe addon; Playwright with a11y-tree assertions + JUnit reporter
- [x] 2.3 Generate API types + MSW handlers from the springdoc OpenAPI document (Storybook/dev only)
- [x] 2.4 reqstool:tags script producing ui/reqstool/annotations.yml

## 3. Portal implementation (GW_0018, GW_0019)

- [x] 3.1 App shell: layout, nav, session/user surface, error + loading states
- [x] 3.2 Marketplaces page: list, register (client-side Zod validation), ingest action, @Requirements({"GW_0018"})
- [x] 3.3 Snapshots: states, approve/reject actions, provenance view
- [x] 3.4 Audit page: fetch ledger table
- [x] 3.5 Tokens page: create with show-once cleartext, revoke, @Requirements({"GW_0019"})

## 4. Serving integration

- [x] 4.1 frontend-maven-plugin: pinned node+pnpm, pnpm verify + build during mvnw verify, dist → target/classes/static
- [x] 4.2 SPA forward controller for client routes; assets behind OIDC session

## 5. E2E (SVC_GW_0018, SVC_GW_0019)

- [x] 5.1 compose.e2e.yaml: PostgreSQL + navikt/mock-oauth2-server
- [x] 5.2 Playwright specs with real OIDC login: register→ingest→approve workflow (@SVCs SVC_GW_0018), token create/revoke show-once (@SVCs SVC_GW_0019)
- [x] 5.3 pnpm e2e orchestration script (compose up → boot jar → playwright → teardown)
- [x] 5.4 ci.yml: e2e + traceability gate in the gates job (browsers installed before verify)

## 6. Verification

- [x] 6.1 `./mvnw verify` (incl. UI gates) green; `reqstool status local -p docs/reqstool` 19/19 PASS after e2e results; `openspec validate --all --strict`

## 7. Owner-directed iteration (Scalar, inventory, forge metadata, restyle)

- [x] 7.1 Replace Swagger UI with Scalar at /docs (com.scalar.maven:scalar, purple theme); springdoc -api artifact only
- [x] 7.2 Enrich OpenAPI: @Tag/@Operation/@ApiResponse on all endpoints, expanded @OpenAPIDefinition
- [x] 7.3 GW_0020 snapshot content inventory: /api/snapshots/{id}/content + SnapshotContentService + SVC_GW_0020 test; detail page shows plugins/skills
- [x] 7.4 GW_0021 forge metadata (GitHub/GitLab/Gitea, best effort): V2 migration, ForgeMetadataService, SVC_GW_0021 test with fake forge
- [x] 7.5 Restyle to agentgateway-inspired layout: grouped sidebar nav, purple accent, breadcrumb bar, dark-mode toggle (next-themes), overview page, marketplace detail route
- [x] 7.6 dev-insecure-auth toggle (off by default) for local UI work; DevAuthTests
- [x] 7.7 Fix annotations merge to read per-source-set processor outputs (clean builds)
- [x] 7.8 GW_0022 admin-action audit: register/ingest/approve/reject/token events appended to the ledger with the acting identity; SVC_GW_0022 test
