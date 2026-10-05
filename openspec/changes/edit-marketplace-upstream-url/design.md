# Design: edit-marketplace-upstream-url

## Context

Motivation: see proposal.md. Current state:

- `MarketplaceRegistrationService.register` is the one registration gate
  (API and estate). It validates the name, origin, scheme allowlist and
  userinfo, warns on a duplicate URL, probes the upstream last
  (`Reachability.REFUSE` for the API, `REPORT` for the estate), then inserts.
- Nothing updates `marketplaces.url`. `EstateReconciler.reconcileMarketplace`
  fails an entry whose declared URL differs, citing the API's lack of an
  update.
- A snapshot does not record the URL it was fetched from; its source is read
  from the marketplace row. That is why the URL must be fixed once a snapshot
  exists, and why an ingest racing an edit matters.
- `IngestionService` serialises ingests per marketplace with an in-process
  lock only; another gateway instance is not covered by it.
- `registered_by` is a column the four-eyes rule
  (`GW_APPROVAL_0010 — Separation of duties on snapshot approval`) reads.

## Goals / Non-Goals

**Goals:**

- Correct a mistyped URL without removing the marketplace, while it has no
  snapshot.
- The corrected URL passes exactly the checks a registration's URL passes.
- No snapshot is ever recorded under a URL its content was not fetched from.

**Non-Goals:**

- Editing after the first snapshot. Remove and re-register stays the path;
  it keeps the old snapshots' source true.
- Editing a hosted marketplace (no upstream) or changing origin.
- Re-pointing a webhook-mode marketplace's forge hook: the gateway does not
  configure the upstream's webhook, so the docs say to move it.
- A webhook event for the edit. The ledger records it; nothing consumes an
  event for a marketplace that has never served anything.

## Decisions

### D1. One route on the marketplace resource: `PUT /api/v1/marketplaces/{name}/url`

Body `{ "url": "…" }`, answer `200` with the `RegisteredMarketplace` shape
registration already answers with (marketplace + warnings). Mirrors
`PUT /marketplaces/{name}/sync`. Statuses: `400` scheme, credential in URL,
hosted marketplace; `403` not an administrator; `404` no live marketplace;
`409` the marketplace has a snapshot; `502` upstream unreadable (same problem
body as registration). A PATCH of the whole marketplace was rejected: it would
invite "which fields are editable" for one field.

An unchanged URL (exact string) is answered `200` with no probe and no ledger
entry.

### D2. The edit lives in `MarketplaceRegistrationService`

`changeUrl(name, url, actor, Reachability)` beside `register`, sharing the
private validations (`requireAllowlistedScheme`, `requireNoUserinfo`,
`duplicateUrlWarnings` — excluding the marketplace itself — and
`readUpstream`). The class comment already says the gate lives here so no
second caller grows a drifting copy; an edit is a second way to set the URL,
so it belongs in the same class. Order: name lookup (404), hosted (400),
snapshot pre-check (409), URL validations (400), probe (502), then the locked
update. Everything that needs no network runs before the probe, as for
registration.

### D3. The edit and the snapshot insert serialise on the marketplace row

- Edit: one transaction — `SELECT … FOR UPDATE` the live row, check no
  snapshot exists (a new statement, so it sees any insert committed while it
  waited), `UPDATE` url, forge metadata and `registered_by`. A snapshot found
  here is the same `409` as the pre-check.
- Snapshot insert (`SnapshotRepository.create`, already transactional): first
  `SELECT url FROM marketplaces WHERE id = … FOR SHARE` and compare it with the
  URL the ingest fetched from (`IngestionService` passes `marketplace.url()`).
  A mismatch throws, the existing failure path unpins and records a failed
  ingest ("the marketplace's URL changed while it was being ingested").

Whichever takes the row first wins: an insert holding the share lock makes the
edit wait and then see the snapshot (409); an edit holding the update lock
makes the insert wait and then read the new URL (ingest fails). This works
across gateway instances, which the in-process ingest lock would not.
Alternatives rejected: taking the in-process ingest lock in the edit (misses
other instances); a single conditional `UPDATE … WHERE NOT EXISTS` (under READ
COMMITTED the subquery does not see a concurrent uncommitted insert, and the
insert takes no lock the update conflicts with).

### D4. The editor becomes the registrant

The registrant is "who chose this upstream". After an edit that is the
editor; the original URL is no longer in effect. Keeping the original
registrant would let the editor approve content from the upstream they picked;
adding a second column would grow the four-eyes conflict set for a case that
exists only before the first snapshot. The ledger keeps both identities.

### D5. The estate converges the URL through the same edit

`reconcileMarketplace`: when the declared URL differs, call
`changeUrl(..., Reachability.REPORT)` and report the entry `updated`
(`url=<new>` plus any warning). A snapshot, a hosted marketplace or a declared
null URL makes `changeUrl` refuse, and that refusal is the entry failure, as
today. The old comment's argument ("the reconciler must not acquire a power the
API refuses to have") now argues for this: the API has the power, within the
same limit.

### D6. Portal: Settings › Upstream, administrators only

An "Edit URL…" button in the Upstream card opens a dialog with the URL field;
errors from the server (problem detail) are shown inline, warnings as a toast.
Shown only for an administrator, an upstream marketplace, and zero snapshots;
with snapshots the card says to remove and register again. A marketplace the
estate declares gets the button disabled with the reason (edit the declaration
and restart), as `RemoveMarketplace` does.

## Risks / Trade-offs

- [An edit lands between an ingest's fetch and its insert] → D3; the ingest
  fails and says why; the next ingest uses the new URL.
- [Share lock on the marketplace row during snapshot insert] → it is held for
  one short transaction; `recordIngest`/`stampSyncAttempt` wait for it at most.
- [Estate converging a URL the operator did not mean to change] → only while no
  snapshot exists, and the entry reports `updated` with the URL.
- [Quarantine holds objects fetched from the old URL] → never served, and the
  next ingest replaces `refs/quarantine/incoming`; nothing reads them.

## Migration Plan

No schema change. Rollback is reverting the PR.

## Open Questions

None.
