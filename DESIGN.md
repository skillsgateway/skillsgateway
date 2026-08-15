---
name: Skills Gateway Portal
description: An operator's console for quarantining, vetting and approving AI agent skill marketplaces — dense, factual, violet-accented.
colors:
  background: "oklch(1 0 0)"
  foreground: "oklch(0.145 0 0)"
  primary: "oklch(0.541 0.247 293.01)"
  primary-foreground: "oklch(0.985 0 0)"
  secondary: "oklch(0.97 0 0)"
  secondary-foreground: "oklch(0.205 0 0)"
  muted: "oklch(0.97 0 0)"
  muted-foreground: "oklch(0.556 0 0)"
  destructive: "oklch(0.577 0.245 27.325)"
  border: "oklch(0.922 0 0)"
  input: "oklch(0.922 0 0)"
  ring: "oklch(0.606 0.25 292.717)"
  sidebar: "oklch(0.985 0 0)"
  sidebar-primary: "oklch(0.541 0.247 293.01)"
typography:
  display:
    fontFamily: "Geist Variable, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: "2rem"
    letterSpacing: "normal"
  headline:
    fontFamily: "Geist Variable, sans-serif"
    fontSize: "1.125rem"
    fontWeight: 600
    lineHeight: "1.75rem"
  title:
    fontFamily: "Geist Variable, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 500
    lineHeight: "1.25rem"
  body:
    fontFamily: "Geist Variable, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: "1.25rem"
  label:
    fontFamily: "Geist Variable, sans-serif"
    fontSize: "0.6875rem"
    fontWeight: 600
    letterSpacing: "0.05em"
  mono:
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace"
    fontSize: "0.75rem"
    fontWeight: 400
rounded:
  sm: "6px"
  md: "8px"
  lg: "10px"
  xl: "14px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  page: "32px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.primary-foreground}"
    rounded: "{rounded.lg}"
    padding: "0 10px"
    height: "32px"
    typography: "{typography.title}"
  button-outline:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.lg}"
    padding: "0 10px"
    height: "32px"
  button-destructive:
    textColor: "{colors.destructive}"
    rounded: "{rounded.lg}"
    padding: "0 10px"
    height: "32px"
  input:
    backgroundColor: "transparent"
    textColor: "{colors.foreground}"
    rounded: "{rounded.lg}"
    padding: "4px 10px"
    height: "32px"
  stat-chip:
    backgroundColor: "{colors.muted}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.md}"
    padding: "2px 8px"
    typography: "{typography.mono}"
  nav-item-active:
    backgroundColor: "{colors.sidebar-primary}"
    textColor: "{colors.primary-foreground}"
    rounded: "{rounded.md}"
    padding: "6px 10px"
---

# Design System: Skills Gateway Portal

<!--
Written by `/impeccable document` in scan mode against the implemented portal
(`src/main/frontend/`). It records the system that exists; it does not propose a new
one.

**Precedence.** `.claude/skills/design-conventions/SKILL.md` and ADR 0003
(`docs/decisions/0003-agentic-first-frontend-stack.md`) are the repository's
authority. Where Impeccable's default taste disagrees with them, the repository
wins and this file says so explicitly. Two such disagreements are recorded below,
under Do's and Don'ts.
-->

## Overview

**Creative North Star: "The Control Room"**

This is the console of a checkpoint. Everything on screen exists to answer one of
three questions — what came in, what is it, and who let it through — and the surface
is built to be *read at speed by someone who is accountable for the answer*. There is
no hero, no illustration, no persuasion. The chrome is quiet to the point of
disappearing: a neutral greyscale field, hairline borders, flat surfaces, and type
that sits close together because the reader wants more facts per screen, not more
air.

Against that greyscale, exactly one colour carries meaning. Violet (`oklch(0.541
0.247 293.01)`) marks *where you are and what the system is offering you*: the active
navigation item, the breadcrumb, the primary action, the passing verdict. A second,
red, marks *what the system objects to*. Nothing else is coloured. A screen that
starts sprouting a third accent has stopped being a control room and started being a
dashboard skin.

