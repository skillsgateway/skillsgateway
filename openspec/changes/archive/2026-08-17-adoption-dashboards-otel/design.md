# Design: adoption-dashboards-otel

## Context

Every facade fetch already lands on the append-only `fetch_log`
(`FetchAuditHook.record`), attributed to the authenticated principal and the
token that authenticated it. `FetchLogRepository.fetchersOf(sha)` (GW_0053)
established the pattern this change generalizes: `upload-pack` entries are the
ones that mean "content was received" — `info-refs` fires on every `git fetch`
whether or not anything transfers. The served tip of a marketplace is
`refs/heads/main` of its published repository (`GitStorage.publishedIfServing`),
the same single source of truth the facade and the catalog use.

On the observability side, the pom already carries
`arconia-opentelemetry-spring-boot-starter` and actuator; `arconia.otel.enabled`
defaults to `false`, with the opt-in `observability` profile turning on export
and the local LGTM stack. Actuator auto-configures a `MeterRegistry` and an
`ObservationRegistry` regardless, so recording is free even when nothing
exports.

## Goals / Non-Goals

**Goals:**

- Answer "who runs what, how much, and how stale" from the ledger alone, with
  zero new writes and no change to what is recorded.
- Metrics/spans for the three flows that matter (ingestion, approvals, facade
  fetches), recorded always, exported only when a deployment opts in.
- The default posture of verify/e2e — no OTLP export attempts, no extra
  containers — untouched.

**Non-Goals:**

- No team concept: attribution is by identity/token as the ledger records
  today; identity→team mapping belongs to the IdP.
- No notification/alerting on staleness (the report is what an operator or a
  SIEM acts on; sinks already stream the ledger).
- No per-marketplace or per-identity metric tags (cardinality; the API carries
  those dimensions).
- No new persisted state of any kind.

## Decisions

1. **Two endpoints, both auditor-gated reads.** `GET /api/adoption?days=N`
   (default 30, clamped 1..365) and `GET /api/adoption/staleness`. They
   enumerate identities off the ledger, which is exactly the class of read
   GW_0068/GW_0070 call "the ledger and the operational listings", so both
   start with `roleService.requireAuditor(...)` and are added to the
   `PRIVILEGED_READS` classification in RoleEnforcementTests. No new
   requirement text for authorization — the existing enforcement covers it.

2. **Adoption = GROUP BY over `upload-pack` entries in the window.** One SQL
   aggregation per request:
   `GROUP BY marketplace, sha` with `COUNT(*)`, `COUNT(DISTINCT principal)`,
   `MAX(ts)`, filtered to `event = 'upload-pack' AND ts >= now() - window`.
   The service folds rows into per-marketplace summaries and marks each SHA
   `current` by comparing against the served tip. The synthesized catalog
   appears like any marketplace — its fetches are ledger entries under its
   name, and its tip resolves the same way.

3. **Staleness is window-free and tip-anchored.** An identity's staleness is a
   property of its *latest* fetch, not of a reporting window: one
   `DISTINCT ON (principal, marketplace) … ORDER BY id DESC` query yields each
   identity's most recent received SHA per marketplace, and the service keeps
   the rows whose SHA differs from the served tip. A marketplace that stopped
   serving (revoked, unpublished) has no tip; every identity holding its
   content is reported stale with `servedSha = null` — that is the retraction
   case the report exists for. Identities are principals; entries with a null
   principal (none exist on the PAT-only facade, but the column allows it) are
   excluded rather than grouped into a fake identity.

4. **Tips come from git, not from the snapshots table.** The served tip is
   resolved per marketplace via `GitStorage.publishedIfServing` +
   `refs/heads/main` — the same read the facade serves from — so the report can
   never disagree with what a `git fetch` would actually return. A DB
   reconstruction ("latest approved snapshot") would re-derive what the
   published repo already states, and diverge exactly in the failure cases the
   report is for.

5. **One covering partial index.** `fetch_log` is append-only and read by four
   patterns now; the new queries scan `event = 'upload-pack'` rows by
   (marketplace, ts) and by (principal, marketplace, id desc). A single partial
   index on `(marketplace, principal, id)` `WHERE event = 'upload-pack'` covers
   the staleness DISTINCT ON; the windowed aggregation stays a filtered scan
   (fine at the ledger sizes a report reads, and the report is not a hot path).
   Folded into `V1__init.sql` per the pre-1.0 convention.

6. **Instrumentation is a seam, not a scatter.** One
   `observability/GatewayMetrics` component owns every meter and observation
   name; the instrumented classes call one method each:
   - `skills_gateway.ingestion` — an Observation (timer + span when tracing is
     on) around `IngestionService.ingest`, low-cardinality `outcome` tag
     (`success`/`error`) from the observation's error state.
   - `skills_gateway.approval` — an Observation around
     `ApprovalService.approve`/`reject`, tag `decision` (`approve`/`reject`);
     a blocked approval is the observation's error.
   - `skills_gateway.facade.fetches` — a counter incremented in
     `FetchAuditHook.record`, tag `event` (`info-refs`/`upload-pack`). The
     HTTP span for `/git/**` already exists via the server observation; a
     second span per fetch would be noise, so the facade gets a counter only.
   Tags are closed sets by construction — never a marketplace, SHA, or
   principal. The wrapped methods keep their logic byte-for-byte; the
   observation wraps around the existing call (additive at the trust
   boundaries, per the risk rules).

7. **`arconia.otel.enabled=false` stays the default.** "By default" in the
   issue title means *recorded* by default: the meters live in the
   auto-configured `MeterRegistry` and observations run through the
   `ObservationRegistry` unconditionally, so enabling the `observability`
   profile (or `arconia.otel.enabled=true` in a deployment) starts exporting
   them with no code change. No new `skills-gateway.*` property is introduced —
   there is nothing to configure.

8. **Portal page.** `/adoption` under Governance: stat chips (window fetches,
   distinct identities, marketplaces fetched), one row card per marketplace
   with its per-SHA table, then the staleness table. A small window selector
   (7/30/90 days) drives the query parameter. Loading/empty/error states per
   the design conventions; no form (nothing to submit). The e2e spec exercises
   the real workflow: approve a marketplace, clone it through the facade with a
   PAT minted in the portal, then read the fetch on the Adoption page.

## Risks / Trade-offs

- [Aggregation reads the whole windowed ledger] → acceptable: reports are
  operator-paced, the window bounds the scan, and the partial index carries
  the latest-fetch query; revisit with materialization only if a real estate
  hurts.
- [Staleness names an identity that intentionally pinned an old SHA] → the
  report states facts ("latest fetch ≠ served tip"), not verdicts; the docs
  say exactly that.
- [Observation around `approve` could mask exceptions] → `Observation.observe`
  rethrows; tests cover the blocked-approval path still surfacing 409.
- [Meter registry growth] → all tag sets are closed enums; no unbounded
  dimension exists by construction.

## Migration Plan

No schema migration (pre-1.0: the index folds into `V1__init.sql`; dev/test
databases recreate). No new configuration. Rollback = revert; nothing persists
that the code created.

## Open Questions

None. Per-team attribution and staleness notifications are explicitly out of
scope (no team concept in the model).
