# Design: marketplaces-table-and-audit-clarity

## Decision 1 — `@tanstack/react-table` for the data tables

The marketplaces and audit tables need sorting, per-column filtering, row
expansion and pagination. The portal already depends on `@tanstack/react-query`;
`@tanstack/react-table` is the headless companion — it owns the row model and
leaves the markup to the existing shadcn `@/components/ui/table` primitives, which
is the shadcn DataTable pattern. It is added at `8.21.3` (v8, the current stable
line). No second table or component library is introduced.

## Decision 2 — status colour comes from the existing theme, not a new palette

Issue #221 asks for "green = pass, amber = warn, red = fail". The portal's theme
(`src/index.css`) has **no green or amber token** — its verdict language, set in
`vetting-report.tsx`, is the purple accent (`--primary`) for pass, `destructive`
red for fail, and muted/secondary for warn. `OutcomeBadge` already draws a blocked
snapshot with `variant="destructive"`.

So a ledger row is coloured in that existing language rather than a literal green:
`blocked → destructive` (the same red as the marketplace card — which is the point
of #221), `clear → primary`, `warn → secondary/muted`, everything else neutral and
uncoloured. A blocked row additionally gets a faint `bg-destructive/5` wash and a
`border-l-destructive` accent so it is findable in a scroll. This keeps
design-conventions (purple accent, existing tokens), which outranks the task's
palette suggestion. **Open question for the owner:** if literal green/amber is
wanted, we add `--success` / `--warning` tokens to the theme (light + dark) — a
separate, deliberate design decision, not smuggled in here.

## Decision 3 — status is derived, read-only, and does not correct the ledger

The status is read from the two columns the server already writes: `event`, and
for a `vetting-completed` row the `outcome=` embedded in its free-text `detail`.
This is a legibility aid, not a new source of truth — the authoritative outcome is
still the vetting record. The lossy `detail` shape and the `human` actor-type on
automated `vetting` principals are #221 backend concerns and are deliberately left
alone; the mapping lives in `src/lib/audit-status.ts` with that boundary stated.

## Decision 4 — expand-in-place *and* a detail page, deliberately

The marketplace row expands to its snapshot review table (fast triage without
leaving the list) **and** the name links to the full `/marketplaces/:name` detail
page (snapshots, vetting reports, plugin/skill inventory, and now the marketplace's
audit slice). The two are not redundant: the inline expansion is for acting on
snapshots in context; the detail page is the whole record. **Open question for the
owner:** if this is felt to be two doors to the same room, we can demote one — drop
the inline expansion in favour of the detail page, or vice-versa.

## Decision 5 — audit pagination is client-side over `/api/audit`

The task suggested paginating against the NDJSON export cursor
(`GET /api/audit/export`, ~5s settling lag). This change instead paginates
client-side with react-table over the existing `GET /api/audit` JSON endpoint,
which already returns the ledger as rows. Reasons: it is a single data path (the
page already loads `/api/audit`), it keeps sort/filter/paginate consistent in one
model, and it avoids a second, lagged, NDJSON-parsing fetch path in the browser for
a surface an operator reads interactively. The NDJSON export with its resumable
cursor remains the path for a **full** programmatic pull (the download button and
sinks are unchanged). **Open question for the owner:** if the ledger is expected to
grow beyond what is comfortable to load at once, we move to server-side cursor
pagination — a follow-up that also wants a bounded/paged `/api/audit`.

## Decision 6 — duplicate URL is a warning, not a block

Registering the same upstream under two names is legitimate (it is how a
marketplace is tested), and the server accepts it. So the client detects the
collision (normalising scheme/host/case, dropping a trailing slash and `.git`) and
warns, holding Register shut until the operator acknowledges — a deliberate choice,
never a silent success and never a hard refusal the server would not itself make.
The normalisation is a client-side aid only; it does not mirror a server rule.

## Open questions (for the owner)

1. **Palette** — keep the theme's purple/red/muted verdict language (this change),
   or introduce literal green/amber `--success` / `--warning` tokens?
2. **Emphasis** — keep both inline expansion and the detail page, or collapse to
   one?
3. **Columns** — the marketplaces table shows name / source / latest snapshot /
   upstream-updated / count; the audit table shows status / when / event /
   principal / marketplace / commit / detail. Are these the right columns, and the
   right default sort (marketplaces by name asc; audit by id desc)?
4. **Audit pagination** — client-side over `/api/audit` (this change) or move to a
   server cursor now?