The tone is unhedged. Copy states what happened and what will be refused, in the
domain's own vocabulary — quarantine, held, revoked, waiver, ledger — with no
softening. The one place the system permits itself emphasis is the moment risk is
being accepted: a waived finding never renders as clean, and an approval that only
cleared because of a waiver says so on its badge.

**Key Characteristics:**

- Greyscale field, single violet accent, red reserved for objection and destruction.
- Flat by default: borders and tonal fills carry structure, not shadows.
- Dense, small type (14px body, 32px controls) — an operator's density, not a
  marketing site's.
- Every state is designed: loading, empty, and error exist on every page.
- Light and dark are equal citizens; nothing is legible in only one.

## Colors

A near-pure greyscale field with one violet voice and one red objection, all
authored in OKLCH so light and dark stay perceptually matched.

### Primary

- **Gateway Violet** (`oklch(0.541 0.247 293.01)` light / `oklch(0.606 0.25 292.717)`
  dark): the system's single voice. It fills the active sidebar item, sets the
  uppercase breadcrumb, tints the sidebar mark and the passing verdict icon, and
  fills the primary button. It brightens in dark mode rather than dimming, so the
  accent keeps the same presence on a dark field. The focus ring is the same hue at
  50% alpha.

### Neutral

- **Paper / Ink** (`oklch(1 0 0)` on `oklch(0.145 0 0)` light; inverted dark): page
  background and body text. Cards sit at `oklch(0.205 0 0)` in dark mode — a lifted
  tone, not a shadow.
- **Muted** (`oklch(0.97 0 0)` light / `oklch(0.269 0 0)` dark): the fill behind stat
  chips, code blocks, and inline evidence panels; also the hover state for outline
  and ghost buttons.
- **Muted Foreground** (`oklch(0.556 0 0)` light / `oklch(0.708 0 0)` dark): every
  secondary line — page descriptions, group labels, timestamps, hints, "—" placeholders.
- **Hairline** (`oklch(0.922 0 0)` light / `oklch(1 0 0 / 10%)` dark): all borders and
  dividers, including input strokes.

### Tertiary

- **Objection Red** (`oklch(0.577 0.245 27.325)` light / `oklch(0.704 0.191 22.216)`
  dark): failing and erroring verdicts, `role="alert"` error text, the revoked and
  failed badges, and destructive buttons — which use it as *text on a 10% tint*, never
  as a solid fill.

### Named Rules

**The One Voice Rule.** Violet means "the system is offering you this, or you are
here". It never decorates. A screen shows it on the active nav item, the breadcrumb,
and at most one primary action; a second competing violet element is a bug.

**The Quiet Destruction Rule.** Destructive actions are red *text on a faint red
tint*, never a solid red button. Deleting a snapshot or revoking a token should read
as available and deliberate, not as an alarm the page is sounding at you.

**The No Raw Colour Rule.** Every colour comes from a token class (`bg-muted`,
`text-destructive`, `text-muted-foreground`). Hardcoding a hex or a Tailwind palette
step (`text-red-500`) breaks dark mode and is rejected in review.

## Typography

**Display Font:** Geist Variable (with `sans-serif` fallback)
**Body Font:** Geist Variable — the same face throughout
**Label/Mono Font:** the platform monospace stack, for machine values only

**Character:** One neutral grotesque doing every job, differentiated by size, weight
and case rather than by family. The restraint is the point: in a console, a second
typeface is a second signal the reader has to decode. Personality comes from the
uppercase micro-labels and the monospace facts, not from the letterforms.

### Hierarchy

- **Display** (600, 24px / `text-2xl font-semibold`): the page `h1`, once per page,
  always followed by a single muted description line.
- **Headline** (600, 18px / `text-lg font-semibold`): section headings within a page
  — "Subscribers", "Export sinks", "Ledger".
- **Title** (500, 14px / `text-sm font-medium`): control labels, card titles, and the
  name column of a table row.
- **Body** (400, 14px / `text-sm`): everything the reader reads. Secondary body is
  the same size in `text-muted-foreground`.
- **Label** (600, 11px, +0.05em, uppercase / `text-[11px] uppercase tracking-wider`):
  sidebar group labels and the top-bar breadcrumb. This is the system's signature
  type move.
