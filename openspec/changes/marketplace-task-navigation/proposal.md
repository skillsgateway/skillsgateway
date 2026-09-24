# Proposal: marketplace-task-navigation

## Why

The marketplace page is ordered in roughly the reverse of how it is used. At
1440×900 the running instance stacks, as equal cards: the client set-up panel
(a one-off step per consumer), Upstream (reference facts), the vetting chain
(admin settings, ~400px) — and only then **Awaiting decision**, the job a
reviewer opens the page to do, at ~1,150px, below the fold. Ingest, the
marketplace's other routine action, is not on the page at all. Settings, a
one-off step, status and the review work are presented as the same kind of
thing.

The marketplaces list has the opposite problem. Its expandable row offers
Approve and Reject beside a single vetting badge, without the report, diff or
contents that the marketplace page renders — an approval control without its
evidence, which the portal's own design rules forbid. "Open detail" in that row
repeats the name link one row above it.

A reviewer also has no view of what awaits a decision across marketplaces: the
Overview shows a held count, and finding the snapshots behind it means opening
each marketplace in turn.

Two defects in the snapshot contents tab land with this change because they are
on the page being reorganised: the file-tree pane resizes with the open file
(the content column's minimum width is its longest unbroken line), and JSON —
`.claude-plugin/marketplace.json` first of all — is shown as one line.

## What Changes

- **A marketplace has sections, and the sidebar knows it.** Inside
  `/marketplaces/:name` the Marketplaces entry expands to the open marketplace
  and its sections: **Review** (the default, with its awaiting count),
  **Snapshots** (serving and history), **Activity** (this marketplace's ledger
  slice) and **Settings** (upstream facts; the vetting chain for an
  administrator). Each section has its own address.
- **The marketplace header carries state and the marketplace's actions.** The
  name, source, and one line stating what the facade serves (or that it serves
  nothing and a clone is answered with not-found); **Ingest** and **Connect a
  client** as actions present on every section. The client set-up wizard stops
  being the page's leading panel.
- **Existing links keep working.** `/marketplaces/:name?snapshot=&tab=&path=`
  resolves to the same snapshot, tab and file on Review.
- **The marketplaces list becomes an index.** The expandable snapshot row, and
  the Approve / Reject / Ingest controls inside it, are removed; an **Awaiting**
  column gives each marketplace's count and links to its Review. A decision is
  offered only where its evidence is rendered.
- **A review queue across marketplaces.** A top-level **Review queue** entry,
  with the count, lists every snapshot awaiting a decision — marketplace,
  commit, ingested when and by whom, vetting outcome — each opening in its
  marketplace's Review. The queue offers no decision control of its own.
- **Contents tab.** The file-tree pane keeps one width whatever file is open,
  and the marketplace pages use the shell's full width rather than a centred
  column. JSON files are shown re-indented, token for token, with the exact
  bytes one toggle away; a document that does not tokenise as JSON is shown as
  stored, with that stated.

No API change. No backend change beyond the SPA route list.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-portal`: GW_INGEST_0007 — Portal marketplace and snapshot
  administration (the list stops expanding to a decision sub-table; marketplace
  sections and header actions); GW_AUTH_0043 — The client wizard leads on a
  serving marketplace and states the held-snapshot outcome (the wizard becomes a
  header action on every section instead of the leading panel; the held-state
  statement is kept). Adds GW_INGEST_0037 — Review queue across marketplaces.
- `snapshot-preview`: GW_INGEST_0032 — Addressable snapshot file inspection
  (JSON presented re-indented without dropping or reordering a token, raw bytes
  one control away).

## Impact

- `src/main/frontend/src/`: `components/app-layout.tsx` (contextual nav, wide
  layout), `pages/marketplace-detail.tsx` (split into sections), `pages/marketplaces.tsx`
  (expander removed, Awaiting column), a new `pages/review-queue.tsx`,
  `components/snapshot-explorer.tsx` (grid and JSON view), `main.tsx` routes.
- `SpaController` gains `/review` and `/marketplaces/*/{snapshots,activity,settings}`.
- Tests: `marketplace-detail.test.tsx`, `marketplaces.test.tsx`, e2e
  `portal.spec.ts` (register → ingest → approve now approves from Review),
  stories for the new page and the nav.
- Docs: the portal pages under `docs/manual/` for marketplaces, the marketplace
  page, and the new review queue.
- reqstool: GW_AUTH_0043 and GW_INGEST_0032 revised; GW_INGEST_0037 added with
  SVC_GW_INGEST_0037.
