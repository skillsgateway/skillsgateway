# Proposal: add-lifecycle-event-webhooks

## Why

The governance state of a marketplace changes inside the gateway (a snapshot is
ingested, approved, or rejected) but nothing outside the gateway learns about it:
integrators poll `/api/marketplaces` or find out when a clone changes. Issue #27 asks
for outbound lifecycle webhooks so that CI, chat, and inventory systems can react to
vetting decisions the moment they are made — the same events the audit ledger already
records, pushed instead of polled.

## What Changes

- Snapshot lifecycle events — `snapshot.ingested`, `snapshot.approved`,
  `snapshot.rejected` — are emitted whenever the corresponding admin action succeeds.
  (The snapshot state machine today is `held → approved | rejected`; there is no
  revocation or re-vet state, so no `snapshot.revoked` event is defined. Adding one is
  a follow-on to a revocation capability, not this change.)
- Webhook subscribers: name, target URL (scheme allowlisted like marketplace URLs), a
  per-subscriber event filter, and a generated secret shown exactly once at creation
  (stored hashed for lookup is not possible — the secret must be usable for signing, so
  it is stored and never re-exposed over the API).
- Every payload is signed `X-Skills-Gateway-Signature: sha256=<hex>` — HMAC-SHA256 over
  the exact JSON body bytes with the subscriber's secret — alongside event, delivery id,
  and timestamp headers, so receivers can authenticate and de-duplicate.
- Delivery is asynchronous and persisted: each (event, subscriber) pair becomes a
  delivery row, attempted by a background dispatcher with exponential backoff and a
  bounded attempt count; every attempt records its HTTP status or error.
- Admin API: create/list/delete subscribers, list recent delivery attempts.
- Portal: a Webhooks page listing subscribers (with their event filters) and their
  recent delivery attempts with status and attempt count.
- New requirements GW_0023–GW_0026 with SVC_GW_0023–SVC_GW_0026.

## Capabilities

### New Capabilities

- `lifecycle-webhooks`: outbound, HMAC-signed, per-subscriber-filtered delivery of
  snapshot lifecycle events with retry/backoff and a durable delivery record
  (GW_0023, GW_0024, GW_0025), plus its portal surface (GW_0026).

### Modified Capabilities

(none — existing requirements are unchanged; the admin API gains endpoints but no
existing requirement changes)

## Impact

- New: `webhook/` package (subscriber + delivery repositories, signer, dispatcher,
  controller), Flyway migration `V2__webhooks.sql` (tables `webhook_subscribers`,
  `webhook_deliveries`), `ui/src/pages/webhooks.tsx`.
- Changed: `AdminController` (event emission on ingest/approve/reject),
  `SkillsGatewayProperties` (webhook dispatcher tuning), `SkillsGatewayApplication`
  (`@EnableScheduling`), `ui` router/nav, generated OpenAPI types, MSW handlers,
  `docs/reqstool/*.yml`.
- Outbound HTTP from the gateway to operator-configured URLs is a new egress surface;
  the URL scheme allowlist and admin-only configuration bound it.
