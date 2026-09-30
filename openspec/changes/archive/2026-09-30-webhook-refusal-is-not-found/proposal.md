# Proposal: webhook-refusal-is-not-found

## Why

The 2026-09-29 review (issue #544) found that the inbound forge webhook,
`POST /hooks/{marketplace}`, answers `404` for an unknown marketplace and `403`
for a bad signature. The endpoint is unauthenticated, so the difference is a
marketplace-name oracle: anyone can learn which names exist and which are in
webhook mode. The facade has no such oracle by design (GW_AUTH_0006 —
Marketplace-scoped access tokens), and neither should this endpoint.

The same review found nine smaller backend defects under requirements that
already say the right thing. They ride on this change as tasks with no spec
delta.

## What Changes

- **BREAKING (pre-1.0, declared):** the webhook answers `404` for an unknown
  marketplace, a marketplace not in webhook mode, and a missing or invalid
  signature alike. The `403` response is removed from the OpenAPI document.
  The body-size bound (`413`) is decided before the marketplace is looked up,
  so it reveals nothing about the name either.
- GW_INGEST_0012 — Webhook-triggered ingestion — is amended to say the refusals
  are indistinguishable; its revision is bumped, and so is SVC_GW_INGEST_0012's.
- Defect fixes, no spec delta:
  - the external vetting connector and the webhook dispatcher close their HTTP
    responses;
  - an ingest whose snapshot insert fails removes the snapshot pin it wrote,
    unless a row already names that commit;
  - re-vetting a marketplace continues past one snapshot's failure, as the
    scheduled sweep does;
  - the vetting chain records a `snapshot-access` error only for a failure to
    read the snapshot's content, and lets any other failure (a database one)
    propagate;
  - the marketplace list reads every marketplace's snapshots in one query;
  - the forge metadata lookup bounds the response body it reads;
  - six configuration leaves fall back to their default at zero or below;
  - three configuration records that carry a secret redact it from `toString`;
  - the diff summary is cached per (marketplace, sha, baseline, path) in a
    bounded in-memory LRU, so a later page does not re-diff the whole delta.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `upstream-sync`: GW_INGEST_0012 — Webhook-triggered ingestion — refusals share
  one status.

## Impact

- API: `POST /hooks/{marketplace}` loses its `403` response; `openapi.json` and
  the generated portal types are regenerated. A forge configured with a wrong
  secret now shows `404` in its delivery log instead of `403`.
- Code: `InboundWebhookController`, `ExternalVettingConnector`,
  `WebhookDispatcher`, `IngestionService`, `RevetService`, `VettingService`,
  `AdminController`, `SnapshotRepository`, `ForgeMetadataService`,
  `SkillsGatewayProperties`, `SnapshotPreviewService`.
- Docs: `guides/upstream-sync.md`, `reference/api/marketplaces.md`,
  `concepts/trust-boundaries.md`, `reference/configuration.md`.
- No new dependency, configuration leaf, Spring test context or schema change.
