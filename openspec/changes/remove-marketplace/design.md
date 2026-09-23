## Context

See `proposal.md` — Why. The constraints that shape the approach:

- `snapshots.marketplace_id` restricts deletion on purpose, so removal must be a
  soft retirement that *behaves* like removal.
- Git storage is keyed by the marketplace **name** (`quarantine/<name>`,
  `published/<name>`, `hosted/<name>`), and so are the facade route, token
  scopes, approver grants as matched, and `POST /status/v1/snapshots`. Reusing a
  name therefore reaches every one of those, not only the unique constraint.
- The facade resolves a published repository from storage alone; it never asked
  the database whether the marketplace exists.
- Administrative revocation (`GW_APPROVAL_0015 — Administrative revocation of an
  approved snapshot`) already withdraws one snapshot correctly: conditional
  transition first, quarantine untouched, loud on an unpublish failure.

## Goals / Non-Goals

**Goals:**

- Removal stops serving at once and stays stopped, including if an unpublish
  fails or an approval races the removal.
- A reused name starts from nothing: no served content, approval, approver
  grant or hosted lineage of its predecessor.
- The ledger can tell two marketplaces that held one name apart.

**Non-Goals:**

- Restoring a removed marketplace. Re-registering is the way back; an undo
  would be a second path into the served set.
- A portal control. Administrative revocation is API-only, and removal is
  built from it.
- Moving storage off name keys. Quarantine is shared by the incarnations of a
  name deliberately (below), so no rename or copy is needed.
- Tidying settings rows (waivers, vetter toggles, chain settings) of a retired
  marketplace. They are keyed by id and consulted only for that id, so they are
  inert; waivers are also evidence.

## Decisions

### The ledger disambiguator is the marketplace id (owner's decision)

`fetch_log.marketplace_id BIGINT`, beside `marketplace`, no foreign key — the
`token_id` idiom, for the same reason: an append-only ledger must not depend on
mutable state. Retirement keeps the row, so the old id keeps its referent; a
re-registration gets a new id.

Considered and rejected:

- **A generation/incarnation counter on the ledger row.** A second number that
  means what the id already means, with its own allocation rule to get wrong.
- **Never free the name.** Cheapest, but case 3 of the issue then requires a new
  clone path and breaks every client — the thing removal is for.
- **Free the name and document the ambiguity.** Leaves the recall query weaker
  than the threat model needs, permanently for rows written in the meantime.

**Writing it.** `FetchLogRepository.append` is the one writer. It resolves the
id from the name inside the `INSERT` — the live marketplace of that name, else
the most recently retired one — so the two dozen audit call sites need no
change and the facade pays no extra round trip. A caller acting on a known
incarnation that may already be retired (removal itself, the revocations it
makes, retention acting on a snapshot) passes the id explicitly, because after
a re-registration the name would resolve to the successor. Entries with the
`-` marketplace carry `NULL`.

### Soft retirement in the retention idiom

`deleted_at`, `deleted_by`, `deleted_reason` on `marketplaces`, with a `CHECK`
that the three are set together. `name` loses `UNIQUE`; a partial unique index
`WHERE deleted_at IS NULL` keeps one live marketplace per name. The sync queue
index gains the same predicate.

### Every name-addressed read filters; id-addressed reads do not

`MarketplaceRepository.findByName`, `list`, `dueScheduledSync`,
`webhookSecret` and `updateSyncMode` filter `deleted_at IS NULL`. That single
change takes a removed marketplace out of sync selection, ingestion, hosted
push, the inbound webhook, every `/marketplaces/{name}/…` route, the listing,
the catalog, retention evaluation, publication reconciliation and the mirror's
plausibility check — all of which already go through it. `findById` stays
unfiltered: a snapshot's provenance and content are the record removal promises
to keep.

### Removal order: stamp, then withdraw through revocation

1. Refuse a missing or blank reason (the reason becomes each revocation's, and
   revocation requires one).
