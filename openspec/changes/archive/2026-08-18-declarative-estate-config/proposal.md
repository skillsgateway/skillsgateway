# Declarative estate configuration — full configuration-as-code

GitHub issue #65.

## Why

Everything under `skills-gateway.*` is already configuration — retention,
vetting, sync, catalog, token policy, role enforcement — but the estate itself
(marketplaces, role grants beyond the bootstrap admins, webhook subscribers,
audit sinks) is API-only runtime state. A pure GitOps deployment, where the
whole service is defined in a repository and applied by a pipeline, cannot
exist: someone has to click or curl the estate into being after every fresh
environment. The driver is deploying into a platform where every service is
fully defined as code.

## What Changes

- **A `skills-gateway.estate.*` block** declaring marketplaces, role grants,
  webhook subscribers, and audit export sinks.
- **A reconciler** that converges the running estate to the declaration at
  startup (after schema migration, before the web surface serves) and on
  demand via a new admin-only endpoint. Reconciliation is **additive and
  idempotent**: declared objects are created or updated to match; objects
  absent from the declaration are never deleted, deregistered, or revoked;
  a converged estate reconciles with zero writes and zero ledger entries.
- **The same trust boundary**: declared marketplaces pass the exact
  registration validation the API applies (name rules, reserved catalog name,
  URL scheme allowlist); the estate block has no ref field at all, so the
  gateway-pinned ref cannot be overridden; declared grants pass the same
  grant validation; every applied change lands on the append-only ledger
  under the same event name as its API equivalent, attributed to the distinct
  actor `config-reconciler`.
- **Operator-supplied secrets for declared receivers**: webhook subscribers
  and audit sinks declare their signing secret by reference (environment
  variable / property placeholder). The value is never logged, never audited,
  never answered by any API; changing the referenced value rotates the stored
  secret idempotently.
- **Failure isolation**: an invalid declared entry never prevents startup and
  never blocks the other entries — it is reported in the log, on the ledger,
  and through a new reconciliation-report endpoint.
- Out of scope (declared non-goals): personal access tokens (user-owned
  credentials, API-only by design); an authoritative/prune mode (retracting
  content teams depend on must never begin because of a deploy — recorded as
  a possible follow-up behind an explicit opt-in); webhook *sync mode* for
  declared marketplaces (its inbound HMAC secret is gateway-generated
  show-once, so it stays API-only like PATs); scheduled re-reconciliation;
  portal UI (the ledger and the report endpoint are the visibility surface);
  IdP group-to-role mapping (#66 — the grants block is shaped so it can slot
  in later).

## Capabilities

### New Capabilities

- `declarative-estate`: the declarative estate block and its reconciliation —
  additive idempotent convergence, trust-boundary reuse, operator-supplied
  secrets by reference, failure isolation and reporting
  (GW_0083–GW_0087).

### Modified Capabilities

<!-- none: registration (GW_0016/GW_0017), grants (GW_0071), receivers
     (GW_0024, GW_0028) are unchanged; the reconciler goes through them -->

## Impact

- **Schema**: none — reconciliation writes only existing tables.
- **Backend**: new `estate` package (`EstateReconciler`, `EstateController`,
  `EstateBootstrap`); `SkillsGatewayProperties.Estate`; creation flows
  extracted from `AdminController`/`RoleController`/`WebhookController`/
  `AuditController` into their services so the reconciler and the API share
  one validated, audited path (behavior of the endpoints unchanged, held by
  the existing SVC suites).
- **API**: `POST /api/estate/reconcile` (admin), `GET /api/estate`
  (auditor-or-admin); both classified in the deny-by-default walk. OpenAPI +
  TS types regenerate.
- **Trust boundary**: registration allowlist and role grants — old-coder
  Tier 3; adversarial tests and mutants mandatory.
- **Docs**: `reference/configuration.md` (the block, exhaustively), new
  `guides/declarative-estate.md`, new `reference/api/estate.md`,
  trust-boundaries note, glossary; CLAUDE.md note recording #65/#66 as
  continuous obligations.
- **Traceability**: GW_0083–GW_0087 + SVC_GW_0083–SVC_GW_0087
  (GW_0073–GW_0082 are taken by in-flight changes).
