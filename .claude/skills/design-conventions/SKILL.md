---
name: design-conventions
description: Skills Gateway portal design system and UI verification rules — shadcn/Base UI components, purple-accent theme, layout patterns, accessibility, and the harness discipline from ADR 0003. Load before creating or changing any portal UI.
---

# Design conventions (portal)

Grounding: `docs/decisions/0003-agentic-first-frontend-stack.md` — the stack and
the verification harness are decided there; this skill is the working style.

## Components & theme

- **shadcn/ui on Base UI primitives only** (`ui/src/components/ui/`) — never
  add a second component library or primitive layer. Missing component? Add it
  with `pnpm dlx shadcn@latest add <name>`.
- Theme tokens live in `ui/src/index.css` (light + `.dark` blocks). Accent is
  violet/purple (`--primary`). Never hardcode colors where a token exists.
- Icons: lucide-react only.
- Dark mode via next-themes (`class` attribute); every surface must work in
  both themes — use token classes (`bg-background`, `text-muted-foreground`…).

## Layout language

- Left sidebar with grouped nav: tiny uppercase group labels
  (`text-[11px] uppercase tracking-wider text-muted-foreground`), active item
  filled with `bg-sidebar-primary`.
- Top bar: uppercase purple breadcrumb left, mode toggle right.
- Content: `max-w-6xl`, page `h1` + one-line muted description, then cards.
- Overview-style pages: row cards with icon + title + status badge, stat chips
  (`rounded-md border bg-muted px-2 py-0.5 text-xs`), action button right.
- States: every page renders loading, empty, and error (`role="alert"`) states.

## Accessibility (hard rules)

- Every control has an accessible name (`aria-label` when icon-only, labels
  bound with `htmlFor`).
- Role/name queries are the contract: Playwright and Testing Library assert
  via the accessibility tree, so semantics are load-bearing.
- Storybook stories run axe with violations as errors — a11y failures fail
  the build.

## Harness discipline

- New component ⇒ story (states enumerated) when it's presentational; new user
  workflow ⇒ Playwright spec with `@SVCs` tag and snake_case title.
- Never update visual baselines or loosen assertions to make tests pass unless
  the change is intentional.
- Client-side validation mirrors but never replaces server policy (the server
  is authoritative; surface its ProblemDetail messages via toasts).
