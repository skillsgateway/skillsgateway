# Tasks: webhook-delivery-sweep

## 1. Requirements

- [x] 1.1 Add GW_RETENTION_0011 and SVC_GW_RETENTION_0011 (automated test) to `docs/reqstool/`.

## 2. Sweep

- [x] 2.1 Test in `RetentionTests` (`@SVCs SVC_GW_RETENTION_0011`): old delivered/failed removed, old
  pending and recent rows kept, batch bound honoured, ledger row written once, nothing when retention
  disabled or nothing removed. Watch it fail.
- [x] 2.2 `WebhookDeliveryRepository.deleteSettledBefore`, `RetentionService.sweepWebhookDeliveries`,
  wired into `RetentionScheduler.compactNow` (`@Requirements GW_RETENTION_0011`).

## 3. Indexes

- [x] 3.1 Edit `V1__init.sql`; assert their catalog definitions in a test (a plan assertion on tiny tables is flaky).

## 4. Docs

- [x] 4.1 snapshot-retention guide, retention reference (ledger table), lifecycle-webhooks guide.

## 5. Gates, evidence, archive

- [x] 5.1 All gates; `evidence.md`; archive as the final commit.
