## Why

The audit ledger is the product's transparency surface, but a reviewer reading a
vetting run in it today cannot see **what vetting actually did**
([#221](https://github.com/skillsgateway/skillsgateway/issues/221)). The full
detail already exists in `GET /api/snapshots/{id}/vetting`; the ledger throws
most of it away, in three distinct ways.

- **Verdict rows are lossy.** A `vetting-verdict` entry records only
  `secret-scan=pass`. There is no finding count, no worst severity, and no
  reference back to the chain run the verdict belongs to, so the ledger is a
  pointer you have to re-derive from the vetting tables rather than an auditable
  record on its own. `GW_0043` already requires the run and every verdict to be
  logged; it does not require them to be legible.
- **A clean pass says nothing.** `secret-scan` and `prompt-injection` return
  `detail: null, findings: []`, so their `detail` column is `NULL`. On a
  fail-closed surface a pass with zero substance is indistinguishable from a
  connector that did not run — the reader cannot tell coverage from absence.
- **The actor kind is mislabelled.** The automated vetting chain writes its
  entries under the principal `vetting`, which is not in `AdminAuditLogger`'s
  system-actor set, so every vetting entry falls through to `ActorType.HUMAN`.
  The portal audit table then renders the automated subsystem as a **human**
  actor — a transparency defect on the transparency surface. `GW_0128` already
  requires the ledger to distinguish a person, a machine credential and the
  gateway acting on its own; the vetting principal simply was not classified.

## What Changes

Backend only. The frontend half of #221 (sortable/filterable audit table on
`@tanstack/react-table`) is a separate change owned by another branch.

- **Verdict entries become self-describing (`GW_0142`).** The `vetting-verdict`
  ledger detail keeps its scannable `connector=state` lead and gains the finding
  count, the worst severity present (`none` when there are none), and the id of
  the chain run. The `vetting-completed` entry gains the same run id, so the
  scattered per-connector rows can be reassembled into the one run they came
  from.
- **A clean pass records its coverage (`GW_0143`).** Every built-in connector
  now returns a non-empty `summary` on its `Verdict` — what it examined, the
  files it scanned and the rules it applied — even for a pass with no findings.
  `VettingRepository` records that summary as the verdict `detail` when there are
  no findings, so a passing verdict carries positive evidence of what was checked
  in both the vetting report and the ledger.
- **The vetting chain is typed as the gateway (`GW_0142` / `GW_0128`).** The
  `vetting` principal is declared as a constant on `VettingService` and added to
  `AdminAuditLogger`'s system-actor set, so its entries are typed
  `ActorType.SYSTEM`. This reuses the existing actor vocabulary — the chain is
  the gateway acting on its own, exactly what `SYSTEM` already means — rather
  than inventing a fourth kind.

Not breaking. Every change is an enrichment of a free-text `detail` string, a
now-non-null value in an existing nullable column, or a corrected actor
classification on an existing typed column. No API path, request shape,
configuration key or database schema changes. The `Verdict` record gains a
`summary` component, but `Verdict` is an internal domain type not present in the
served OpenAPI document.

## Capabilities

### New Capabilities

None. This change adds requirements to an existing capability.

### Modified Capabilities

- `snapshot-vetting`: `GW_0142` — a vetting ledger entry carries the finding
  count, worst severity and run reference and is attributed to the gateway rather
  than a person; `GW_0143` — a clean connector pass records what it examined.

## Impact

**Code**

- `vetting/Verdict.java` — a `summary` component and an `of(findings, summary)`
  factory
- `vetting/SecretScanConnector.java`, `PromptInjectionConnector.java`,
  `LicenseScanConnector.java` — compute and pass the coverage summary
- `vetting/VettingRepository.java` — record the summary as the pass detail
- `vetting/VettingService.java` — the `VETTING_ACTOR` constant and the enriched
  verdict/completed ledger details
- `admin/AdminAuditLogger.java` — the vetting principal joins the system-actor
  set
- `docs/reqstool/requirements.yml`, `software_verification_cases.yml` —
  `GW_0142`, `GW_0143` and their SVCs

**Behavior**

Vetting ledger entries carry more, a passing connector's `detail` is no longer
null, and the portal audit table shows vetting rows as `system` rather than
`human`. Existing SVC assertions that match `secret-scan=fail` /
`prompt-injection=pass` as substrings remain valid because the enriched detail
keeps the `connector=state` lead.

**Documentation**

`docs/manual/reference/audit-ledger.md` gains the enriched vetting entry shape
and the actor-kind row; the vetting guide notes that a clean pass now states its
coverage.

**Out of scope**

The NDJSON export DTO (`FetchLogRepository.AuditEntry`) omits `actor_type`; the
portal reads the `SELECT *` `/api/audit` endpoint, which carries it, so the
actor-kind fix surfaces without touching the export. Adding `actor_type` to the
export contract is a separate concern. The audit-table UI is the frontend half of
#221.
