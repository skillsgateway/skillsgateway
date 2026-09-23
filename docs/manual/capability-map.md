# Capability map

Every capability area the gateway has today, one row each. If you are asking
"does the gateway do X?", the answer is on this page or it is no.

| Area | What it does | Trust boundary | Requirements | Docs |
| --- | --- | --- | --- | --- |
| Registration | Registers an upstream marketplace against the URL-scheme allowlist and a gateway-pinned ref, and removes one — withdrawing what it serves — so its name can be registered again. | Registration | `GW_INGEST` | [Registering a marketplace](guides/registering-a-marketplace.md), [Trust boundaries](concepts/trust-boundaries.md) |
| Ingestion | Clones a pinned commit into quarantine as an immutable snapshot with a provenance record and a content inventory. | Registration | `GW_INGEST` | [Lifecycle](concepts/lifecycle.md) |
| Source resolution | Resolves a manifest's external plugin sources into the snapshot under address, redirect and resource bounds, producing one composite commit. | Resolution | `GW_INGEST` | [Trust boundaries](concepts/trust-boundaries.md) |
| Upstream sync | Polls upstream or accepts an inbound forge webhook, and re-ingests when the pinned ref moves. | Inbound webhook | `GW_INGEST` | [Syncing from upstream](guides/upstream-sync.md) |
| First-party publishing | Accepts an authenticated push to a gateway-hosted marketplace; pushed content enters the same quarantine pipeline. | Publication | `GW_FACADE`, `GW_INGEST` | [Publishing first-party skills](guides/publishing-first-party-skills.md) |
| Vetting chain | Runs the vetter chain — secret scan, prompt injection, skill conformance, license, operator-configured external vetters — fail-closed, in an order an administrator can set, optionally stopping at the first failure. Which vetters run, in what order and how far is settable per marketplace, globally, or across a selection of marketplaces as one audited act. | — | `GW_VETTING` | [Vetting](concepts/vetting.md) |
| License compliance | Detects a declared license at ingestion and evaluates it against allow and ban lists through the chain. | — | `GW_VETTING` | [License compliance](guides/license-compliance.md) |
| Waivers | Suppresses a named finding for a scope, with an expiry that restores the block. | — | `GW_VETTING` | [Waiving a finding](guides/waiving-findings.md) |
| Re-vetting | Re-runs the chain against already-approved snapshots and auto-quarantines a violation. | Approval | `GW_VETTING` | [Re-vetting](guides/re-vetting.md) |
| Policy rules | Evaluates CEL deny rules over snapshot facts as a fail-closed gate at approval. | Approval | `GW_APPROVAL` | [Policy deny rules](guides/policy-rules.md) |
| Approval | Holds everything until a human approves, enforcing the vetting outcome, a complete closure, four-eyes and a minimum release age; publishing is the only path to served content. | Approval | `GW_APPROVAL` | [Approving snapshots](guides/approving-snapshots.md) |
| Snapshot preview | Shows a reviewer the snapshot's files and its diff against the served baseline, read-only. | — | `GW_INGEST`, `GW_APPROVAL` | [Approving snapshots](guides/approving-snapshots.md) |
| Git facade | Serves approved snapshots over read-only git smart-HTTP, advertising only the refs it serves. | Facade | `GW_FACADE` | [Git smart-HTTP facade](reference/git-facade.md) |
| Virtual catalog | Synthesises one global catalog repository from the approved marketplaces, refreshed on publication changes. | Facade | `GW_FACADE` | [The virtual catalog](guides/virtual-catalog.md) |
| Git storage | Holds repositories on a named backend — filesystem or object store — with identical ref transitions and a verified migration between them. | — | `GW_FACADE` | [Storage backends](guides/storage-backends.md) |
| Forge mirror | Mirrors approved content outward to an external forge, read-only and never an enforcement path. | Mirror (outbound) | `GW_FACADE` | [The read-only forge mirror](guides/read-only-forge-mirror.md) |
| Web authentication | Authenticates the portal and REST API with OIDC only, keeping tokens out of the browser. | Web surface | `GW_AUTH` | [Identity providers](guides/identity-providers.md) |
| Access tokens | Issues, scopes, expires and rotates the hashed PATs git clients fetch with, attributing every fetch. | Facade | `GW_AUTH` | [Access tokens](reference/api/tokens.md) |
| Machine credentials | Gives pipelines a distinct credential scope that cannot reach judgement, retraction or the administrative interface. | Machine API | `GW_AUTH` | [REST API overview](reference/api/index.md) |
| Roles | Enforces deny-by-default `admin` / `approver` / `auditor` authority, granted by audited grants or a validated claim mapping. | Roles | `GW_AUTH` | [Delegated administration](guides/delegated-administration.md) |
| Audit ledger | Records every fetch and every administrative action append-only, with the kind of actor, and streams it to export sinks. | — | `GW_AUDIT` | [Snapshots and the ledger](concepts/snapshots-and-ledger.md), [Exporting the ledger](guides/exporting-the-audit-ledger.md) |
| Adoption reporting | Derives install inventory and staleness against the served tip from the fetch ledger. | — | `GW_OBSERVABILITY` | [Adoption API](reference/api/adoption.md) |
| Observability | Records gateway metrics, storage health and mirror divergence whether or not anybody asks. | — | `GW_OBSERVABILITY` | [Observability](reference/observability.md) |
| Lifecycle webhooks | Delivers signed snapshot-lifecycle events to subscribers with retry and backoff. | — | `GW_WEBHOOK` | [Lifecycle webhooks](guides/lifecycle-webhooks.md) |
| Retention | Soft-deletes and then compacts eligible snapshots; approved ones are never eligible. The same pass trims audit-ledger read entries, only behind every enabled export sink and never the administrative half. | — | `GW_RETENTION` | [Reclaiming snapshot storage](guides/snapshot-retention.md) |
| Declarative estate | Reconciles marketplaces, role grants, policy rules and webhook receivers from configuration through the same audited paths as the API. | Registration, Roles | `GW_ESTATE` | [Declarative estate configuration](guides/declarative-estate.md) |
| Admin portal | The nine pages an operator works in: overview, marketplaces, marketplace detail, snapshot contents, vetting, audit, adoption, tokens, webhooks. | Web surface | `GW_AUTH` | [Admin portal](reference/portal.md) |
| REST API contract | Serves and publishes the versioned contract document, and detects a breaking change to it. | — | `GW_API` | [REST API overview](reference/api/index.md) |
| Release and packaging | Produces the container image release by digest, behind a gated release workflow. | — | `GW_RELEASE` | [Container image](reference/container-image.md), [Cutting a release](guides/releasing.md) |

## Deliberately parked

Scope that was considered and consciously not built. Each has an ADR; none has
an open commitment.

| Parked | Decision |
| --- | --- |
| Estate import/export | [ADR 0014 — Estate export: content and its attestations leave together; an import can only fill quarantine](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0014-estate-import-export.md) — rejected; the exit is documented in [Leaving the gateway](guides/leaving-the-gateway.md) |
| Corpus-aware vetting | [ADR 0015 — Corpus questions are approval-gate preconditions, not vetting vetters](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0015-corpus-questions-are-approval-gate-preconditions.md) |
| Invocation metrics | [ADR 0016 — Client invocation telemetry is not ingested; the gateway publishes presence instead](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0016-client-invocation-telemetry-is-not-ingested.md) |
| Virtual catalogs per team | [ADR 0017 — Virtual catalogs stay derived views: they may subtract, never substitute](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0017-virtual-catalogs-are-derived-views.md) |

Adding an area to the first table, or moving one out of the second, goes through
the stop rule in `CLAUDE.md`.
