## Context

Issue #221 is one transparency surface split into a backend half (ledger data
richness) and a frontend half (a sortable/filterable table). This change is the
backend half only. Three defects, all in the emitted ledger data, all in the
`vetting` and `audit` packages.

## Decisions

### The vetting chain is `SYSTEM`, not a new actor kind

`ActorType` already has `HUMAN`, `MACHINE` (a machine API credential) and
`SYSTEM` (the gateway acting on its own: reconciliation, the schedulers, the
waiver sweep). The vetting chain is the gateway's own automated subsystem, which
is exactly what `SYSTEM` means, so the fix is to add the `vetting` principal to
`AdminAuditLogger.SYSTEM_ACTORS` — the one place an entry's actor kind is
decided — rather than to introduce a fourth value. Introducing one would have
been a native-enum migration (a new value cannot be dropped) for a distinction
the vocabulary already draws. `MACHINE` was rejected because it is reserved for
externally-presented machine credentials, and the chain presents none.

### `summary` lives on `Verdict`, consumed by `detailOf`

The coverage statement is the connector's own knowledge — how many files it
scanned, how many rules it applied — so it belongs on the connector's answer,
`Verdict`, as a new `summary` component. `VettingRepository.detailOf` already
computes the persisted `detail`; it now falls back to `summary` when a verdict
has no findings. This keeps one place deciding the `detail` value, surfaces the
coverage in `GET /api/snapshots/{id}/vetting` as well as the ledger for free, and
leaves the finding-summary path (`N finding(s); worst X`) unchanged. `Verdict` is
not in the served OpenAPI document, so the new component is not an API change;
`VerdictView.detail` is, and only its description moved.

### The `connector=state` lead is preserved

The existing `SVC_GW_0043` ledger test asserts the verdict detail *contains*
`secret-scan=fail` and `prompt-injection=pass`. The enriched detail keeps that
exact lead and appends `; findings=…; worst=…; run=…`, so the substring
assertions stay valid and the SVC is not weakened — the new facts are additive.

### The run "link" is the run id, not a URL

There is no per-run REST endpoint (`GET /api/snapshots/{id}/vetting` returns the
snapshot's *latest* run), so a URL would be synthetic. The run id is the stable
identity that ties a snapshot's scattered `vetting-verdict` rows and its
`vetting-completed` row to the one run, which is what "auditable on its own"
requires; the portal can build a snapshot link from the `sha` the entry already
carries.

## Open questions / to confirm

- **`actor_type` is absent from the NDJSON export DTO.** The portal reads
  `/api/audit` (`SELECT *`, carries `actor_type`), so the actor fix surfaces
  there. A SIEM consuming `/api/audit/export` still will not see the actor kind.
  Adding it to `FetchLogRepository.AuditEntry` is a small, arguably in-spirit
  extension of `GW_0128` but changes the export contract; left out of this change
  deliberately. Confirm whether it should ride along.
- **`run` id vs. a stable public reference.** If a per-run endpoint is planned,
  the ledger reference could later be a URL; the id is forward-compatible with
  that.
