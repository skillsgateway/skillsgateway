# Agentic-first frontend stack

- **Status:** accepted
- **Date:** 2026-08-13
- **Deciders:** jimisola

## Context and Problem Statement

The portal (ADR 0002: SPA served by the app as its own BFF, monorepo `ui/`)
will be developed agent-first: the primary design constraint is a **closed
SDLC loop** — an agent must be able to build → run → inspect → interact →
screenshot → test → diagnose → modify → repeat, without a human acting as
eyes and hands. Stack choices are therefore optimized for agent fluency,
in-repo inspectability, and machine-verifiable feedback, not for human
framework preference.

## Decision

### Stack

| Layer | Choice | Rationale |
|---|---|---|
| Language | TypeScript (strict) | Compile-time feedback is the agent's fastest reviewer |
| Framework | React | Deepest agent/training-data fluency |
| Build | Vite | Minimal conceptual surface, fast HMR; **no Next.js** — the app-as-BFF serves static assets, SSR machinery is dead weight |
| Styling | Tailwind CSS | Styling visible in markup — agent-legible |
| Components | **shadcn/ui** — copy-in source, on **one** headless primitive layer: whatever shadcn's registry ships by default at portal-start (Radix or Base UI — verify then; never mix two primitive layers) | In-repo component source beats documentation for agents; MIT copy-in has no commercial-tier traps (contrast MUI X) |
| Icons | Lucide | Consistent, simple |
| Server state | TanStack Query | Same-origin `/api` + session cookie (BFF) |
| Client state | React state; Zustand only if a real need appears | Boring beats clever for agent maintenance |
| Forms | React Hook Form + Zod | Structured, machine-checkable validation |
| Routing | React Router or TanStack Router — chosen by the implementing change; typed routes (TanStack) vs training-data depth (RR) both defensible | |
| API mocking | MSW, **contract-derived only**: handlers/types generated from the backend's OpenAPI (springdoc), never hand-authored; mocks allowed in Storybook/dev, **never in the acceptance path** | A hand-written mock is a second API implementation that drifts silently |
| Package manager | pnpm (pinned via frontend-maven-plugin, ADR 0002) | |

### Verification harness (the load-bearing half)

- **Playwright** is the agent's browser and the outer loop: real-browser
  e2e against the running app (Arconia-provisioned backend), with
  **accessibility-tree snapshots as the primary interaction/assertion
  channel** (role/name queries, deterministic) and **screenshots as the
  visual channel** (agent vision + `toHaveScreenshot` baselines for
  regression). Playwright MCP / claude-in-chrome give the agent eyes and
  hands interactively; the same assertions run headless in CI.
- **Storybook** as the inner loop: stories enumerate component states
  (empty/loading/populated/error, per viewport), play-functions +
  test-runner execute them headlessly, **axe/a11y addon fails on
  violations** — machine-verifiable UI quality.
- **vitest + Testing Library** for component/unit tests; strict TS +
  ESLint as edit-time gates.
- **Baseline discipline:** visual snapshots are only updated when the UI
  change is intentional; baseline diffs are what the human reviews.

### Contracts and rules — no second spec system

- **UI contracts are reqstool requirements + SVCs**, not a parallel
  format: portal requirements get `GW_*` IDs, acceptance criteria become
  GIVEN/WHEN/THEN SVCs, and Playwright/vitest tests carry `@SVCs` JSDoc
  tags via **reqstool-typescript-tags** — `reqstool status` is the closed
  loop's exit gate for UI and backend alike, and OpenSpec changes drive
  the work as everywhere else in this repo.
- **Agent engineering rules live where the agent runtime loads them**: the
  repo `CLAUDE.md` and a UI-engineering skill under `.claude/skills/` —
  not a bespoke `.agent/` convention. Rules include: shadcn components
  only (no second component library), semantic HTML, accessible names on
  every control, loading/empty/error states on every page, a Playwright
  test per user workflow, no arbitrary CSS where a design token exists,
  and no baseline updates to make tests pass unless the change is
  intentional.

## Consequences

- The harness is built **with the first component, not retrofitted**: the
  future portal change's task groups start with Storybook + Playwright +
  axe wiring.
- springdoc/OpenAPI exposure on the backend becomes a prerequisite for the
  portal change (feeds MSW and TS type generation).
- Two decisions deliberately deferred to portal-start, to be resolved by
  the implementing change and recorded there: shadcn's primitive layer
  (follow the registry default) and the router choice.