2. Conditionally stamp the row (`WHERE deleted_at IS NULL`). A concurrent
   second removal loses here and gets not-found.
3. Revoke every approved snapshot through `RevocationService` with
   *serve nothing* and reason `marketplace removed: <reason>`. This is the same
   path as a person revoking one snapshot: ledger entries, the
   `marketplace.snapshot.revoked` event, catalog rebuild, mirror withdrawal, and
   `revoked` from `status/snapshots`. A snapshot a concurrent revocation took
   first is skipped.
4. Ledger `marketplace-removed`, then the `marketplace.removed` event.

Stamping first is what makes approval refusable during the removal. The
revocations are `administrative`, so retention keeps their rows
(`GW_RETENTION_0008 — An administrative revocation is never erased by retention`); and
because the standing-revocation gate is scoped to the marketplace id, a
successor that re-ingests the same commit is **not** blocked by its
predecessor's removal — removal is not a verdict on the content.

*Alternative rejected:* revoke first, stamp last. It leaves the marketplace
approvable while its snapshots are being withdrawn.

### Approval serialises with removal on the marketplace row

`SnapshotRepository.decide` takes `FOR SHARE` on the snapshot's marketplace row
and refuses when it is retired; removal's stamp takes the row exclusively. So a
decision either commits before the stamp — and removal then sees and revokes it
— or waits for the stamp and is refused. Without the lock the two are write
skew: each reads the other's row before either commits.

This does not order approval's *publication* (after `decide` commits) against
the revocation's unpublish. The facade check below is what closes that window.

### The facade asks the registry

`resolvePublished` refuses a name with no live marketplace (the virtual catalog
excepted, which is not a marketplace row). It runs after the scope check, so
`GW_AUTH_0006 — Marketplace-scoped access tokens` ordering is unchanged. This is defence in depth,
not the withdrawal mechanism: removal already unpublishes through revocation;
the check makes "removed" mean "not served" even when an unpublish failed or a
racing approval published after it.

### A reused name starts from nothing

Registration of a name that has retired predecessors, before inserting:

- **Published:** `GitStorage.unpublish(name, sha)` for every predecessor
  snapshot — the existing per-snapshot inverse of publication, idempotent — so
  a leftover reference from a failed unpublish can never be served under the
  successor.
- **Hosted:** if the new marketplace is hosted, the origin repository's lineage
  reference is deleted, so the successor does not ingest its predecessor's
  pushes or refuse its own first push as a non-fast-forward.
- **Quarantine is shared, deliberately.** Predecessor pins are the evidence
  behind their snapshots' provenance and content reads; the successor's
  ingestion force-updates only its own incoming reference. Retention compaction
  therefore no longer deletes a quarantine pin that another snapshot of the
  same name still holds.

A failure in either step refuses the registration and changes nothing.

### Other name-matched surfaces

- **`POST /status/v1/snapshots`** answers about the live marketplace of the
  name when it has the commit, else the most recent retired one — so a holder
  of removed content is told `revoked`, and an identical commit the successor
  approved is `approved`.
- **Approver grants** are matched by name. A grant on a retired marketplace is
  excluded from every read, so it cannot confer authority over a successor.
- **Token scopes** are names and carry over to a successor, deliberately: that
  is how clients keep working. A push scope carries over too; its pushes land
  held behind approval like any other.

### Declarative estate gains nothing

Removal is an act, not converge-able state — the line administrative revocation
drew. A marketplace still declared in `skills-gateway.estate.marketplaces` is
registered again, as a new marketplace serving nothing, on the next
reconciliation; the guide says to remove the declaration first.

## Risks / Trade-offs

- [One indexed registry read per facade request] → The facade already writes a
  ledger row per request; a primary-key-sized lookup beside it is noise.
- [Adoption reports group by name, so they span incarnations] → Reporting only;
  the id is on every row for a consumer that needs to split them.
- [A token scoped to a removed name reaches its successor] → Intended; stated in
  the guide so an administrator re-registering knows it.
