# Proposal: snapshot-decision-first-layout

## Why

The marketplace detail page shows everything about every snapshot, always. Measured
against the running instance at 1440×900: **3,220px — 3.6 screens — for two
snapshots with nothing expanded.**

The cost is not the "Show contents" toggle, which was the obvious suspect. It is
that `VettingReport` renders **unconditionally** for every snapshot: chain flow,
four verdicts, findings, waivers and the vetter disclosure, **518px** of it, or
~78% of a 660px card. So the page is O(n) in snapshots at ~660px each — eight
snapshots is eight screens, twenty is seventeen — and ingestion is routine.

Underneath the length is a worse problem: **the page does not say what it is
for.** Every snapshot is presented identically, so nothing distinguishes the one
consumers are being served from the one waiting on a decision from the six that
are history. The reviewer has to reconstruct that from state badges.

## What Changes

### The snapshot section becomes three named roles

- **Awaiting decision**, first, because it is the only section with a pending
  action — a *list*, newest first. Two snapshots can await a decision at once;
  confirmed on the live instance by ingesting twice without deciding. The newest
  opens; the rest are one line each, and the open card states that approving it
  leaves the older held ones awaiting — and that approving one of them afterwards
  would serve content older than it.
- **Serving**, one quiet line: who approved it, when, and who is pulling it.
- **Earlier snapshots (n)**, a collapsed count. This is what stops page length
  growing with ingest count.

### Each card leads with a one-line delta

`3 files · +48 −7 · 1 skill added, 1 modified · vs 65f64622`, above the fold of
the card, so the size of the review is answerable without expanding anything.
Assembled from reads the portal already makes — `GET /snapshots/{id}/diff` for
files and line counts, `GET /snapshots/{id}/content-diff` whose `summary` already
carries per-status skill counts. **No new endpoint.**

### Inspection becomes tabs on the card

Contents, Diff, Vetting, Inventory and Provenance become tabs, so one region
swaps and the page — and the reviewer's place in it — does not. **Vetting stops
being unconditional**, which is what removes the 518px from every card and makes
the arithmetic above work.

The selected tab and file live in the **query string**, so
GW_INGEST_0032 — Addressable snapshot file inspection is preserved: one address
still names the snapshot and the file and restores both. That requirement
constrains the *address*, not the existence of a separate page.

### Approve and Reject move to the foot of the card

Below the delta and below the tabs. This is not a layout preference: DESIGN.md
says "don't offer an approval control without the evidence above it", and the
header row puts the control above everything. Blocked vetting disables Approve
with the reason on screen rather than failing on press.

### The marketplace read reports what is actually served

`MarketplaceView` has no `servedSha`, so the portal can only infer "the newest
approved snapshot" — which is wrong exactly when it matters. A marketplace
mid-revocation holds approved snapshots while the facade serves nothing, and the
page would still point at one and imply it is live. The field exists on the
adoption and staleness reads already; it joins the marketplace read.

Requirements GW_INGEST_0033 — The marketplace read names the commit the facade
serves, and GW_APPROVAL_0018 — An approval control is presented only below the
evidence it rests on, each with its SVC.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `snapshot-approval`: any surface offering the approval decision presents it
  after the evidence it rests on, presents a blocked decision as unavailable with
  its reason, and names the snapshots that would still await a decision after it.
- `marketplace-ingestion`: the marketplace read reports the commit the facade is
  currently serving, distinctly from the newest approved snapshot.

The role-based presentation itself — awaiting, serving, history — is layout and
carries no requirement of its own; what is worth holding the system to is the
decision surface's order and the served commit's honesty, and those two are
what the requirements pin.

## Impact

- **DB**: none. The served tip is read from `GitStorage.SERVED_REF`, as the
  adoption read already does.
- **Backend**: one additive field on `MarketplaceView` and its assembly. No new
  endpoint, no new package.
- **API**: additive within the major; `openapi.json` regenerated.
- **Portal**: `pages/marketplace-detail.tsx` restructured; new
  `components/snapshot-delta.tsx` and `components/snapshot-card.tsx`;
  `components/snapshot-preview.tsx` retired into the card's tabs.
- **Configuration**: no new settable leaves. No new Spring test context.
- **Trust boundary**: the approval control moves, and that is the reason the
  placement rule exists — so the `.claude/skills/old-coder` discipline applies to
  the decision surface: a test that Approve is never rendered above the evidence,
  and that a blocked snapshot's control is disabled with its reason rather than
  failing on press.
- **Stop rule**: this **removes** surface rather than adding it. One
  unconditional 518px block becomes one tab among five; three stacked panels
  become tabs; a list of identical cards becomes two pinned roles and a count. No
  new route, endpoint, package, estate object, role, sweep or configuration leaf.
- **The dedicated file-explorer route.** Making the card self-sufficient means
  `/marketplaces/:name/snapshots/:id/files` becomes a second way to see the same
  thing, which the stop rule dislikes. The owner has agreed to retire it. It is
  **not** retired here: it stays as a thin wrapper over the same components so
  this change is reviewable and revertible on its own, and a follow-up removes
  the route once the card has replaced it in practice. Retiring it in the same PR
  would mean deleting a surface that shipped a day earlier inside a large
  restructure.
- **Docs** (same PR): `reference/portal.md`, `reference/api/marketplaces.md`,
  `guides/approving-snapshots.md`.
- **Design harness**: `/impeccable audit` and `harden` on the rebuilt page;
  `critique` is not required — this rebuilds an existing page rather than adding
  one.
