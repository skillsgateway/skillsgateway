## Why

The vetting chain (GW_0037–GW_0043) and waivers (GW_0044–GW_0048) gate the moment of
**approval**, and nothing after it. Once a snapshot is published it is never looked at again:
a connector can gain a rule, an advisory can land, and — the sharpest case — an accepted risk
can expire, and the content keeps being served regardless.

The waivers design named this gap in its own non-goals: *"an expired waiver re-blocks the gate;
it does not un-publish already-served content"*. `ARCHITECTURE.md` §5 has specified the answer
since the beginning — a `revoked` snapshot state whose refs the façade refuses, with a
blast-radius report from the ledger — and seven manual pages currently assert the opposite
("there is no revocation state"). This change closes the gap and resolves that contradiction.

## What Changes

- **New**: a scheduled sweep re-runs the chain over approved snapshots, oldest-evidence-first in
  bounded batches, recording a new run per pass with trigger `revet-scheduled` and the identity
  and version of every connector that produced it.
- **New**: on-demand re-vetting of one snapshot or of every approved snapshot of a marketplace
  (`revet-manual`) — the v1 answer to "a scanner or advisory feed moved".
- **New**: a `revoked` snapshot state. Under `enforce`, a re-vetting violation moves the snapshot
  to it and removes both published refs, so the façade stops serving the content.
- **New**: a warn-only mode, and it is the **default** — the violation is recorded and announced
  in full, and publication is untouched.
- **New**: an inconclusive classification. A run that blocks only because a connector errored,
  timed out or has not answered never retracts anything, in either mode.
- **New**: `snapshot.revet_violation` and `snapshot.revoked` lifecycle events, and a blast-radius
  report of every identity that fetched a snapshot's content, read from the fetch ledger.
- **BREAKING**: the snapshot state machine gains `approved → revoked` and `revoked → approved |
  rejected`. `Snapshot.state` and `Run.trigger` widen; API consumers that switch on either must
  handle the new values.
- **Modified**: retention now names its deletable states explicitly rather than as "not approved",
  so `revoked` is admitted deliberately and only through the criteria that should reach it.

## Capabilities

### New Capabilities
- `continuous-revetting`: re-vetting of approved content and the retroactive quarantine it can
  trigger — the sweep and its scheduling, the revoked state and its unpublication, warn versus
  enforce, the inconclusive rule, notification and blast radius, the ledger record, and the
  portal surface (GW_0049–GW_0055).

## Impact

- **Schema**: `snapshots.state` gains `revoked` and the table gains `revoked_at` / `revoked_by`;
  `vetting_runs` gains `chain`; one partial index for the sweep's queue. Folded into `V1__init.sql`.
- **API**: `POST /api/snapshots/{id}/revet`, `POST /api/marketplaces/{name}/revet`,
  `GET /api/snapshots/{id}/fetchers`. Approve and reject accept a `revoked` snapshot.
- **Code**: `RevetService`, `RevetVerdict`, `RevetScheduler`, `RevetController`;
  `GitStorage.unpublish`; `VettingConnector.version`; `SnapshotRepository.revoke`/`dueRevet`;
  `FetchLogRepository.fetchersOf`; two new webhook events.
- **Portal**: shared snapshot-state badge with `revoked`, a re-vetting panel with **Re-vet now**
  and the already-fetched-by list, and **Re-approve** on a revoked snapshot.
- **Docs**: a new guide, a new configuration block, the state-machine diagram and every page that
  asserted there is no revocation state.
- **Traceability**: GW_0049–GW_0055 and SVC_GW_0049–SVC_GW_0055.
