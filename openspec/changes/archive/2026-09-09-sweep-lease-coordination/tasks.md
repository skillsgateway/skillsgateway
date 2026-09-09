# Tasks: sweep-lease-coordination

## 1. The lease

- [x] 1.1 `sweep_leases` table in `V1__init.sql` — `name TEXT PRIMARY KEY`,
      `leased_until TIMESTAMPTZ NOT NULL`, `holder TEXT NOT NULL`,
      `updated_at TIMESTAMPTZ NOT NULL`.
- [x] 1.2 `persistence/SweepLease.java` record + `persistence/SweepLeaseRepository.java`
      with the conditional upsert (`ON CONFLICT … WHERE sweep_leases.leased_until <= :now`),
      shaped like `WebhookDeliveryRepository.claim()`.
- [x] 1.3 `scheduling/SweepLeases.java` — `boolean runIfLeader(String, Duration, Runnable)`,
      non-blocking, DEBUG on loss, holder = pod hostname. Key constants live on the
      sweep classes, never derived from class names.

## 2. Wire the eight sweeps

- [x] 2.1 `SyncScheduler.sweep()` → `sync`, lease = `sync.poll-interval`.
- [x] 2.2 `RevetScheduler.sweep()` → `revet`, lease = `vetting.revet.interval`.
- [x] 2.3 `RetentionScheduler.evaluate()` → `retention-evaluate`, lease = `retention.poll-interval`.
- [x] 2.4 `RetentionScheduler.compact()` → `retention-compact`, lease = 2 × `retention.compaction-interval`.
- [x] 2.5 `WaiverExpirySweep.sweep()` → `waiver-expiry`, lease = `vetting.waiver-sweep-interval`.
- [x] 2.6 `WebhookDispatcher.poll()` → `webhook-dispatch`, lease = `webhooks.poll-interval`.
- [x] 2.7 `MirrorReconciliationSweep.sweep()` → `mirror-drift`, lease = `mirror.sweep-interval`;
      split out `sweepNow()` and keep the scheduled method named `sweep`.
- [x] 2.8 `AuditExportScheduler.poll()` → `audit-export`, lease = `audit-export.poll-interval`.

## 3. The two defects

- [x] 3.1 `WaiverRepository.markExpiryRecorded` becomes conditional and reports
      whether it stamped; `WaiverService.sweepExpired` writes the ledger entry
      only after a successful stamp, and returns the number actually recorded.
- [x] 3.2 `AuditSinkRepository.updateCursor(id, cursor, expected)` CAS overload;
      `AuditExportService.exportBatch` uses it and logs on a lost CAS. The
      unconditional overload stays for replay (`GW_AUDIT_0005`).

## 4. Tests (mutation-tested — each must fail against the unrepaired code)

- [x] 4.1 `SweepLeaseTests` — a second `runIfLeader` under the same key is refused
      while the lease holds; is granted once it lapses; two keys never block each
      other; a body that throws still releases nothing early but does not poison
      the next acquire.
- [x] 4.2 Every one of the eight scheduled methods is refused while another holder
      owns its key — driven from the sweep classes' own key constants so a ninth
      sweep added without a lease is visible.
- [x] 4.3 Waiver expiry: a pass whose stamp loses writes no ledger entry.
- [x] 4.4 Audit export: a CAS against a stale expectation does not move the cursor,
      and an unconditional replay rewind still does.
- [x] 4.5 Helm: `replicaCount: 3` + `storage.backend: object-store` renders with
      the sweeps on; `replicaCount: 3` + filesystem still refuses.

## 5. Requirements and docs

- [x] 5.1 `GW_FACADE_0014` — drop the "while any … uncoordinated background sweeps
      and pollers remains enabled" clause; keep the single-writer refusal.
- [x] 5.2 New `GW_FACADE_0030` + `SVC_GW_FACADE_0030`; annotate implementation and tests.
- [x] 5.3 Docs: `guides/storage-backends.md` (authoritative), `guides/deploying-on-kubernetes.md`,
      `reference/configuration.md`, `architecture.md` — and add the per-sweep
      safety table naming all **eight** passes.

## 6. Gates and evidence

- [x] 6.1 `./mvnw clean verify`
- [x] 6.2 `pnpm test:stories`, `pnpm e2e`
- [x] 6.3 `reqstool status local -p docs/reqstool` → PASS
- [x] 6.4 `openspec validate --all --strict`, `mkdocs build --strict`
- [x] 6.5 `evidence.md`, saying what the gates do not cover
