---
name: Skills Gateway Portal
description: An operator's console for quarantining, vetting and approving AI agent skill marketplaces — dense, factual, violet-accented.
colors:
  background: "oklch(1 0 0)"
  foreground: "oklch(0.145 0 0)"
  card: "oklch(1 0 0)"
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
  chart-1: "oklch(0.87 0 0)"
  chart-2: "oklch(0.556 0 0)"
  chart-3: "oklch(0.439 0 0)"
  chart-4: "oklch(0.371 0 0)"
  chart-5: "oklch(0.269 0 0)"
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
  2xl: "18px"
  4xl: "26px"
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
  button-secondary:
    backgroundColor: "{colors.secondary}"
    textColor: "{colors.secondary-foreground}"
    rounded: "{rounded.lg}"
    padding: "0 10px"
    height: "32px"
  button-destructive:
    textColor: "{colors.destructive}"
    rounded: "{rounded.lg}"
    padding: "0 10px"
    height: "32px"
  button-sm:
    rounded: "{rounded.md}"
    padding: "0 10px"
    height: "28px"
  button-xs:
    rounded: "{rounded.md}"
    padding: "0 8px"
    height: "24px"
  input:
    backgroundColor: "transparent"
    textColor: "{colors.foreground}"
    rounded: "{rounded.lg}"
    padding: "4px 10px"
    height: "32px"
  card:
    backgroundColor: "{colors.card}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.xl}"
    padding: "16px"
  badge-default:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.primary-foreground}"
    rounded: "{rounded.4xl}"
    padding: "2px 8px"
    height: "20px"
  badge-secondary:
    backgroundColor: "{colors.secondary}"
    textColor: "{colors.secondary-foreground}"
    rounded: "{rounded.4xl}"
    padding: "2px 8px"
    height: "20px"
  badge-destructive:
    textColor: "{colors.destructive}"
    rounded: "{rounded.4xl}"
    padding: "2px 8px"
    height: "20px"
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
  table-header-cell:
    textColor: "{colors.foreground}"
    padding: "0 8px"
    height: "40px"
    typography: "{typography.title}"
---

# Design System: Skills Gateway Portal

<!--
Written by `/impeccable document` in scan mode against the implemented portal
(`src/main/frontend/`), and refreshed by a later scan-mode run after the portal
gained tables, a three-state theme control and the snapshot content diff. It
records the system that exists; it does not propose a new one.

**Precedence.** `.claude/skills/design-conventions/SKILL.md` and ADR 0003
(`docs/decisions/0003-agentic-first-frontend-stack.md`) are the repository's
authority. Where Impeccable's default taste disagrees with them, the repository
wins and this file says so explicitly. Two such disagreements are recorded below,
under Do's and Don'ts.

**On the refresh.** The previous revision asserted two rules the primitives never
obeyed — that nothing is a pill, and that badges are 8px. The shadcn `Badge` has
always been a pill, and cards have always used a 1px ring at a 14px radius. Those
statements described intent rather than code and have been corrected against the
implementation. `design-conventions` specifies the hand-rolled **stat chip**
(`rounded-md border bg-muted`) and says nothing about the Badge primitive, so both
shapes are legitimate and both are now documented.
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
red, marks *what the system objects to*. Nothing else is coloured — the chart ramp is
deliberately five steps of grey, so even data visualisation cannot introduce a third
voice. A screen that starts sprouting an accent has stopped being a control room and
started being a dashboard skin.

The tone is unhedged. Copy states what happened and what will be refused, in the
domain's own vocabulary — quarantine, held, revoked, waiver, ledger — with no
softening. The one place the system permits itself emphasis is the moment risk is
being accepted: a waived finding never renders as clean, and an approval that only
cleared because of a waiver says so on its badge.

**Key Characteristics:**

- Greyscale field, single violet accent, red reserved for objection and destruction.
- Flat: no `shadow-*` class appears anywhere in the portal. Structure comes from
  hairlines, tonal fills, and one 1px ring on cards.
- Dense, small type (14px body, 32px controls, 40px table headers) — an operator's
  density, not a marketing site's.
- Every state is designed: loading, empty, and error exist on every page.
- Light, dark **and system** are equal citizens; nothing is legible in only one.

## Colors

A near-pure greyscale field with one violet voice and one red objection, all
authored in OKLCH so light and dark stay perceptually matched. Tokens are declared
once in `src/main/frontend/src/index.css` — Tailwind v4 CSS-first configuration, no
`tailwind.config`.

### Primary

