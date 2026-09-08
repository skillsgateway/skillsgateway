## Why

`docs/reqstool/requirements.yml` reached 172 top-level requirements under a
single flat sequence, `GW_0001`-`GW_0227` with several gaps left permanently
unused by design. A flat, monotonically issued id says nothing about which
part of the system a requirement belongs to: `GW_0098` (identity-provider
claim to role mapping) and `GW_0158` (resource-bounded source resolution)
were adjacent in nothing but issue order. Finding every requirement for a
given area meant grepping titles, not reading ids.

Issue [#299](https://github.com/skillsgateway/skillsgateway/issues/299)
already adopted dot-notation decomposition (`GW_INGEST_0026.1`-`.8`, merged as
#324) using the reqstool-conventions skill's ID Conventions. That same skill's
"Prefix Naming Strategies" section prescribes exactly the next fix: a
domain-specific prefix. This change adopts it.

See [ADR 0018](../../../docs/decisions/0018-domain-prefixed-requirement-ids.md)
for the full decision record, including why this is a wholesale renumber
rather than an incremental one (pre-`1.0.0`, no other branch had an open PR
against `docs/reqstool/` at the time) and why it is the last one.

## What Changes

**Every requirement and SVC id gains a domain segment and every domain keeps
its own sequence from `0001`.** No requirement text, scenario, or verification
description changes — this is an id rename, not a behavior change.

| Domain | Count |
| --- | ---: |
| `INGEST` | 30 |
| `VETTING` | 30 |
| `AUTH` | 29 |
| `FACADE` | 28 |
| `APPROVAL` | 13 |
| `AUDIT` | 7 |
| `WEBHOOK` | 7 |
| `RETENTION` | 7 |
| `API` | 6 |
| `RELEASE` | 6 |
| `ESTATE` | 5 |
| `OBSERVABILITY` | 4 |

Dot-notation children move with their parent unchanged in shape:
`GW_0158.1` -> `GW_INGEST_0026.1`.

**Deliberately not renamed** — neither was ever entered in
`requirements.yml`/`software_verification_cases.yml`, so neither is a real id:

- Ids reserved and then abandoned by design: `GW_0074`, `GW_0113`, `GW_0119`,
  `GW_0123`, `GW_0124`.
- Provisional ids named in active, unimplemented change proposals
  (`corpus-aware-vetting`, `estate-import-export`,
  `invocation-adoption-metrics`, `virtual-catalogs`) and the not-yet-accepted
  ADR 0016. Whoever implements one of those next mints a domain-prefixed id
  when they do, per this project's own precedent (`GW_VETTING_0025`/`26` were
  reassigned when another branch took the originally-proposed numbers while
  that PR sat unmerged).

`.reqstool-ai.yaml` sets `req_prefix: ""` / `svc_prefix: "SVC_"` — ids are
minted manually now, one domain sequence at a time, per the
reqstool-conventions skill's guidance for domain-specific prefixes.

## Capabilities

### Modified Capabilities

This change is a mechanical, meaning-preserving id rename across the entire
SSOT, so — as an explicit exception to keeping a proposal to one capability —
every capability with at least one requirement is touched. Each delta below
is `## RENAMED Requirements` only: no requirement text, scenario, or
significance changes.

- `admin-api`, `admin-portal`, `admin-roles`, `adoption-reporting`,
  `audit-export`, `auth`, `continuous-revetting`, `declarative-estate`,
  `forge-mirror`, `git-facade`, `git-storage`, `license-compliance`,
  `lifecycle-webhooks`, `marketplace-ingestion`, `observability`,
  `persistence-schema`, `policy-rules`, `release-packaging`, `sbom`,
  `snapshot-approval`, `snapshot-preview`, `snapshot-retention`,
  `snapshot-vetting`, `token-lifecycle`, `upstream-sync`, `vetting-waivers`,
  `virtual-catalog`: every `GW_NNNN`/`SVC_GW_NNNN` id in the capability
  renamed to its domain-prefixed form.

## Impact

- `docs/reqstool/requirements.yml`, `docs/reqstool/software_verification_cases.yml`
  — every id renamed.
- Every `@Requirements`/`@SVCs` Java annotation and TypeScript JSDoc tag
  across `src/main/java`, `src/test/java`, `src/main/frontend` — renamed to
  match.
- `docs/manual/**`, every ADR in `docs/decisions/` — every id mention renamed.
- Every OpenSpec change under `openspec/changes/`, including
  `openspec/changes/archive/**` — every id mention renamed (archived history
  rewritten deliberately, not left stale).
- `.reqstool-ai.yaml` — `req_prefix`/`svc_prefix` blanked.
- `CONTRIBUTING.md` — workflow step 2 describes the domain-prefix minting
  convention.
- New `docs/decisions/0018-domain-prefixed-requirement-ids.md`, indexed in
  `docs/manual/reference/decisions.md`.
- No production code path, API response, configuration key, or portal page
  changes.