- **Mono** (400, 12px / `font-mono text-xs`): commit SHAs, rule identifiers, finding
  locations, cursor positions, ledger cells, and show-once secrets — anything the
  reader may copy or compare character by character.

### Named Rules

**The Machine Value Rule.** If a value is produced by a machine and compared by a
human — a SHA, a rule id, a path, a cursor — it is monospace and it is truncated
visibly (`sha.slice(0, 12)`), never wrapped mid-token.

**The One Description Rule.** A page gets exactly one muted sentence under its `h1`.
Anything longer belongs in the docs site, not on the console.

## Layout

A fixed two-pane shell. A 240px sidebar (`w-60`) holds the brand row, grouped
navigation, and the signed-in identity pinned to the bottom behind a top border. The
right pane is a bordered top bar (breadcrumb left, theme toggle right) above a
content column centred at `max-w-6xl` with `px-6 py-8`.

Within the content column the rhythm is `space-y-6` (24px) between page-level blocks,
`space-y-3` inside a section, and `space-y-2` between a label and its control. Forms
lay out as `flex flex-wrap items-end gap-3`, so every field and the submit button
share one baseline and wrap together on narrow viewports. Tables are full-bleed
within their section, with a right-aligned actions column.

Density is intentional: controls are 32px tall, table rows are compact, and the page
prefers to show one more row over adding breathing room. The layout is not
mobile-first — this is a desk tool — but nothing may overflow horizontally, and
long values (`URLs`, SHAs) wrap with `break-all` rather than pushing the layout wide.

## Elevation & Depth

**Flat by default.** The system uses no shadow vocabulary at all. Depth is expressed
by three things only: a hairline border, a tonal fill (`bg-muted`, `bg-muted/40`,
`bg-sidebar`), and — in dark mode — a card surface one step lighter than the page.
Dialogs are the sole exception, and their separation comes from the primitive's
overlay, not from a shadow the design system authors.

### Named Rules

**The Flat Field Rule.** If a surface needs to feel separate, give it a border or a
tonal fill. Adding a `box-shadow` to a card, table, or panel is a deviation from the
system, not a refinement of it.

## Shapes

One radius family, from the `--radius: 0.625rem` root: 10px on buttons, inputs and
dialogs (`rounded-lg`), 8px on chips, badges and nav items (`rounded-md`), 6px where
something small sits inside something small. Nothing is a pill; nothing is square.
Corners are the only softening the system allows itself — every other edge is a 1px
hairline.

Recurring silhouettes: the **row card** (bordered, icon + title + status badge on the
left, stat chips centre, action button hard right), the **stat chip** (`rounded-md
border bg-muted px-2 py-0.5 text-xs`), and the **evidence panel** (`rounded-md border
bg-muted/40 p-3`) that opens inline beneath the finding it belongs to.

## Components

All primitives are shadcn/ui built on Base UI. There is no second component library
and no second primitive layer; a missing component is added with
`pnpm dlx shadcn@latest add <name>`, never hand-rolled. Icons are lucide-react only.

### Buttons

- **Shape:** softly rounded (10px, `rounded-lg`), 32px tall by default, 28px at
  `size="sm"`.
- **Primary:** violet fill, near-white label, hover to 80% opacity. One per screen
  region.
- **Hover / Focus:** all variants share a 3px `ring-ring/50` focus ring plus a border
  shift; pressing translates the control down one pixel. Disabled drops to 50%
  opacity and removes pointer events.
- **Outline:** hairline border on the page background, hover to muted — the default
  for secondary and per-row actions (Ingest, Provenance, Replay, Restore).
- **Destructive:** red text on a 10% red tint (see the Quiet Destruction Rule).
- **Ghost / Link:** ghost for icon-only chrome such as the back arrow; link for
  inline navigation.

### Inputs / Fields

- **Style:** transparent field, hairline `border-input`, 10px radius, 32px tall, 14px
  text; in dark mode a faint `input/30` fill so the field reads against the card.
- **Focus:** border moves to the ring colour and a 3px `ring-ring/50` halo appears.
- **Error:** `aria-invalid` turns the border and ring destructive; the message
  renders beneath the field as `role="alert"` destructive text.
- **Disabled:** 50% opacity, `cursor-not-allowed`, no pointer events.
- Every field is bound to a `<Label htmlFor>`. An unlabelled input is a build
  failure, not a style nit.

