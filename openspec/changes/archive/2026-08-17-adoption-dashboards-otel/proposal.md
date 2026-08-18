# Adoption dashboards and OpenTelemetry

GitHub issue #14.

## Why

The gateway has been keeping the answer to "who runs what?" all along — every
facade fetch lands on the append-only ledger — but nobody can read it without
SQL. An operator deciding whether a retraction is safe, or chasing consumers
still pinned to a superseded snapshot, needs the ledger aggregated: fetch
counts per marketplace, per snapshot SHA and per identity, and the list of
identities whose latest fetch is content the gateway no longer serves.
Operationally, the gateway also emits no metrics of its own: once a deployment
turns on OTLP export, the interesting numbers (ingestions, approval decisions,
facade traffic) should already be flowing without a code change.

## What Changes

- **Adoption reporting** (`GET /api/adoption`): read-only aggregation over the
  existing fetch ledger — per-marketplace fetch counts over a requestable time
  window, distinct fetching identities, and a per-snapshot-SHA breakdown with
  each SHA marked current or superseded against the served tip. No new writes,
  no new tables.
- **Staleness reporting** (`GET /api/adoption/staleness`): identities whose
  most recent facade fetch of a marketplace is a SHA that is not the currently
  served tip — including identities holding content of a marketplace that has
  stopped serving entirely (revoked or unpublished).
- **Scope limit (explicit)**: there is no "team" concept in the model.
  Adoption and staleness attribute by the authenticated identity and token the
  ledger records today; mapping identities to teams is the identity provider's
  knowledge, not the gateway's.
- **Portal**: a new "Adoption" page under the Governance nav group presenting
  both reports, dense operator-console style.
- **OpenTelemetry by default, export opt-in**: Micrometer counters/timers and
  observation-based spans named `skills_gateway.*` for ingestion, approval
  decisions, and facade fetches are always *recorded* through the
  MeterRegistry/ObservationRegistry, so they flow automatically whenever a
  deployment enables export. The repository's deliberate default —
  `arconia.otel.enabled=false` with the opt-in `observability` profile — is
  unchanged; verify/e2e still never attempt OTLP export.

## Capabilities

### New Capabilities

- `adoption-reporting`: the adoption and staleness reads over the fetch ledger
  and the portal page presenting them (GW_0075, GW_0076, GW_0078).
- `observability`: the always-recorded `skills_gateway.*` metrics and
  observations for ingestion, approvals and facade traffic (GW_0077).

### Modified Capabilities

<!-- none: the reads are auditor-gated by the existing GW_0068/GW_0070
     enforcement, whose requirement text already covers "operational-listing
     reads"; the instrumentation changes no behavior of the flows it times -->

## Impact

- **Backend**: new `adoption` package (`AdoptionService`,
  `AdoptionController`); ledger aggregation queries in `FetchLogRepository`;
  one covering index on `fetch_log` folded into `V1__init.sql` (pre-1.0
  convention); new `observability/GatewayMetrics` component injected into
  `IngestionService`, `ApprovalService` and `FetchAuditHook` — additive
  wrapping only, no trust-boundary logic touched.
- **API**: `GET /api/adoption`, `GET /api/adoption/staleness` — auditor-gated
  reads (they name identities off the ledger), classified in the
  RoleEnforcementTests walk. OpenAPI snapshot and generated TS types
  regenerate.
- **Portal**: `pages/adoption.tsx`, route `/adoption`, Governance nav entry,
  component tests, Playwright e2e spec covering the real workflow (fetch
  through the facade, read it on the page).
- **Docs**: new `reference/api/adoption.md`, new `reference/observability.md`,
  portal reference section, nav entries.
- **Traceability**: GW_0075–GW_0078 + SVC_GW_0075–SVC_GW_0078 (GW_0073/0074
  are reserved by another in-flight change).
- **Known limit (documented)**: metric tags are low-cardinality only (outcome,
  decision, event) — per-marketplace and per-identity numbers stay in the
  adoption API, not in metrics, so an estate's size can never blow up a
  time-series backend.
