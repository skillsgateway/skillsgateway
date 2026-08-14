# Tasks: add-lifecycle-event-webhooks

## 1. Requirements SSOT

- [ ] 1.1 Add GW_0023 (lifecycle event webhooks), GW_0024 (signed payloads), GW_0025 (retry with backoff), GW_0026 (portal webhook administration) to docs/reqstool/requirements.yml
- [ ] 1.2 Add SVC_GW_0023–SVC_GW_0026 (automated-test) to docs/reqstool/software_verification_cases.yml

## 2. Persistence

- [ ] 2.1 Flyway `V2__webhooks.sql`: `webhook_subscribers` and `webhook_deliveries` with the state check constraint and the `(state, next_attempt_at)` dispatch index
- [ ] 2.2 `WebhookSubscriber` / `WebhookDelivery` records and their JdbcClient repositories, including the `FOR UPDATE SKIP LOCKED` claim query

## 3. Emission and signing

- [ ] 3.1 `WebhookEvent` names (`snapshot.ingested`, `snapshot.approved`, `snapshot.rejected`) and the payload record
- [ ] 3.2 `WebhookService.emit(...)`: serialize once, fan out to subscribers whose filter matches, one pending delivery row each — `@Requirements({"GW_0023"})`
- [ ] 3.3 `WebhookSigner`: `sha256=<hex>` HMAC-SHA256 over the exact body — `@Requirements({"GW_0024"})`
- [ ] 3.4 Secret generation (SecureRandom, `whsec_` prefix) returned exactly once at creation — `@Requirements({"GW_0024"})`
- [ ] 3.5 Emit from `AdminController` ingest / approve / reject next to the audit-ledger calls

## 4. Dispatch and retry

- [ ] 4.1 `skills-gateway.webhooks.*` properties (enabled, poll interval, base backoff, max backoff, max attempts, timeouts) on `SkillsGatewayProperties`
- [ ] 4.2 `WebhookDispatcher`: `@Scheduled` claim-and-POST loop with signature headers, 2xx → delivered, otherwise backoff `base * 2^(n-1)` capped, exhausted → failed — `@Requirements({"GW_0025"})`
- [ ] 4.3 `@EnableScheduling` on the application

## 5. Admin API

- [ ] 5.1 `WebhookController`: create (201, show-once secret), list subscribers, delete subscriber, list recent deliveries — with `@Tag`/`@Operation`/`@ApiResponse` and `@Schema` on the DTOs
- [ ] 5.2 Reuse the URL scheme allowlist for subscriber URLs; audit-log subscriber creation and deletion

## 6. Portal (GW_0026)

- [ ] 6.1 Regenerate `ui/src/api/types.gen.ts` from the springdoc snapshot; extend the API client and MSW handlers
- [ ] 6.2 `ui/src/pages/webhooks.tsx`: subscribers table + show-once create form + delivery attempts table, JSDoc `@Requirements GW_0026`
- [ ] 6.3 Route and nav entry in `app-layout`

## 7. Tests

- [ ] 7.1 `WebhookTests`: filtering and payload content — `@SVCs({"SVC_GW_0023"})`
- [ ] 7.2 Signature equals HMAC-SHA256 of the body under the created secret; no read endpoint exposes the secret — `@SVCs({"SVC_GW_0024"})`
- [ ] 7.3 Failing endpoint: per-attempt recording, increasing backoff, terminal `failed` state — `@SVCs({"SVC_GW_0025"})`
- [ ] 7.4 Vitest component test for the webhooks page; Playwright e2e `webhooks_page_lists_subscribers_and_deliveries` — `@SVCs SVC_GW_0026`

## 8. Verification

- [ ] 8.1 `./mvnw clean verify`
- [ ] 8.2 `(cd ui && pnpm e2e)`
- [ ] 8.3 `reqstool status local -p docs/reqstool` ends PASS
- [ ] 8.4 `openspec validate --all --strict`
