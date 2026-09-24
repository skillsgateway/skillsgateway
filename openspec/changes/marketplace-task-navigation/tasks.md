# Tasks: marketplace-task-navigation

## 1. Requirements (reqstool)

- [x] 1.1 Revise GW_AUTH_0043 — The client wizard leads on a serving marketplace and states the held-snapshot outcome (title, description, rationale) and SVC_GW_AUTH_0043; revision 0.3.0
- [x] 1.2 Revise GW_INGEST_0032 — Addressable snapshot file inspection and SVC_GW_INGEST_0032 for the JSON presentation; revision 0.3.0
- [x] 1.3 Add GW_INGEST_0037 — Review queue across marketplaces and SVC_GW_INGEST_0037 (re-check no in-flight change took the id)

## 2. Routing and shell

- [x] 2.1 Make `/marketplaces/:name` a layout route with index Review and `snapshots`, `activity`, `settings` children; add `/review`
- [x] 2.2 Add `handle.layout: "full"` (full width capped at 1600px, page scroll) and apply it to marketplace routes and `/review`
- [x] 2.3 Add the new paths to `SpaController` and its test
- [x] 2.4 Contextual sidebar: nested marketplace sections under Marketplaces when inside one, Review count chip; Review queue entry with cross-marketplace count, hidden at zero; breadcrumb names each section

## 3. Marketplace pages

- [x] 3.1 Marketplace header: name, source, served / not-served status line, Ingest (opens the new snapshot on Review) and Connect a client (inline SetupWizard)
- [x] 3.2 Review section: awaiting snapshots with the open card; an addressed non-awaiting snapshot opens in place with a pointer to Snapshots; empty state
- [x] 3.3 Snapshots section: Serving plus earlier snapshots, same one-open-card and query-string address
- [x] 3.4 Activity section (MarketplaceAudit) and Settings section (Upstream; vetting chain for administrators)
- [x] 3.5 Split `marketplace-detail.test.tsx` along the sections; keep every existing assertion that still describes behaviour

## 4. List and queue

- [x] 4.1 Marketplaces table: remove the expander and `MarketplaceSnapshots`; add the Awaiting column linking to Review
- [x] 4.2 `/review` page: every decidable snapshot, newest first, with marketplace, commit link, state, vetting outcome, ingested; no decision controls; loading, empty, error states
- [x] 4.3 Tests and a story for the queue; `marketplaces.test.tsx` updated (SVC_GW_INGEST_0037 annotation on the queue tests)
- [x] 4.4 Overview: the "awaiting review" status links to the queue

## 5. Contents tab

- [x] 5.1 Fix the explorer grid to `18rem minmax(0,1fr)`; verify the tree keeps its width across a long-line file
- [x] 5.2 Token-preserving JSON formatter in `lib/`, with unit and property tests (duplicate keys, escapes, number spellings, invalid input)
- [x] 5.3 Formatted / Raw control for `.json` paths; invalid and truncated shown as stored with the stated condition; tests annotated SVC_GW_INGEST_0032

## 6. End to end and docs

- [x] 6.1 e2e: SVC_GW_INGEST_0007 approves from Review; SVC_GW_AUTH_0043 finds the wizard in the header; add SVC_GW_INGEST_0037 queue flow; list has no decision controls
- [x] 6.2 Update the portal pages in `docs/manual/` (marketplaces, marketplace page sections, review queue, JSON view)
- [x] 6.3 `/impeccable audit` the changed pages at 1440×900 and mobile width, light and dark; fix in one batch

## 7. Close

- [ ] 7.1 Run every gate fresh after the last edit; write `evidence.md` with commands, result tails and SHA
- [ ] 7.2 Archive the change as the final commit
