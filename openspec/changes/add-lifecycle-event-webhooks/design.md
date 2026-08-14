# Design: add-lifecycle-event-webhooks

## Context

Snapshot lifecycle transitions are already the gateway's governance events: they are
recorded by `AdminAuditLogger` at exactly three call sites in `AdminController`
(`marketplace-registered` aside: `snapshot-ingested`, `snapshot-approved`,
`snapshot-rejected`). The state machine in `SnapshotRepository.decide` is
`held → approved | rejected` and nothing else — there is no revocation or re-vet
violation state today, so this change covers the three states that exist. Persistence is
`JdbcClient` + Flyway, there is no message broker, and the deployment target is a single
gateway process (Helm chart, one replica).

## Goals / Non-Goals

**Goals:** at-least-once, durable, signed delivery of the three lifecycle events to
operator-registered subscribers, with per-subscriber filtering, bounded retry with
exponential backoff, and an operator-visible delivery record (GW_0023–GW_0026).

**Non-Goals:** a `snapshot.revoked` event (no revocation capability exists); fan-out
across multiple gateway replicas; per-subscriber custom headers, mTLS, or payload
templates; replay/redelivery from the portal; webhook receipt (inbound hooks).

## Decisions

- **Events are emitted from the controller, next to the audit-ledger call**, not from
  `ApprovalService`/`IngestionService`. The ledger call site is already the definition of
  "the admin action succeeded", the acting principal is in scope there, and the services
  stay free of an outbound-HTTP dependency. Emission is a synchronous *enqueue* only:
  `WebhookService.emit(...)` writes one `webhook_deliveries` row per matching subscriber
  and returns. An admin action never blocks on, or fails because of, a receiver.

- **Two tables, added in a new migration `V2__webhooks.sql`.** The repo's standing
  convention is a single `V1__init.sql`; the owner directed this change to add the next
  version instead, so V1 is left untouched and V2 adds:
  - `webhook_subscribers(id, name UNIQUE, url, secret, events, enabled, created_at)` —
    `events` is a comma-delimited list of event names (`*` = all). A text list keeps the
    JdbcClient mapping trivial and native-image-safe versus a Postgres array type.
  - `webhook_deliveries(id, subscriber_id, event, payload, state, attempts,
    next_attempt_at, last_status, last_error, created_at, updated_at)` with
    `state IN ('pending','delivered','failed')` and an index on
    `(state, next_attempt_at)` — the dispatcher's only query.

- **Dispatcher = `@Scheduled` poller with `FOR UPDATE SKIP LOCKED`.** A fixed-delay
  scheduled method claims due pending deliveries (`state='pending' AND next_attempt_at <=
  now()`), POSTs each with a `RestClient` under a connect/read timeout, and updates the
  row. `SKIP LOCKED` makes the claim safe if a second replica ever runs. Chosen over an
  in-memory queue (loses deliveries on restart, invisible to operators) and over a broker
  (no broker in the stack).

- **Backoff:** attempt *n* (1-based) schedules the next attempt at
  `now + base * 2^(n-1)`, capped, with `base` and `max-attempts` configurable under
  `skills-gateway.webhooks.*` (defaults 10s base, 5 attempts, 1h cap). Reaching
  `max-attempts` sets `state='failed'`; a 2xx sets `state='delivered'`. Non-2xx and
  transport errors are both retryable — a receiver returning 4xx is usually a
  misconfiguration the operator will fix, and the bounded attempt count contains the cost.

- **Signature:** `X-Skills-Gateway-Signature: sha256=<lowercase hex>` = HMAC-SHA256 over
  the exact serialized body bytes with the subscriber's secret, plus
  `X-Skills-Gateway-Event`, `X-Skills-Gateway-Delivery` (delivery id, the de-duplication
  key), and `X-Skills-Gateway-Timestamp`. The payload JSON is serialized once at emit
  time and stored in `webhook_deliveries.payload`, so every retry sends byte-identical
  content and the stored signature basis never drifts.

- **Secret handling:** generated like a PAT (`SecureRandom`, 32 bytes, URL-safe base64,
  `whsec_` prefix) and returned in cleartext exactly once at creation. Unlike a PAT it
  must be stored recoverably — signing needs the key — so it is stored as-is and no read
  endpoint ever returns it (`SubscriberView` has no secret field). This trade-off is
  stated in GW_0024 rather than hidden.

- **Subscriber URLs go through the same scheme allowlist as marketplace URLs**
  (`SkillsGatewayProperties.allowedUrlSchemes`), failing closed on unparseable or
  scheme-less URLs; the admin API is OIDC-only, so registering an egress target is
  already an authenticated administrative act.

- **Portal page is read-mostly:** a `Webhooks` nav entry with a subscribers table
  (name, URL, events, state) plus a create form reusing the show-once pattern from the
  tokens page, and a deliveries table (event, subscriber, state, attempts, last status).
  No editing, no manual redelivery in v1.

## Risks / Trade-offs

- [Secrets are stored recoverably, unlike PATs] → required for signing; mitigated by
  never exposing them over the API and by the secret being useless without the target
  endpoint. Envelope encryption at rest is a later, separate requirement.
- [Polling dispatcher adds latency up to one poll interval] → interval defaults to 5s;
  lifecycle events are human-triggered, so seconds are irrelevant.
- [At-least-once, not exactly-once] → receivers de-duplicate on the delivery id header,
  which is stated in the payload contract.
- [Outbound HTTP is a new egress surface (SSRF-adjacent)] → registration is admin-only
  and scheme-allowlisted; host allowlisting is deferred to the same future requirement
  that will cover marketplace host policy.

## Migration Plan

Additive: `V2__webhooks.sql` creates two new tables; no existing table or row changes.
With no subscribers registered the dispatcher is a no-op query every 5 seconds. Rollback
is a revert plus dropping the two tables.

## Open Questions

(none)
