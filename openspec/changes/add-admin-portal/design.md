# Design: add-admin-portal

## Context

ADR 0003 fixes the stack and the harness philosophy (closed SDLC loop, harness with the
first component). ADR 0002 fixes the serving model: SPA served by the app as its own
BFF from the boot jar, same-origin `/api`, session cookie, tokens never in the browser.
The backend admin API is complete (GW_0001–GW_0017) and documented via springdoc.

## Goals / Non-Goals

**Goals:** working portal for all current admin workflows; the ADR 0003 harness
operational (vitest, Storybook+axe, Playwright with a11y-tree assertions, real-login
e2e); reqstool as the single exit gate across backend and UI.

**Non-Goals:** Impeccable adoption (advisory design channel — follow-up once the
portal has more surface); visual `toHaveScreenshot` baselines (added when the design
stabilizes); Phase 2 ingestion features; replacing the JSON API.

## Decisions

- **Router: React Router v7 (library mode)** — deferred decision from ADR 0003
  resolved for training-data depth; typed-routes benefit of TanStack Router doesn't
  outweigh it at this page count.
- **shadcn/ui on the registry default primitive layer** (deferred decision resolved:
  whatever `shadcn init` ships today — verified at implementation time; single
  primitive layer, never mixed).
- **Real-login e2e**: `compose.e2e.yaml` runs PostgreSQL + navikt/mock-oauth2-server;
  the gateway runs from the boot jar with `SGW_OIDC_*` pointed at the mock IdP;
  Playwright drives the actual redirect → login → callback flow. No security config
  changes, no test-only auth bypass in the app.
- **PAT-authenticated git fetch stays out of portal e2e** — covered by backend SVCs.
- **reqstool stays one dataset; annotations are merged.** A multi-dataset
  restructure (system + per-module implementations) was considered but rejected for
  now: cross-urn ID resolution would force rewriting every existing `@Requirements`
  annotation as `urn:id`. Instead the UI build scans JSDoc `@Requirements`/`@SVCs`
  tags with `@reqstool/reqstool-typescript-tags` into `ui/reqstool/annotations.yml`;
  a merge script combines it with the Java processor output into
  `target/reqstool/annotations-combined.yml` (both are disjoint-keyed maps), which
  `reqstool_config.yml` points at, and UI vitest/Playwright JUnit XMLs join
  `test_results`. The gate command is unchanged. Revisit the multi-module shape when
  a second *service* appears (as `.reqstool-ai.yaml` anticipates).
- **Maven orchestration**: frontend-maven-plugin pins node/pnpm, runs
  `pnpm install --frozen-lockfile`, `pnpm verify` (tsc, eslint, vitest, tags) during
  `verify`, and copies `ui/dist` into `target/classes/static`. Playwright e2e is NOT
  part of `mvnw verify` (needs Docker orchestration + browser binaries); it runs via
  `pnpm e2e` locally and as a dedicated CI job.
- **SPA serving**: static assets from the jar; client-side routes forwarded to
  `index.html` by a lightweight MVC forward controller; everything behind the OIDC
  session except `/actuator/health` (unchanged).

## Risks / Trade-offs

- [Playwright browser download flakiness in CI] → dedicated job with browser cache;
  failures don't block the fast gate job.
- [Mock IdP drift vs real IdP] → mock-oauth2-server implements the OIDC spec
  (discovery, authorization_code); the app uses explicit provider URIs exactly as in
  production config.
- [Frontend build lengthens `mvnw verify`] → pnpm store cache + CI maven cache; the
  native workflow skips tests entirely, unchanged.
- [reqstool restructure touches the traceability gate] → done first and verified
  green with backend-only results before any UI work lands.

## Migration Plan

Single PR on the stack. Rollback = revert (API untouched). The reqstool restructure
is transparent to consumers of the gate command.

## Open Questions

(none)
