# Design: marketplace-task-navigation

## Context

See proposal.md — Why. The portal is a React Router SPA; `SpaController` serves
the shell for a fixed list of paths and a new route that is missing from it
404s on a cold load. `/marketplaces/:name` is one component
(`MarketplaceDetailPage`) rendering every card in sequence; the snapshot card's
open snapshot, tab and file live in the query string (GW_INGEST_0032 —
Addressable snapshot file inspection). The shell's `<main>` is either a centred
`max-w-6xl` column or, for routes with `handle.layout === "wide"`, a full-height
self-scrolling area used by the file-explorer route.

Every number the new surfaces show — awaiting counts, the queue — is already in
`GET /api/v1/marketplaces`, which carries each marketplace's snapshots and the
served commit. The per-snapshot vetting outcome is its own read
(`/snapshots/{id}/vetting`), as the list page already does per row.

## Goals / Non-Goals

**Goals:** order by frequency of use; one place per decision, with its evidence;
no link a reviewer holds today stops working.

**Non-Goals:** any API or backend change beyond the SPA route list; a portal
control for removing a marketplace (API-only today, #477 — Settings is where it
would go, but adding it is its own change); keyboard shortcuts; changes to the
vetting report, diff or provenance components themselves.

## Decisions

### Review is the index route, not a redirect target

`/marketplaces/:name` becomes a layout route (header + outlet) whose **index**
child is Review; `snapshots`, `activity` and `settings` are siblings. Every
existing `/marketplaces/:name?snapshot=&tab=&path=` link therefore lands on
Review with its query intact — no redirect, nothing to keep in step.

*Alternative:* `/marketplaces/:name/review` with a redirect from the bare path.
Rejected: a redirect has to carry the query string, and two addresses for one
view is one too many.

A snapshot addressed by `?snapshot=` that is not awaiting a decision (served,
rejected, earlier) is opened on **Snapshots**, which accepts the same query
parameters; Review, given such an id, opens it in place rather than dropping it,
so an old link to a served snapshot still shows that snapshot.

### Contextual navigation is derived from the route, not stored

The sidebar reads `useMatch("/marketplaces/:name/*")`. When it matches, the
Marketplaces item renders a nested list: the marketplace name, then Review (with
its awaiting count as a stat chip), Snapshots, Activity, Settings — `NavLink`s,
so the active one takes the existing active fill. Leaving the marketplace
collapses it. No state, nothing persisted.

The awaiting count uses `isDecidable` — held or revoked, not deleted — the same
rule the page uses, so the sidebar and the page cannot disagree.

**Review queue** sits in the Gateway group between Overview and Marketplaces,
with the cross-marketplace count. The count chip is hidden at zero rather than
showing "0", so a quiet sidebar means a quiet queue.

### The header is the marketplace's frame

Name, source URL, and one status line: *Serving `2e9c9b28fe13`* or *Not served —
a clone is answered with not-found until a snapshot is approved* (the page-level
statement GW_AUTH_0043 — The client wizard leads on a serving marketplace and
states the held-snapshot outcome — requires). Actions on the right, both
outline: **Ingest** and **Connect a client**. Approve on Review stays the page's
one violet control (the One Voice Rule).

Ingest on success opens the new snapshot on Review (`?snapshot=<id>`), so the
reviewer sees what arrived instead of a toast about it. Connect a client opens
the existing `SetupWizard` inline under the header, unmounted when closed as
today (a credential minted inside lives only while it is open).

### Snapshots, Activity, Settings are today's parts, moved

- **Snapshots:** the Serving line and the earlier snapshots, expanded by
  default here (this section is where history is the point), with the same one-
  open-card behaviour and query-string address.
- **Activity:** `MarketplaceAudit`, unchanged.
- **Settings:** Upstream, then `MarketplaceVettingChain` for an administrator.
  A non-administrator sees Upstream only; the server still refuses the chain
  read independently.

### The list is an index; the queue is a list of links