### Forms

The system's hard rule, and its most visible behavioural signature: **a submit
control is disabled until every required input is valid**, and the disabled state is
explained by a persistent muted hint bound with `aria-describedby`. The client rule
mirrors the server's validation and never invents a stricter one; whitespace is never
valid input; an in-flight submit keeps the control disabled and swaps its label to
the present participle ("Creating…", "Registering…"). `src/main/frontend/src/pages/tokens.tsx`
is the canonical implementation. The full rule lives in
`.claude/skills/design-conventions/SKILL.md`.

### Badges

Status is always a badge, never coloured body text: violet-filled default for the
healthy terminal state (approved, active, enabled, delivered), `secondary` grey for
in-between or accepted-risk states (held, waived, disabled, pending), `destructive`
for revoked/rejected/failed, and `outline` for unknown. Badge text is lowercase —
it echoes the API's own state vocabulary verbatim.

### Cards / Containers

- **Corner Style:** 10px.
- **Background:** `bg-card` (page-white in light, one step lighter than the page in
  dark).
- **Shadow Strategy:** none — see Elevation & Depth.
- **Border:** 1px hairline.
- **Internal Padding:** 16px, with the header row laid out
  `flex-row items-center justify-between`.

### Navigation

Grouped sidebar. Each group is an 11px uppercase muted label over a list of 14px
medium items with a 16px leading icon. The active item is filled
`bg-sidebar-primary` with near-white text and keeps that fill on hover. The top bar
carries the breadcrumb as violet uppercase micro-type and nothing else but the theme
toggle.

### Vetting report (signature component)

The screen the product exists for. A section per connector: verdict icon, connector
name, verdict badge, then its findings as one dense line each — severity badge,
monospace rule id, monospace location, message. A high-severity finding carries a
"Waive…" button that opens the evidence panel inline, directly beneath the finding,
so the justification is written while looking at what is being justified. A waived
finding keeps its row but strikes the rule id through and replaces the button with a
"waived by … until …" badge. Accepted risk is never rendered as absence.

## Do's and Don'ts

### Do:

- **Do** take every colour from a token class, and verify both themes before calling
  a surface done.
- **Do** render loading, empty, and error states on every page; error text is
  `role="alert"` in `text-destructive`.
- **Do** give every control an accessible name, and `aria-label` anything icon-only
  or repeated per row (`Revoke token ci-runner`, `Approve snapshot 12`).
- **Do** disable a submit control until its form is valid, and explain the disabled
  state with an `aria-describedby` hint.
- **Do** show machine values in monospace, truncated to a readable prefix.
- **Do** surface the server's `ProblemDetail` message in a toast when a mutation
  fails; the server is authoritative and its wording is the truth.

### Don't:

- **Don't** add a shadow, a gradient, a second accent colour, or a second typeface.
  The greyscale-plus-violet field is the identity.
- **Don't** add a second component library or primitive layer beside shadcn/Base UI,
  and don't hand-roll a primitive that shadcn ships.
- **Don't** soften domain vocabulary. It is "quarantine", "revoked" and "waiver" on
  screen because that is what it is in the ledger.
- **Don't** render an accepted risk as a clean result, and don't offer an approval
  control without the evidence above it.
- **Don't** let a control fail on press when it could have been disabled with a
  reason.
- **Don't** update a visual baseline or loosen a role/name assertion to make a test
  pass unless the change was the point of the work.

### Where this system overrules Impeccable's defaults

Two deviations are deliberate, and the repository's conventions win:

1. **No bold expressive layer.** Impeccable's `bolder`, `delight`, `overdrive` and
   `colorize` playbooks push toward distinctive, expressive surfaces. This is an
   Operate-mode security console for an audience that reads it under audit
   conditions; expression here reads as noise and, worse, as a tool trying to
   persuade. Density, consistency and the single accent stay. Those commands should
   not be run against this product without the owner asking for them by name.
2. **No motion vocabulary.** The system's only motion is the primitives' own
   `transition-all` on colour and the one-pixel active-press. Impeccable's `animate`
   guidance is not adopted: animated evidence is harder to trust, and the audience
   is scanning, not being guided.