- **Gateway Violet**: the system's single voice. It fills the active sidebar item,
  sets the uppercase breadcrumb, tints the sidebar mark and the passing verdict icon,
  and fills the primary button and the default badge. It *brightens* in dark mode
  (`oklch(0.606 0.25 292.717)`) rather than dimming, so the accent keeps the same
  presence on a dark field. The focus ring is the same hue at 50% alpha.

### Neutral

- **Paper / Ink**: page background and body text, inverted between themes. Cards sit
  at `oklch(0.205 0 0)` in dark mode — a lifted tone, not a shadow.
- **Muted**: the fill behind stat chips, code blocks, inline evidence panels, table
  hover and table footers; also the hover state for outline and ghost buttons.
- **Muted Foreground**: every secondary line — page descriptions, group labels,
  timestamps, hints, monospace metadata, "—" placeholders.
- **Hairline**: all borders and dividers, including input strokes. In dark mode it
  becomes `oklch(1 0 0 / 10%)` — a translucent white rather than a grey, so it reads
  against every surface tone.
- **Chart ramp** (`chart-1` … `chart-5`, `oklch(0.87 0 0)` down to `oklch(0.269 0 0)`):
  five steps of pure grey. There is no categorical colour scale, by design.

### Tertiary

- **Objection Red**: failing and erroring verdicts, `role="alert"` error text, the
  revoked and failed badges, and destructive buttons — which use it as *text on a 10%
  tint*, never as a solid fill. It also lightens in dark mode
  (`oklch(0.704 0.191 22.216)`).

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

**The Grey Data Rule.** Data visualisation uses the grey chart ramp. A chart that
needs to distinguish more than five series needs a different chart, not a new hue.

## Typography

**Display Font:** Geist Variable (with `sans-serif` fallback), self-hosted via
`@fontsource-variable/geist`
**Body Font:** Geist Variable — the same face throughout; `--font-heading` is
deliberately aliased to `--font-sans`
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
- **Title** (500, 14px / `text-sm font-medium`): control labels, the name column of a
  table row, and table header cells. Card titles are one step up at 16px
  (`text-base font-medium`).
- **Body** (400, 14px / `text-sm`): everything the reader reads. Secondary body is
  the same size in `text-muted-foreground`.
- **Label** (600, 11px, +0.05em, uppercase / `text-[11px] uppercase tracking-wider`):
  sidebar group labels and the top-bar breadcrumb. This is the system's signature
  type move.
- **Mono** (400, 12px / `font-mono text-xs`): commit SHAs, rule identifiers, finding
  locations, cursor positions, ledger cells, diff paths, and show-once secrets —
  anything the reader may copy or compare character by character.

### Named Rules

**The Machine Value Rule.** If a value is produced by a machine and compared by a
human — a SHA, a rule id, a path, a cursor — it is monospace and it is truncated
visibly (`sha.slice(0, 12)`), never wrapped mid-token.

**The One Description Rule.** A page gets exactly one muted sentence under its `h1`.
Anything longer belongs in the docs site, not on the console.

**The Mobile Input Exception.** Inputs render at 16px below the `md` breakpoint and
14px above it (`text-base md:text-sm`). This is the one place the type scale bends,
and it exists solely to stop iOS zooming the viewport on focus.

## Layout

A fixed two-pane shell. A 240px sidebar (`w-60`) holds the brand row, grouped
navigation, and the signed-in identity pinned to the bottom behind a top border. The
right pane is a bordered top bar (breadcrumb left, theme control right) above a
content column centred at `max-w-6xl` with `px-6 py-8`.

Within the content column the rhythm is `space-y-6` (24px) between page-level blocks,
`space-y-3` inside a section, and `space-y-2` between a label and its control. Forms
lay out as `flex flex-wrap items-end gap-3`, so every field and the submit button
share one baseline and wrap together on narrow viewports. Tables are full-bleed
within their section, with a right-aligned actions column.

Density is intentional: controls are 32px tall, table rows are compact, and the page
prefers to show one more row over adding breathing room. The layout is not
mobile-first — this is a desk tool — but nothing may overflow horizontally, and
long values (URLs, SHAs) wrap with `break-all` rather than pushing the layout wide.

## Elevation & Depth

**Flat, and verifiably so: no `shadow-*` utility appears anywhere in
`src/main/frontend/src/pages/` or `components/`.** Depth is expressed by three things
only: a hairline border, a tonal fill (`bg-muted`, `bg-muted/40`, `bg-muted/50`,
`bg-sidebar`), and — in dark mode — a card surface one step lighter than the page.

