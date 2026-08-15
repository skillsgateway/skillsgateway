# Tasks: add-audit-ledger-export

## 1. Requirements SSOT

- [x] 1.1 Add GW_0027 (NDJSON ledger export with cursor), GW_0028 (audit export sinks with at-least-once delivery), GW_0029 (cursor replay), GW_0030 (portal audit export surface) to docs/reqstool/requirements.yml
- [x] 1.2 Add SVC_GW_0027–SVC_GW_0030 (automated-test) to docs/reqstool/software_verification_cases.yml

## 2. Persistence

- [x] 2.1 Fold `audit_sinks` into `V1__init.sql` (name UNIQUE, kind, subscriber_id FK, cursor_position, batch_size, enabled, timestamps)
- [x] 2.2 `AuditSink` record and `AuditSinkRepository` (create, list, findById, updateCursor, delete)
- [x] 2.3 `FetchLogRepository`: typed `AuditEntry`, `entriesAfter(cursor, cutoff, limit)`, `maxId()` — still no update or delete

## 3. Export service

- [x] 3.1 `skills-gateway.audit-export.*` properties (enabled, poll interval, lag, default/max page size) on `SkillsGatewayProperties`
- [x] 3.2 `AuditExportService`: sink CRUD (webhook sink creates its `webhook_subscribers` row with the `audit.export` filter and returns the show-once secret), cursor reset — `@Requirements({"GW_0028","GW_0029"})`
- [x] 3.3 `AuditExportService.exportBatch(sink)`: read after cursor under the settling cutoff, enqueue one signed delivery through `WebhookDeliveryRepository`, advance the cursor — `@Requirements({"GW_0028"})`
- [x] 3.4 `@Scheduled` exporter pass over enabled sinks, off by default in tests
- [x] 3.5 `WebhookEvent.AUDIT_EXPORT` constant, deliberately outside `WebhookEvent.ALL`

## 4. Export API

- [x] 4.1 `AuditController` `GET /api/audit/export`: NDJSON `StreamingResponseBody`, `?after=` / `?limit=`, ascending, `X-Skills-Gateway-Audit-Cursor` header — `@Requirements({"GW_0027"})`
- [x] 4.2 `AuditController` sink endpoints: create (201, show-once secret), list (with cursor and lag), delete, `PUT /api/audit/sinks/{id}/cursor` — `@Requirements({"GW_0029"})`
- [x] 4.3 `@Tag`/`@Operation`/`@ApiResponse` on every endpoint and `@Schema` on the DTOs; audit-log sink creation, deletion, and cursor reset

## 5. Portal (GW_0030)

- [x] 5.1 Regenerate `ui/src/api/types.gen.ts` from the springdoc snapshot; extend queries and MSW handlers
- [x] 5.2 `ui/src/pages/audit.tsx`: NDJSON download link, sinks table (target, cursor, backlog), create form with show-once secret, reset-cursor control — JSDoc `@Requirements GW_0030`

## 6. Tests

- [x] 6.1 NDJSON export: one JSON object per line, ascending, `after` skips delivered entries, cursor header resumes — `@SVCs({"SVC_GW_0027"})`
- [x] 6.2 Webhook sink: batch enqueued as a signed delivery carrying the ledger entries, cursor advanced, second pass enqueues nothing — `@SVCs({"SVC_GW_0028"})`
- [x] 6.3 Cursor reset re-delivers the entries after the given position — `@SVCs({"SVC_GW_0029"})`
- [x] 6.4 Vitest component test for the audit page; Playwright e2e `audit_page_exports_the_ledger_and_lists_sinks` — `@SVCs SVC_GW_0030`

## 7. Verification

- [x] 7.1 `./mvnw clean verify`
- [x] 7.2 `(cd ui && pnpm e2e)`
- [x] 7.3 `reqstool status local -p docs/reqstool` ends PASS
- [x] 7.4 `openspec validate --all --strict`
