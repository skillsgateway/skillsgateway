# Tasks: webhook-refusal-is-not-found

The webhook is an unauthenticated trust boundary, so group 2 is worked under
`.claude/skills/old-coder`: every new or changed assertion is watched failing
before the code it guards changes. The defect groups (3–11) are Tier 2: a
failing test first where one is cheap and meaningful, otherwise the reason
there is none.

## 1. Requirements (reqstool)

- [x] 1.1 Amend GW_INGEST_0012 — Webhook-triggered ingestion — in
  `docs/reqstool/requirements.yml` so the refusals share one answer, and
  SVC_GW_INGEST_0012 so its THEN says so; bump both revisions. Verify with
  `openspec validate webhook-refusal-is-not-found --strict`.

## 2. The webhook answers every refusal alike (SVC_GW_INGEST_0012)

- [x] 2.1 `SyncTests`: missing, malformed, wrong-secret and tampered-body
  signatures, an unknown name, a non-webhook marketplace, and the rotated
  secret each answer `404` with the same body; an oversized body answers `413`
  for an unknown name as for a real one; a bad signature on a real webhook
  marketplace creates no snapshot and records no sync attempt. Watch the
  status assertions fail against the current controller.
- [x] 2.2 `InboundWebhookController`: read the bounded body first, then answer
  `404 not found` for every lookup or signature refusal; drop the `403`
  `@ApiResponse`. Make 2.1 green.
- [x] 2.3 Regenerate `src/main/frontend/openapi.json` and
  `src/api/types.gen.ts`; `OpenApiContractTests` passes.
- [x] 2.4 Docs: `guides/upstream-sync.md`, `reference/api/marketplaces.md` and
  `concepts/trust-boundaries.md` say "404: unknown marketplace, not in webhook
  mode, or the signature did not verify". Verify with `mkdocs build --strict`.

## 3. Close the RestClient responses

- [ ] 3.1 `ExternalVettingConnector` and `WebhookDispatcher` use the closing
  `exchange(fn)`. Verified by the existing connector and dispatcher suites; no
  new test (the leak is invisible to the JDK client's behaviour under test).

## 4. No orphan snapshot pin

- [ ] 4.1 Test: an ingest whose snapshot insert fails (the marketplace row
  removed underneath it) leaves no `refs/snapshots/*` in quarantine. Watch it
  fail.
- [ ] 4.2 `IngestionService.ingestLocked`: on a non-duplicate insert failure,
  delete the pin when no row names the commit, re-check, restore if one
  appeared; cleanup failures are suppressed onto the original. Make 4.1 green.

## 5. Re-vetting a marketplace survives one snapshot

- [ ] 5.1 `RevetService.revetMarketplace` catches per snapshot and logs, as
  `sweep` does. Covered by the existing re-vet suites staying green.

## 6. The vetting chain's catch is narrowed

- [ ] 6.1 Unit test: a storage failure opening the snapshot records a
  `snapshot-access` error verdict; a database failure recording a verdict
  propagates and records no `snapshot-access` verdict. Watch the second fail.
- [ ] 6.2 `VettingService.run` catches `IOException | UncheckedIOException`
  only. Make 6.1 green.

## 7. The marketplace list in one snapshot query

- [ ] 7.1 `SnapshotRepository.listByMarketplaces` (one `IN` query, grouped,
  ordered by id); `AdminController.listMarketplaces` uses it. The existing
  marketplace-list tests stay green with the response unchanged.

## 8. Bounded forge metadata body

- [ ] 8.1 Test against a local HTTP server: a forge answer over the bound
  yields no metadata; one under it is read. Watch the first fail.
- [ ] 8.2 `ForgeMetadataService.fetch` reads at most the bound plus one byte.
  Make 8.1 green.

## 9. Six leaves fall back at zero or below

- [ ] 9.1 Unit test: `audit-export.batch-size`, `default-page-size`,
  `max-page-size`, `retention.batch-size`, `webhooks.max-attempts` and
  `webhooks.batch-size` at `0` and `-1` take their defaults. Watch it fail.
- [ ] 9.2 Guard them `<= 0` in `SkillsGatewayProperties`; a line each in
  `reference/configuration.md`. Make 9.1 green.

## 10. Secrets out of `toString`

- [ ] 10.1 Unit test: `ObjectStore.Credentials`, `DeclaredWebhook` and
  `DeclaredAuditSink` do not print their secret. Watch it fail.
- [ ] 10.2 Override `toString` in the style of `Mirror`. Make 10.1 green.

## 11. The diff summary is cached

- [ ] 11.1 Unit test of the bounded LRU: it holds at most its capacity and
  evicts the least recently used entry.
- [ ] 11.2 `SnapshotPreviewService.diff` reads and fills the cache keyed by
  marketplace, sha, baseline and narrowed path; on a hit only the page is
  classified. SVC_GW_APPROVAL_0028's paging test (every page carries the
  whole-diff summary, then a narrowed one) stays green.

## 12. Gates and evidence

- [ ] 12.1 One fresh run of every gate after the last code edit; results in
  `evidence.md` with the commit SHA.
