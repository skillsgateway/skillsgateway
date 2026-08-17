# Tasks: upstream-sync-modes

## 1. Traceability (SSOT first)

- [x] 1.1 Add GW_0056–GW_0060 to `docs/reqstool/requirements.yml`
      (sync mode policy; scheduled sweep; webhook trigger with HMAC; upstream-outage
      resilience; sync audit trail).
- [x] 1.2 Add SVC_GW_0056–SVC_GW_0060 to
      `docs/reqstool/software_verification_cases.yml`.

## 2. Schema

- [x] 2.1 `marketplaces` gains `sync_mode TEXT NOT NULL DEFAULT 'on-demand'` with a
      CHECK over the three modes, `webhook_secret TEXT`, `last_sync_at TIMESTAMPTZ`
      (folded into `V1__init.sql`).
- [x] 2.2 House-style partial index for the sweep's only query:
      `ON marketplaces (last_sync_at) WHERE sync_mode = 'scheduled'`.

## 3. Domain and repository

- [x] 3.1 `Marketplace` record gains `syncMode` (never the secret); `MarketplaceRepository`
      maps it, adds `updateSyncMode(name, mode, secretOrNull)`, `webhookSecret(name)`,
      `dueScheduledSync(limit)` (oldest `last_sync_at` first, NULLS FIRST),
      `stampSyncAttempt(id)`.
- [x] 3.2 `SkillsGatewayProperties.Sync(enabled=true, pollInterval=10m, batchSize=10,
      maxWebhookBodyBytes=1MiB)`.
- [x] 3.3 `IngestionService.ingest` becomes safe for concurrent same-marketplace
      calls: per-marketplace lock (`ConcurrentHashMap` of locks keyed by
      marketplace id) around the fetch+pin+record, and a unique-violation on the
      snapshot insert returns the existing snapshot.

## 4. Sync service, scheduler, endpoints

- [x] 4.1 `sync/SyncService`: shared trigger path — ingest via `IngestionService`,
      stamp `last_sync_at`, audit (`snapshot-ingested`, actor `scheduler`/`webhook`),
      emit `snapshot.ingested`; failures logged, never thrown to callers.
- [x] 4.2 `sync/SyncScheduler`: `@Scheduled` sweep over `dueScheduledSync(batchSize)`,
      no-op when disabled.
- [x] 4.3 `sync/InboundWebhookController`: `POST /hooks/{marketplace}` — body-size
      bound (413), marketplace lookup (404), mode gate (404), constant-time
      HMAC-SHA256 check of `X-Hub-Signature-256` via the existing `WebhookSigner`
      + `MessageDigest.isEqual` (403), then 202 + queue on the single-threaded
      sync executor. No `Authentication` parameter (anonymous chain).
- [x] 4.4 `AdminController` (or `sync/SyncModeController`):
      `PUT /api/marketplaces/{name}/sync` — validate mode, (re)generate/clear the
      secret, audit `sync-mode-changed`, return secret exactly once; OpenAPI
      annotations; `MarketplaceView` gains `syncMode`.
- [x] 4.5 Security config: dedicated `@Order(2)` stateless `SecurityFilterChain`
      for `/hooks/**` (CSRF disabled, `permitAll()`, mirrors `gitChain`); web
      chain moves to `@Order(3)`; facade chain (`@Order(1)`) untouched.

## 5. API artifacts

- [x] 5.1 Regenerate `src/main/frontend/openapi.json` and `src/api/types.gen.ts`
      (no portal UI changes in this change).

## 6. Tests (old-coder: prove them red first; negative tests mandatory)

- [x] 6.0 Test controllability, house pattern: `skills-gateway.sync.enabled=false`
      in the shared test properties; tests drive the sweep/`SyncService` directly;
      webhook tests use a deterministic (synchronous or awaitable) executor.

- [x] 6.1 Sync-mode endpoint: set each mode, secret returned exactly once and only
      for `webhook`, secret absent from every marketplace read, mode change audited,
      invalid mode → 422/400, unknown marketplace → 404.
- [x] 6.2 Scheduled sweep: only `scheduled` marketplaces picked, oldest-first order,
      batch bound honored, snapshot lands HELD and vetting ran, disabled flag stops
      the sweep, one failing upstream doesn't stop the rest and is retried
      (`last_sync_at` stamped on failure).
- [x] 6.3 Webhook trigger (adversarial): valid signature → 202 and a HELD snapshot;
      missing signature, wrong signature, secret-of-another-marketplace, tampered
      body → 403 and no snapshot; unknown marketplace and non-webhook mode → 404 and
      no snapshot; oversized body → 413; payload contents (foreign URL/ref) are
      ignored — ingested SHA is the registered upstream's HEAD.
- [x] 6.4 Outage resilience (GW_0059): approve a snapshot, kill the upstream, run a
      scheduled sweep + a signed webhook trigger → both fail internally, a real git
      clone through the facade still serves the approved SHA.
- [x] 6.5 Approval unchanged: no sync path ever produces an APPROVED snapshot or
      touches published refs.
- [x] 6.6 Concurrency: concurrent ingests of the same marketplace (manual +
      sync-triggered) both succeed and yield one snapshot row, no lock error, no
      duplicate-key 500.

## 7. Documentation

- [x] 7.1 New guide `docs/manual/guides/upstream-sync.md`; add to `mkdocs.yml` nav.
- [x] 7.2 `reference/configuration.md`: the `sync` block.
- [x] 7.3 `reference/api/marketplaces.md`: sync-mode endpoint + `/hooks/{marketplace}`.
- [x] 7.4 Concepts touched: `concepts/lifecycle.md` (triggers), `concepts/trust-boundaries.md`
      (inbound webhook surface), `concepts/glossary.md` (sync mode).
- [x] 7.5 `ARCHITECTURE.md` roadmap note: sync modes implemented, payload parsing and
      portal surface deferred.

## 8. Gates and evidence

- [ ] 8.1 `./mvnw clean verify`
- [ ] 8.2 `(cd src/main/frontend && pnpm e2e)`
- [ ] 8.3 `reqstool status local -p docs/reqstool` → PASS
- [x] 8.4 `openspec validate --all --strict`
- [x] 8.5 `mkdocs build --strict`
- [ ] 8.6 Record all gates in `evidence.md` with the final commit SHA.