Cards are the one nuance. They separate with `ring-1 ring-foreground/10` rather than
a `border`: visually a hairline, mechanically a ring. It reads as a border and should
be treated as one. Dialogs are the other exception, and their separation comes from
the primitive's overlay, not from a shadow the design system authors.

### Named Rules

**The Flat Field Rule.** If a surface needs to feel separate, give it a border, a
ring, or a tonal fill. Adding a `box-shadow` to a card, table, or panel is a
deviation from the system, not a refinement of it — and the absence of any
`shadow-*` class in the portal is the test.

## Shapes

One radius family, derived from `--radius: 0.625rem`: 10px on buttons, inputs and
dialogs (`rounded-lg`), 14px on cards (`rounded-xl`), 8px on stat chips and nav items
(`rounded-md`, the most-used step by a wide margin), and 6px where something small
sits inside something small. Small buttons step *down* to `rounded-md` as they
shorten, so the corner never looks disproportionate on a 24–28px control.

**Badges are the deliberate outlier**: `rounded-4xl` (26px) on a 20px-tall element,
which renders as a full pill. Status is the one thing on screen that should read as a
token rather than a panel, and the pill is what distinguishes it at a glance from the
square-shouldered stat chip sitting beside it.

Recurring silhouettes: the **row card** (ringed, icon + title + status badge on the
left, stat chips centre, action button hard right), the **stat chip** (`rounded-md
border bg-muted px-2 py-0.5 text-xs`), the **status pill** (the Badge primitive), and
the **evidence panel** (`rounded-md border bg-muted/40 p-3`) that opens inline
beneath the finding it belongs to.

## Components

All primitives are shadcn/ui built on Base UI. There is no second component library
and no second primitive layer; a missing component is added with
`pnpm dlx shadcn@latest add <name>`, never hand-rolled. Icons are lucide-react only.
Tables use TanStack Table v9 for state (sorting, filtering, row expansion) over the
shadcn table primitives for presentation.

### Buttons

- **Shape:** softly rounded (10px, `rounded-lg`), stepping down to `rounded-md` at
  the `sm`, `xs` and small icon sizes.
- **Size scale:** 24px (`xs`), 28px (`sm`), 32px (default), 36px (`lg`), plus square
  `icon`, `icon-xs`, `icon-sm` and `icon-lg` counterparts. Horizontal padding is 10px
  (`px-2.5`), tightening to 8px at `xs`.
- **Primary:** violet fill, near-white label, hover to 80% opacity. One per screen
  region.
- **Outline:** hairline border on the page background, hover to muted — the default
  for secondary and per-row actions (Ingest, Provenance, Replay, Restore). In dark
  mode it gains a faint `input/30` fill so the control reads against the card.
- **Secondary:** grey fill whose hover is mixed in OKLCH
  (`color-mix(in oklch, var(--secondary), var(--foreground) 5%)`) rather than an
  opacity shift, so it darkens correctly in both themes.
- **Destructive:** red text on a 10% red tint (see the Quiet Destruction Rule).
- **Ghost / Link:** ghost for icon-only chrome such as the back arrow; link for
  inline navigation.
- **Hover / Focus / Press:** all variants share a 3px `ring-ring/50` focus ring plus
  a border shift. Pressing translates the control down one pixel — except on
  popup triggers (`aria-haspopup`), which stay put so the menu does not appear to
  jump. Disabled drops to 50% opacity and removes pointer events.

### Badges

Status is always a badge, never coloured body text. A 20px-tall pill at 12px medium
type: violet-filled default for the healthy terminal state (approved, active,
enabled, delivered), `secondary` grey for in-between or accepted-risk states (held,
waived, disabled, pending), `destructive` red-on-tint for revoked/rejected/failed,
`outline` for unknown, plus `ghost` and `link` variants for interactive use. Badge
text is lowercase — it echoes the API's own state vocabulary verbatim.

### Inputs / Fields

- **Style:** transparent field, hairline `border-input`, 10px radius, 32px tall,
  16px text below `md` and 14px above (see The Mobile Input Exception); in dark mode
  a faint `input/30` fill so the field reads against the card.
- **Focus:** border moves to the ring colour and a 3px `ring-ring/50` halo appears.
- **Error:** `aria-invalid` turns the border and ring destructive; the message
  renders beneath the field as `role="alert"` destructive text.
- **Disabled:** 50% opacity, `cursor-not-allowed`, no pointer events, and a `input/50`
  fill (`input/80` in dark) so the field itself reads as inert.
- Every field is bound to a `<Label htmlFor>`. An unlabelled input is a build
  failure, not a style nit.
