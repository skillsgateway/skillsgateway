## 1. Requirements (reqstool SSOT)

- [x] 1.1 Add GW_WEBHOOK_0008 — Namespaced lifecycle event names and
      GW_WEBHOOK_0009 — Marketplace administration event webhooks to
      `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_WEBHOOK_0008 and SVC_GW_WEBHOOK_0009 to
      `docs/reqstool/software_verification_cases.yml`, each with its GIVEN /
      WHEN / THEN

## 2. The event catalogue

- [x] 2.1 Rename the nine wire names in `WebhookEvent` to
      `marketplace.snapshot.*`; `audit.export` unchanged
- [x] 2.2 Add `Shape` (`SNAPSHOT`, `APPROVAL_PENDING`, `MARKETPLACE`), the
      `Definition(name, shape)` catalogue, `ALL` derived from it, and
      `shapeOf(name)`; annotate with `@Requirements({"GW_WEBHOOK_0008"})`
- [x] 2.3 Add `MARKETPLACE_REGISTERED`, `MARKETPLACE_UPDATED` and
      `MARKETPLACE_VETTER_TOGGLED` to the catalogue

## 3. Payload and emission

- [x] 3.1 Add `WebhookService.MarketplacePayload(event, occurredAt,
      marketplace, actor, detail)` with every field `requiredMode = REQUIRED`
- [x] 3.2 Add `WebhookService.emitMarketplace(event, marketplace, actor,
      detail)` reusing the existing `fanOut`; annotate
      `@Requirements({"GW_WEBHOOK_0009"})`
- [x] 3.3 Emit `marketplace.registered` in
      `MarketplaceRegistrationService.register`, after the ledger write
- [x] 3.4 Emit `marketplace.updated` in `SyncService.changeMode`, after the
      ledger write, only when a marketplace was actually updated
- [x] 3.5 Emit `marketplace.vetter_toggled` in `VetterToggleService.set`, after
      the ledger write, with `-` as the marketplace for a global toggle and no
      operator-supplied reason in `detail`

## 4. The published contract

- [x] 4.1 `OpenAPI.delivery` picks its body component from
      `WebhookEvent.shapeOf(event)` instead of the equality test
- [x] 4.2 Add `exampleMarketplacePayload` to `WebhookController.EventRegistry`
      so `MarketplacePayload` reaches `components` and the events endpoint's
      `200` response — the side the contract gate rates a removed field an
      error
- [x] 4.3 Update the `@Schema` examples and descriptions that spell an event
      name (`CreateSubscriberRequest.events`, the registry field descriptions,
      the `Webhooks` tag)
- [x] 4.4 Regenerate `src/main/frontend/openapi.json` from
      `target/openapi.json` and run `pnpm gen:api-types`

## 5. Stored filters

- [x] 5.1 Add `src/main/resources/db/migration/V3__namespace_webhook_events.sql`
      rewriting `webhook_subscribers.events`; `V1` and `V2` untouched
- [x] 5.2 Update the estate `skills-gateway.estate.webhooks[*].events` examples
      in `application.yaml`/test fixtures if any spell an event name — only
      `EstateReconciliationTests` did; no shipped YAML spells an event name

## 6. Tests

- [x] 6.1 Update every existing test that spells an event name
      (`WebhookTests`, `EstateReconciliationTests`, `SyncTests`,
      `RevetEnforceTests`, `OpenApiContractTests`, `NativeEnumColumnTests`,
      `DataClassRowMapperTests`)
- [x] 6.2 `SVC_GW_WEBHOOK_0008`: the registry offers only namespaced names, a
      delivery's header and body carry the namespaced name, an old name is
      refused as an unknown event, and a filter stored before the migration is
      rewritten and still matches
- [x] 6.3 `SVC_GW_WEBHOOK_0009`: one test per event — registration, sync-mode
      change and vetter toggle each reach a subscriber filtering for it, with
      the marketplace body's field set asserted
- [x] 6.4 Negative tests: a refused registration (bad scheme / duplicate name),
      a refused sync-mode change (unknown marketplace) and a refused toggle
      (unknown vetter) each emit nothing
- [x] 6.5 Update MSW handlers and `webhooks.test.tsx` fixtures to the
      namespaced names

## 7. Documentation

- [x] 7.1 `docs/manual/guides/lifecycle-webhooks.md`: rename every event in the
      table and the prose, add the marketplace-event table and its body
      example, and add the "Not a webhook: fetches" note linking
      `exporting-the-audit-ledger.md`
- [x] 7.2 Rename event names in `approving-snapshots.md`, `declarative-estate.md`,
      `re-vetting.md`, `snapshot-retention.md`, `upstream-sync.md`,
      `reference/configuration.md` and `reference/retention.md`
- [x] 7.3 The estate reference and guide `events` examples — there is no
      hand-written `reference/api/webhooks.md`; that surface is the rendered
      OpenAPI document
- [x] 7.4 Note the rename in `docs/manual/reference/compatibility.md`, beside
      the promise it is an exception to

## 8. Gates and evidence

- [x] 8.1 `./mvnw clean verify`
- [x] 8.2 `pnpm test:stories` and `pnpm e2e`
- [x] 8.3 `reqstool status local -p docs/reqstool` ends PASS
- [x] 8.4 `openspec validate --all --strict` and `mkdocs build --strict`
- [x] 8.5 Write `openspec/changes/marketplace-webhook-events/evidence.md` from
      one final fresh run of every gate
