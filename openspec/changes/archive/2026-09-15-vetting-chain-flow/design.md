# Design: vetting-chain-flow

## The drawing

A graph library was considered and rejected. The chain is a **path**, not a graph:
its nodes have exactly one predecessor and one successor, the order is a total order
the server already computes (`VettingService.connectors()` sorts by `order` then
`name`), and the run records a `position` per verdict. A layout engine would solve a
problem that does not exist, and every candidate (React Flow, dagre, cytoscape) adds
a dependency, a canvas or SVG surface with its own focus model, and a second
component library beside shadcn/Base UI — which `design-conventions` forbids outright.

So the flow is a flex row of ordinary buttons with chevron separators, wrapping on
narrow viewports:

```
[ingest] → [secret-scan PASS] → [prompt-injection FAIL] → [outcome BLOCKED] → [approval held]
```

Consequences that fall out of that choice, rather than having to be engineered:

- Tab order is DOM order is chain order. Nothing to manage.
- The nodes are real `<button>`s, so role/name queries — the project's test contract —
  address them directly, and axe has nothing to complain about.
- It reflows. A wide chain wraps to a second line instead of scrolling a canvas
  sideways, and the separators are `aria-hidden` so the wrap changes nothing a screen
  reader hears.
- No motion. `DESIGN.md` records that this product has no motion vocabulary; a flow
  diagram is exactly the place a reflex would add a traversal animation, and it does
  not get one.

**Colour is never alone.** Every node carries an icon, the state word, and the
token colour: `text-primary` for pass and warn, `text-destructive` for fail and
error, `text-muted-foreground` for pending, disabled and unknown. No new token, no
categorical scale — `DESIGN.md`'s Grey Data Rule and One Voice Rule both hold.

## The node detail

The Dialog primitive the repo already ships, not a Popover or a Sheet — neither is in
`src/components/ui/`, and adding a primitive to render a paragraph of evidence is
exactly the "hand-roll a primitive shadcn ships" move the conventions reject. A
dialog is also the right *weight*: the detail is the evidence a decision rests on,
not a hover affordance.

One dialog is rendered for the selected node, keyed by node id, so the content is
unmounted when it closes — the same discipline the setup wizard uses for the
show-once token.

## Why one additive endpoint

The marketplace-level flow needs, for each connector: its position in the chain, its
description, its version, whether it is external, whether it is enabled **for this
marketplace**, and where that state came from. The client can derive **none** of that
reliably today:

- The only list of configured connectors the API returns is
  `GET /api/snapshots/{id}/vetting` → `connectors`, which requires a snapshot. A
  marketplace registered but never ingested has none, and the administrator surface
  must work before the first ingestion — that is when the chain is worth configuring.
- `GET /api/vetting/connector-toggles` returns rows keyed by `marketplaceId`, a number
  the marketplace list does expose — but the rows carry no chain order, and the
  *absence* of a row is the "default: enabled" case the surface has to name. A client
  reconstructing "per-marketplace, else global, else enabled" would be a second
  implementation of `ConnectorToggleService`'s resolution rule, free to disagree with
  the one that actually decides what runs. The portal already refuses that trade for
  the four-eyes check, for the same reason.

So: `GET /api/marketplaces/{name}/vetting-chain`, administrator-only, returning the
chain in order with each connector's resolved state and its source. It computes
nothing new — `ConnectorToggleService.resolve(...)` is the rule `enabled(...)` already
applied, refactored so `enabled(...)` delegates to it. One rule, two shapes.

The endpoint is a read of connector settings, which GW_VETTING_0029.4 — Every toggle
is an audited, administrator-only action already reserves to administrators, so it
goes behind the same `roleService.requireAdmin` gate as the settings list and carries
a negative test that a non-administrator is refused. It is additive: a new path under
the existing prefix, no existing response narrowed.

## Where the DISABLED reason is shown, and where it is not

A `DISABLED` node in the **snapshot** flow says the connector was skipped and that an
administrator disabled it for this marketplace — which is exactly what the run's own
verdict detail records, and which every reviewer may see. It does **not** show who
switched it off, at what scope, or why: that is a connector setting, and
GW_VETTING_0029.4 says a non-administrator may not see connector settings at all.
Fetching it into the reviewer's report "when the session happens to be an admin"
would put a privileged field behind a client-side condition; the node points at the
administrator surface instead, where the same three facts are shown to a session the
server has checked.

## Requirements

Two are added, both under `snapshot-vetting`:

- **GW_VETTING_0031 — Portal vetting chain flow.** A new user-facing capability:
  GW_VETTING_0005 — Portal vetting surface requires the verdicts and findings to be
  shown, and is satisfied by the existing list; it says nothing about the chain being
  legible *as a chain*, or about a node's detail, and a flow that silently regressed
  to colour-only state would violate nothing today.
- **GW_VETTING_0029.5 — Administrator surface for a marketplace's effective vetting
  chain**, decomposed under GW_VETTING_0029 alongside .1–.4. It is the read that makes
  the guarantee in the parent checkable: an administrator who cannot see which
  connectors actually run for a marketplace cannot tell that switching one off has not
  quietly become a blanket approval.

No existing requirement is revised, and no SVC is weakened.

## Rejected alternatives

- **A Mermaid diagram.** The docs site renders Mermaid; the portal does not, and
  adding a renderer to ship a five-node path is a bundle and a second layout engine
  for nothing. A Mermaid node is also not focusable or nameable.
- **Replacing the per-connector list with the flow.** The flow is a scan surface; the
  list is where a reviewer reads findings side by side and writes a waiver next to the
  one being accepted. Losing it to a dialog would make the waiver flow worse.
- **Putting the marketplace flow on the marketplaces table page.** The chain is a
  property of one marketplace; the table is the estate. It goes on the detail page,
  above the snapshots whose verdicts it explains.
