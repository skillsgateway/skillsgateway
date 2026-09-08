# ADR 0018 — Requirement ids carry a domain: `GW_<DOMAIN>_NNNN`, minted manually

*Accepted, 2026-09-08. Extends [issue #299](https://github.com/skillsgateway/skillsgateway/issues/299),
which adopted dot-notation decomposition ([ADR reference: none — tracked directly
in the reqstool-conventions skill](https://github.com/reqstool/reqstool-ai/blob/main/plugins/reqstool/skills/reqstool-conventions/references/reqstool-decomposition-conventions.md#id-conventions))
via `GW_INGEST_0026` — *Resource-bounded source resolution* — as the worked example.*

## Context

`docs/reqstool/requirements.yml` reached 172 top-level requirements under a
single flat sequence, `GW_0001`–`GW_0227` with several gaps left permanently
unused by design (an id is never reused once reserved). A flat, monotonically
issued id says nothing about which part of the system a requirement belongs
to: `GW_0098` (identity-provider claim to role mapping) and `GW_0158`
(resource-bounded source resolution) are adjacent in nothing but issue order.
Finding every requirement for a given area — auditing the vetting chain,
reviewing everything the facade promises — meant grepping titles, not reading
ids.

The reqstool-conventions skill's own decomposition-conventions reference
prescribes exactly this fix under "Prefix Naming Strategies": domain-specific
prefixes (`AUTH_`, `API_`, `CORE_`, …), optionally combined with a module
prefix when a project has more than one module. This project is single-module,
so the **module + domain** pattern collapses to **domain only**, with the
existing `GW_` reserved as the module/product prefix ahead of it:
`GW_<DOMAIN>_NNNN`.

## Decision

**Every requirement and SVC id gets a domain segment: `GW_<DOMAIN>_NNNN` /
`SVC_GW_<DOMAIN>_NNNN`, and every domain keeps its own sequence starting at
`0001`.** Dot-notation children (ADR-less, adopted via issue #299) sit under
the parent unchanged: `GW_INGEST_0026.1`, `SVC_GW_INGEST_0026.1`.

**Twelve domains**, assigned by reading each requirement against the project's
own OpenSpec capability specs (`openspec/specs/*/spec.md`) rather than by
title alone — that mapping already existed and is authoritative for what a
requirement is *about*:

| Domain | Rough scope | Count |
| --- | --- | ---: |
| `INGEST` | Registration, snapshot ingestion, sync, external plugin source resolution | 30 |
| `VETTING` | The vetting chain, connectors, waivers, re-vetting, license detection | 30 |
| `AUTH` | OIDC, PATs, machine credentials, RBAC, session integrity | 29 |
| `FACADE` | Git smart-HTTP serving, catalogs, storage backends, the read-only mirror | 28 |
| `APPROVAL` | The approval gate, four-eyes, CEL policy, release-age | 13 |
| `AUDIT` | The append-only ledger and its export | 7 |
| `WEBHOOK` | Outbound lifecycle event delivery | 7 |
| `RETENTION` | Snapshot retention, soft/hard delete | 7 |
| `API` | The REST/contract surface, SBOM | 6 |
| `RELEASE` | Packaging, container distribution, deployment charts | 6 |
| `ESTATE` | Declarative estate reconciliation | 5 |
| `OBSERVABILITY` | Adoption/staleness reporting, gateway metrics | 4 |

**Renumbered wholesale, not grown incrementally.** The project is
pre-`1.0.0` (`0.2.0-b2`); the reqstool-conventions skill treats pre-1.0 ids as
freely restructurable, and no other branch had an open PR against
`docs/reqstool/` at the time of this change (verified: `gh pr list --state
open` returned none). Every mention of an old id was rewritten in the same
change — `docs/reqstool/`, every `@Requirements`/`@SVCs` annotation, every
`docs/manual/` page, every ADR, and every archived and active OpenSpec change —
so `git grep -oE "GW_[0-9]{4}\b"` on the merged tree finds no *real* id in its
old form. It still finds bare four-digit numbers that were never real: ids
reserved and then deliberately abandoned (`GW_0074`, `GW_0113`, `GW_0119`,
`GW_0123`, `GW_0124`) and provisional ids named in **active, unimplemented**
change proposals (`corpus-aware-vetting`, `estate-import-export`,
`invocation-adoption-metrics`, the `client-invocation-telemetry` ADR,
`virtual-catalogs`). Neither category was ever entered in
`requirements.yml`/`software_verification_cases.yml`, so neither is a real id
to rename — this project's own precedent (the `add-webhook-payload-contract`
evidence, on `GW_VETTING_0025`/`GW_VETTING_0026`) already treats proposal-stage
id mentions as provisional, reconciled at implementation time. Whoever
implements one of those four changes next mints a domain-prefixed id when they
do; the old bare numbers in their proposals are historical drafting artifacts,
same as the deliberately-abandoned gaps.

**Ids are minted manually.** `.reqstool-ai.yaml` sets `req_prefix: ""` and
`svc_prefix: "SVC_"`, per the skill's own guidance for domain-specific
prefixes — there is no single counter for a generator to append to, only
twelve.

## Consequences

- An id now says what it's about before you open `requirements.yml`:
  `GW_VETTING_0012` reads as vetting-domain before anyone resolves it to
  *Continuous re-vetting of approved snapshots*.
- `git grep GW_INGEST_` (or any domain) is a working "everything in this area"
  query across requirements, SVCs, annotations and docs — it was not, before.
- This is the second and last time these ids move as a block. Post-1.0.0 the
  reqstool-conventions skill's own rule applies without exception: a domain's
  sequence only grows, an id is never reused, and a wrong domain call found
  later is fixed by deprecating and re-issuing under the right one — not by
  renumbering again.
- Thirteen domains' worth of judgement calls are compressed into this ADR's
  table rather than scattered across 172 individual choices with no record of
  why. A requirement that turns out to sit at a domain boundary (several were:
  `GW_APPROVAL_0004` — minimum release age — reads as much RELEASE as
  APPROVAL) is deliberately not relitigated case by case here; the table is
  the record to consult before assuming a placement was an oversight.

## What would reopen this

- A thirteenth functional area emerging that doesn't fit any existing domain
  cleanly — add a domain, don't force-fit it and don't renumber the others.
- Evidence that a domain boundary chosen here actively misleads (not merely
  debatable) — for example, if `OBSERVABILITY` and `AUDIT` turn out to need to
  be one domain rather than two. That is a deprecate-and-reissue exercise on
  the affected ids, not a second wholesale renumber.