- A `Field` / `FieldGroup` primitive exists in `components/ui/field.tsx` but is
  **imported nowhere**; forms compose `Label` + `Input` directly. Treat it as
  available, not as the established pattern.

### Forms

The system's hard rule, and its most visible behavioural signature: **a submit
control is disabled until every required input is valid**, and the disabled state is
explained by a persistent muted hint bound with `aria-describedby`. The client rule
mirrors the server's validation and never invents a stricter one; whitespace is never
valid input; an in-flight submit keeps the control disabled and swaps its label to
the present participle ("Creating…", "Registering…"). `src/main/frontend/src/pages/tokens.tsx`
is the canonical implementation. The full rule lives in
`.claude/skills/design-conventions/SKILL.md`.

### Tables

The portal's densest surface, on the marketplaces and audit pages. Header cells are
40px tall (`h-10 px-2`), left-aligned, 14px medium in full-strength foreground — not
muted, because column names are read as often as the data. Rows carry a bottom
hairline and hover to `bg-muted/50`; the last row drops its border. Footers invert
into `border-t bg-muted/50`.

Rows expand in place: the trigger carries `aria-expanded`, and the row holds
`bg-muted/50` while open (`has-aria-expanded:bg-muted/50`), so an opened row stays
visually attached to the detail it revealed. Captions sit below the table in muted
14px.

### Navigation

Grouped sidebar. Each group is an 11px uppercase muted label over a list of 14px
medium items with a 16px leading icon. The active item is filled
`bg-sidebar-primary` with near-white text and keeps that fill on hover. The top bar
carries the breadcrumb as violet uppercase micro-type, and the theme control.

### Theme control

A single cycling button, not a switch: **system → light → dark → system**, defaulting
to system so the portal follows the OS until told otherwise. The icon states the
current mode rather than the next one (`Monitor` / `Sun` / `Moon`, lucide). The
choice persists in `localStorage` via `next-themes`. Because system is a first-class
state, no surface may be verified in only one rendered theme.

### Vetting report (signature component)

The screen the product exists for. A section per connector: verdict icon, connector
name, verdict badge, then its findings as one dense line each — severity badge,
monospace rule id, monospace location, message. A high-severity finding carries a
"Waive…" button that opens the evidence panel (`rounded-md border bg-muted/40 p-3`)
inline, directly beneath the finding, so the justification is written while looking at
what is being justified. A waived finding keeps its row but strikes the rule id
through and replaces the button with a "waived by … until …" badge. Accepted risk is
never rendered as absence.

### Snapshot content diff (signature component)

Compares an incoming snapshot against the last approved one. Its notable property is
what it *doesn't* do: there is **no green/red add-remove colouring**. The whole
component draws from `text-primary`, `text-muted-foreground`, `text-destructive` and
`bg-muted` only — additions and removals are distinguished by label and monospace
path, not by hue. A reviewer reading it under audit conditions gets the same single
accent as every other screen, and red keeps meaning "the system objects" rather than
"a line was deleted".

## Do's and Don'ts

### Do:

- **Do** take every colour from a token class, and verify all three theme states
  (system, light, dark) before calling a surface done.
- **Do** render loading, empty, and error states on every page; error text is
  `role="alert"` in `text-destructive`.
- **Do** give every control an accessible name, and `aria-label` anything icon-only
  or repeated per row (`Revoke token ci-runner`, `Approve snapshot 12`).
- **Do** disable a submit control until its form is valid, and explain the disabled
  state with an `aria-describedby` hint.
- **Do** show machine values in monospace, truncated to a readable prefix.
- **Do** use the Badge primitive for status and the stat chip for counts. The pill
  and the square shoulder are how a reader tells a state from a number.
- **Do** surface the server's `ProblemDetail` message in a toast when a mutation
  fails; the server is authoritative and its wording is the truth.

### Don't:

- **Don't** add a shadow, a gradient, a second accent colour, or a second typeface.
  The greyscale-plus-violet field is the identity.
- **Don't** introduce a categorical colour scale for charts. The grey ramp is the
  answer.
- **Don't** add a second component library or primitive layer beside shadcn/Base UI,
  and don't hand-roll a primitive that shadcn ships.
- **Don't** soften domain vocabulary. It is "quarantine", "revoked" and "waiver" on
  screen because that is what it is in the ledger.
- **Don't** render an accepted risk as a clean result, and don't offer an approval
  control without the evidence above it.
- **Don't** let a control fail on press when it could have been disabled with a
  reason.
- **Don't** colour a diff green and red. See the snapshot content diff above.
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
