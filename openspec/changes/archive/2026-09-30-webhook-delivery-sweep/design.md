# Design: webhook-delivery-sweep

## Decisions

- **A constant, not a leaf.** `RetentionService.DELIVERY_MAX_AGE = Duration.ofDays(30)`. A delivery row
  exists to troubleshoot a receiver over days; the ledger holds the audit record of the event. Reusing
  `ledger-max-age` would couple two unrelated policies (compliance evidence vs. troubleshooting history).
- **Same pass, same gate.** Runs inside `compact`'s six-hourly pass under its lease and `retention.enabled`,
  bounded per pass by `retention.batch-size`. Its failure does not fail the purge, like the staging sweep.
- **Selection.** `state IN ('delivered','failed') AND updated_at < cutoff`; `updated_at` is set on every
  state transition, so no column is needed. `pending` is never selected, whatever its age.
- **Ledger.** One entry per pass that removed something, `webhook-deliveries-swept:removed=<n>`, following
  `staging-refs-swept:count=<n>`; marketplace `-` (deliveries are not marketplace-scoped).
- **Indexes.** `idx_fetch_log_ts ON fetch_log (ts) WHERE event = 'upload-pack'` matches both adoption
  reads. The retention idle veto matches `source <> 'admin'`, not `event`, so a partial index restricted
  to `upload-pack` cannot serve it; changing the predicate would weaken a data-protecting veto (info-refs
  currently counts). So `idx_fetch_log_sha_marketplace_ts ON fetch_log (sha, marketplace, ts) WHERE sha IS NOT NULL`
  is restricted only by `sha IS NOT NULL` (implied by both equality predicates) and also serves
  `fetchersOf`. `idx_webhook_deliveries_subscriber ON (subscriber_id, id)` serves `listBySubscriber`.
- Existing `idx_webhook_deliveries_due (state, next_attempt_at)` is unchanged; the sweep is bounded by a
  subselect on id ordered by `updated_at` and may scan; the table is small after the sweep.

## Risks

- A subscriber's delivery listing loses rows older than 30 days when retention is on. Documented.
