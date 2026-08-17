# Upstream sync modes: scheduled and webhook-triggered ingestion

GitHub issue: #32

## Why

Ingestion today is manual per marketplace: content only enters quarantine when an
operator clicks or calls `POST /api/marketplaces/{name}/ingest`. An estate of many
upstreams goes stale unless someone remembers to poll each one, and a forge that can
announce a push has no way to tell the gateway. Sync must become a per-marketplace
policy — while approval stays exactly where it is: a new snapshot lands held no matter
what triggered it.

## What Changes

- Each marketplace gets a **sync mode**: `on-demand` (today's behavior, the default),
  `scheduled` (the gateway polls upstream on an interval), or `webhook` (a forge push
  webhook triggers ingestion).
- A **scheduled sync sweep** ingests `scheduled` marketplaces in bounded, oldest-first
  batches (same shape as the retention and re-vetting sweeps). A failed upstream fetch
  is logged and retried next sweep; it never affects what the facade serves.
- An **inbound webhook endpoint** (`POST /hooks/{marketplace}`) authenticated by a
  per-marketplace HMAC-SHA256 signature (GitHub-compatible `X-Hub-Signature-256`).
  The payload is never trusted: a valid signature is only a "poll now" signal — the
  gateway ingests the registered URL's default branch, nothing from the request body.
- A **sync-mode admin endpoint** to change a marketplace's mode; switching to
  `webhook` generates the secret and returns it exactly once.
- **Upstream-outage resilience stated explicitly**: the facade always serves the last
  approved snapshot regardless of upstream health — a failing upstream fetch is
  invisible to git clients.
- Sync-triggered ingestion lands **held** (or rejected on manifest policy violation),
  runs the vetting chain, is audit-logged with its trigger, and emits the existing
  `snapshot.ingested` lifecycle event. Sync mode never bypasses approval.
- Out of scope (follow-ups): portal UI for sync mode (API-only in this change; the
  portal keeps working unchanged), GitLab-token-style webhook auth, per-marketplace
  poll intervals.

## Capabilities

### New Capabilities

- `upstream-sync`: per-marketplace sync mode, the scheduled sweep, the inbound
  webhook trigger with HMAC verification, outage resilience, and the audit trail
  (GW_0056–GW_0060).

### Modified Capabilities

<!-- none: registration, ingestion mechanics, vetting and approval requirements are
     unchanged; this change only adds new triggers in front of the same pipeline -->

## Impact

- **Schema**: `marketplaces` gains `sync_mode`, `webhook_secret`, `last_sync_at`
  (folded into `V1__init.sql` per convention).
- **Backend**: new `sync` package (scheduler + trigger service + inbound webhook
  controller), `Marketplace` record and repository, `SkillsGatewayProperties.Sync`,
  security config opening `/hooks/**` (HMAC-authenticated, not OIDC).
- **API**: new `PUT /api/marketplaces/{name}/sync`, new `POST /hooks/{marketplace}`;
  `MarketplaceView`/`Marketplace` gain `syncMode`. OpenAPI snapshot and generated TS
  types regenerate.
- **Trust boundary**: the inbound webhook endpoint is a new unauthenticated-by-OIDC
  surface — old-coder discipline, adversarial/negative tests required (bad signature,
  missing signature, wrong marketplace, replayed/oversized body, mode not webhook).
- **Docs**: MkDocs manual pages for configuration and REST API, new sync page.
- **Traceability**: new GW_0056–GW_0060 + SVC_GW_0056–SVC_GW_0060 in
  `docs/reqstool/`.
