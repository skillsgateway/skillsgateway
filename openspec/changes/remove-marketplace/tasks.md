## 1. Requirements (reqstool SSOT)

- [x] 1.1 Add GW_INGEST_0034 — Administrative marketplace removal, GW_INGEST_0035 — Reuse of a removed marketplace's name, GW_AUDIT_0009 — Ledger entries identify the marketplace they concern by id, and GW_WEBHOOK_0010 — Marketplace removal event to `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_INGEST_0034, SVC_GW_INGEST_0035, SVC_GW_AUDIT_0009 and SVC_GW_WEBHOOK_0010 to `docs/reqstool/software_verification_cases.yml`

## 2. Schema

- [ ] 2.1 `V1__init.sql`: `deleted_at`/`deleted_by`/`deleted_reason` on `marketplaces` with a set-together CHECK; drop the table-level `UNIQUE` on `name` for a partial unique index over live rows; sync-queue index gains the live predicate
- [ ] 2.2 `V1__init.sql`: `fetch_log.marketplace_id BIGINT`, no foreign key
- [ ] 2.3 Update schema-shape assertions that name the old constraint, if any

## 3. Registry and ledger

- [ ] 3.1 `Marketplace` record carries the retirement columns; `MarketplaceRepository` filters retired rows from every name-addressed read; `retire` (conditional) and `hasRetired(name)`; annotate GW_INGEST_0034 / GW_INGEST_0035
- [ ] 3.2 `FetchLogRepository.append` resolves `marketplace_id` in the INSERT, accepts an explicit id; `AuditEntry` carries `marketplaceId`; `AdminAuditLogger` overload taking an explicit id; annotate GW_AUDIT_0009

## 4. Removal

- [ ] 4.1 `MarketplaceRemovalService`: reason required, stamp, revoke each approved snapshot through `RevocationService` (serve nothing), ledger `marketplace-removed`, `marketplace.removed` event; annotate GW_INGEST_0034, GW_WEBHOOK_0010
- [ ] 4.2 `RevocationService` writes its entries with the marketplace's id
- [ ] 4.3 `DELETE /api/v1/marketplaces/{name}` (admin only, JSON reason body, 200 with the removed marketplace and withdrawn snapshot ids, 404/422 problems); `MachineApiRegistry` UNREACHABLE; `RoleEnforcementTests` route list
- [ ] 4.4 `WebhookEvent.MARKETPLACE_REMOVED` in the catalogue

## 5. Guards on name-matched surfaces

- [ ] 5.1 `SnapshotRepository.decide` locks the marketplace row `FOR SHARE` and refuses a retired one; the refusal maps to a 409 problem
- [ ] 5.2 Facade `resolvePublished` refuses a name with no live marketplace (catalog excepted)
- [ ] 5.3 `heldContent` prefers the live marketplace of a name, else the latest retired one
- [ ] 5.4 Role grant reads exclude grants on retired marketplaces
- [ ] 5.5 Retention compaction keeps a quarantine pin another snapshot of the same name holds; retention writes its entries with the snapshot's marketplace id

## 6. Name reuse

- [ ] 6.1 Registration of a name with retired predecessors unpublishes every predecessor snapshot's references and, for a hosted successor, clears the origin lineage — before inserting; annotate GW_INGEST_0035

## 7. Tests (old-coder: observe each fail first)

- [ ] 7.1 SVC_GW_INGEST_0034: removal withdraws served content (facade clone fails, status says revoked, row/snapshots/provenance/ledger survive); refusals — non-admin, no/blank reason, unknown and already-removed name — change nothing; approval, ingest, sync-mode, sync sweep and listing exclude a removed marketplace; facade refuses a removed name even with leftover refs
- [ ] 7.2 SVC_GW_INGEST_0035: re-registration gets a new id and serves nothing; predecessor approver grant does not apply; hosted lineage cleared; same commit re-ingests held and approvable; retention purge of the predecessor's snapshot keeps the successor's pin; status prefers the successor
- [ ] 7.3 SVC_GW_AUDIT_0009: entries of two incarnations carry different ids; export and browse carry `marketplaceId`
- [ ] 7.4 SVC_GW_WEBHOOK_0010: `marketplace.removed` delivered with the marketplace body; a refused removal emits nothing
- [ ] 7.5 Manual mutation pass on the guards (facade check, decide lock, name filter, pin guard, successor unpublish); record kills in evidence

## 8. Contract and docs

- [ ] 8.1 Regenerate `src/main/frontend/openapi.json` and `pnpm gen:api-types`
- [ ] 8.2 Docs: REST reference, a guide section on removing a marketplace (estate declaration first, token scopes carry over), webhook event table, audit ledger reference (`marketplaceId`)

## 9. Gates and evidence

- [ ] 9.1 `./mvnw clean verify`
- [ ] 9.2 `pnpm test:stories` and `pnpm e2e`
- [ ] 9.3 `reqstool status local -p docs/reqstool` ends PASS
- [ ] 9.4 `openspec validate --all --strict` and `mkdocs build --strict`
- [ ] 9.5 `evidence.md` from one final fresh run of every gate