The marketplaces table drops its expander and `MarketplaceSnapshots`. It gains
an **Awaiting** column: a count linking to the marketplace's Review, "—" at zero.
The name still links to the marketplace.

`/review` is a table over every decidable snapshot, newest first: marketplace,
commit (mono, 12 chars), state badge, vetting outcome badge, ingested (time and
principal). The row's commit links to `/marketplaces/:name?snapshot=<id>`. It has
**no Approve or Reject** — that is the point of removing them from the list.
Empty: "Nothing awaits a decision." with a link to Marketplaces.

### Width

Marketplace routes and `/review` take a new `handle.layout: "full"`: full width,
normal page scroll (unlike `wide`, which owns its scroll for the two-pane
explorer). Capped at `max-w-[1600px]` so an ultrawide monitor does not stretch a
table's columns apart. The other pages keep `max-w-6xl`.

### The file-tree pane

Root cause: `grid-cols-[minmax(0,22rem)_1fr]`. `1fr` is `minmax(auto, 1fr)`, so
the content column's minimum is its longest unbreakable line, and a wide file
takes width from the tree. Fixed with `grid-cols-[18rem_minmax(0,1fr)]`; the
content's `<pre>` already scrolls horizontally inside itself.

### JSON is re-indented by a tokenizer, not by `JSON.parse`

`JSON.stringify(JSON.parse(text), null, 2)` would be wrong for a security
reviewer: it silently drops all but the last of a duplicated key (a known way to
show one value to one parser and another to the next), rewrites number
spellings (`1.0` → `1`, large integers lose precision) and reorders nothing but
normalises escapes. The formatter instead walks the text once, copies string
literals byte for byte (escapes included), drops whitespace outside strings, and
inserts newline + indent after `{` `[` `,` and before `}` `]`, and a space after
`:`. Every token survives in order; only inter-token whitespace changes.

If the walk fails (unterminated string, unbalanced brackets, a stray character),
the file is shown as stored with the line *Not valid JSON — shown as stored.* A
truncated blob is shown as stored with the existing truncation notice — a
partial document cannot be formatted honestly. A **Formatted / Raw** segmented
control (the existing `SegmentedGroup`) switches to the exact bytes; Formatted
is the default for `.json` paths. The choice is not put in the address: it is a
view preference, not part of what the address identifies.

### Requirements

Wording lives in `docs/reqstool/` only; what changes, by title:

- GW_AUTH_0043 — The client wizard leads on a serving marketplace and states
  the held-snapshot outcome: revised (title, description, SVC), because the
  wizard stops leading and becomes a header action; the held-state statement is
  kept. Revision 0.3.0.
- GW_INGEST_0032 — Addressable snapshot file inspection: revised to cover the
  JSON presentation above; SVC revised with it. Revision 0.3.0.
- GW_INGEST_0037 — Review queue across marketplaces: new, with
  SVC_GW_INGEST_0037 (component test plus e2e).
- GW_INGEST_0007 — Portal marketplace and snapshot administration: presentation
  only; statement and SVC unchanged, its e2e approves from Review.

## Risks / Trade-offs

- [Approving from the list was one click for a reviewer who trusted the vetting
  badge] → That click is what the design rules forbid; the queue plus Review is
  two clicks with the evidence rendered. Accepted deliberately.
- [A bookmarked served snapshot `?snapshot=` now lands on Review, where it is
  not "awaiting"] → Review opens an addressed snapshot in place whatever its
  role, with a line pointing to Snapshots; nothing 404s.
- [N+1 vetting reads on the queue] → Same pattern and cost as the list page
  today; the queue is bounded by what awaits a decision, which is small by
  nature. Revisit only if a real estate shows otherwise.
- [Tokenizer bugs could misrepresent a file] → Property test: formatting then
  stripping inter-token whitespace equals the input stripped the same way, over
  generated JSON including duplicate keys, escapes and odd numbers; Raw is always
  one click away.

## Migration Plan

None: presentation only, no stored state. Rollback is reverting the PR.
