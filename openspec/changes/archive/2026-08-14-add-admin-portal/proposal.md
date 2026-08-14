# Proposal: add-admin-portal

## Why

Every admin operation today requires raw HTTP calls. The portal is the chosen next
track (issue #4 step 4; ADR 0003 is execute-ready and its prerequisite — springdoc
OpenAPI exposure — landed with add-ci-and-packaging). Per ADR 0003 the verification
harness is built with the first component, not retrofitted.

## What Changes

- `ui/` workspace: Vite + React + TypeScript (strict) + Tailwind + shadcn/ui,
  TanStack Query, React Router — the ADR 0003 stack.
- Portal pages for the existing admin workflows: marketplace registration + listing,
  ingestion trigger, snapshot approve/reject, provenance, audit log, and PAT
  self-service (create/revoke, show-once).
- Maven integration (frontend-maven-plugin, pnpm): `./mvnw verify` builds the UI,
  runs its gates (tsc, ESLint, vitest), and packages the bundle into the boot jar,
  served by the app as its own BFF.
- Verification harness: vitest + Testing Library (unit/component), Storybook + axe,
  Playwright e2e in a real browser against the running gateway with PostgreSQL and a
  mock OIDC IdP (navikt/mock-oauth2-server) — a real login flow, no mocks in the
  acceptance path. MSW handlers are generated from the springdoc OpenAPI contract and
  used in Storybook/dev only.
- reqstool restructure to the designed multi-module shape: `docs/reqstool` becomes
  the system SSOT with two implementations — `docs/reqstool-gateway` (backend
  annotations + surefire results) and `ui/reqstool` (TS annotations via
  @reqstool/reqstool-typescript-tags + vitest/Playwright JUnit results).
- New requirements GW_0018 (portal marketplace/snapshot administration) and GW_0019
  (portal PAT self-service) with SVC_GW_0018/0019.

## Capabilities

### New Capabilities

- `admin-portal`: browser portal for marketplace/snapshot administration and PAT
  self-service (GW_0018, GW_0019).

### Modified Capabilities

(none — the HTTP API is unchanged)

## Impact

- New: `ui/` (app + harness), `docs/reqstool-gateway/`, `ui/reqstool/`, e2e compose
  file, Playwright/Storybook/vitest configs.
- Changed: `docs/reqstool/requirements.yml` (variant system + implementations),
  `pom.xml` (frontend-maven-plugin), `.github/workflows/ci.yml` (portal e2e job),
  `SecurityConfig` (serve SPA + static assets behind OIDC).
