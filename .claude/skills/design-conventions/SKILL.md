---
name: design-conventions
description: Skills Gateway portal design system and UI verification rules — shadcn/Base UI components, purple-accent theme, layout patterns, accessibility, and the harness discipline from ADR 0003. Load before creating or changing any portal UI.
---

# Design conventions (portal)

Grounding: `docs/decisions/0003-agentic-first-frontend-stack.md` — the stack and
the verification harness are decided there; this skill is the working style.

## Components & theme

- **shadcn/ui on Base UI primitives only** (`src/main/frontend/src/components/ui/`) — never
  add a second component library or primitive layer. Missing component? Add it
  with `pnpm dlx shadcn@latest add <name>`.
- Theme tokens live in `src/main/frontend/src/index.css` (light + `.dark` blocks). Accent is
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

## Forms (hard rules)

Canonical implementation: `src/main/frontend/src/pages/tokens.tsx`. Copy its shape
rather than inventing a new one.

- **Disabled until valid.** A submit control is disabled until every required
  input holds a valid value. A control that the server would reject must never
  be pressable — "press it and read the toast" is not a validation strategy.
- **The client mirrors the server, exactly.** Derive each client rule from the
  controller/request record that handles the call (name patterns, required
  fields, expiry-in-the-future, …). Never stricter, never looser.
  - The one allowed exception is a rule the client *cannot* know: a
    server-configurable allowlist or a server-side registry. Mirror the
    invariant part, leave the rest to the server, and say so in a code comment
    naming what the server owns.
- **Whitespace is never valid.** Trim before testing emptiness and before
  sending; `"   "` leaves the control disabled.
- **The disabled state is explained.** Every such form carries a persistent
  muted hint (`text-xs text-muted-foreground`) bound to the input(s) with
  `aria-describedby`, stating what is required. A disabled control with no
  reason on screen is an accessibility defect.
- **In-flight submits stay disabled**, with the label switched to the present
  participle ("Creating…", "Registering…", "Recording…").
- **Field-level errors** stay `role="alert"` `text-destructive` beneath the
  field; the hint and the error are different things and both may be present.
- **Test it per form**, next to the existing component tests: disabled when
  empty, still disabled on whitespace-only, enabled once valid. The e2e spec for
  a creation flow asserts the initial disabled state before filling anything.

## Impeccable

The Impeccable design harness is installed at `.claude/skills/impeccable`, with
`PRODUCT.md` and `DESIGN.md` at the repository root recording this product and
this design system. `DESIGN.md` is generated from the code, not aspirational.

- **Any PR that adds or changes a portal page runs `/impeccable audit` and
  `/impeccable harden` on the affected surfaces**; a PR that adds a *new* page
  also runs `/impeccable critique`.
- Findings are either fixed in the same PR or explicitly dismissed in the PR
  body with a reason. "Ran it, addressed nothing, said nothing" is not a pass.
- **This skill and ADR 0003 outrank Impeccable.** Where its taste conflicts with
  the conventions here, the repository wins and `DESIGN.md`'s closing section
  records the deviation. In particular the expressive playbooks (`bolder`,
  `delight`, `overdrive`, `colorize`, `animate`) are not run against this
  product unless the owner asks for them by name.
- Impeccable never edits `docs/reqstool/`, requirement annotations, or the
  server; it is a UI-craft tool operating inside the boundaries above.

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
