# Evidence: webhook-delivery-sweep

Final fresh run of all gates after the last code edit; code commit 375f0868.

| Gate | Command | Result |
| --- | --- | --- |
| Java + UI + jar | `./mvnw clean verify` | `Tests run: 996, Failures: 0, Errors: 0, Skipped: 9` / `BUILD SUCCESS` |
| Storybook | `pnpm test:stories` | `Test Files 17 passed (17)`, `Tests 88 passed (88)` (first run hit the known iframe flake; caches cleared, re-run green) |
| e2e | `pnpm e2e` | `24 passed (1.2m)` |
| Traceability | `reqstool status local -p docs/reqstool` | `325/325 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `change/webhook-delivery-sweep` passed, 0 failed |
| Docs | `mkdocs build --strict` | `Documentation built` |

Index usage is asserted by catalog definition, not by plan: on tiny tables the planner chooses by
cost (an attempted EXPLAIN assertion under `enable_seqscan = off` picked an unrelated index and
failed), so a plan assertion would be flaky.

## Addendum: order-dependent test fixed after the gate run

CI's first run of this PR failed `WebhookTests` 8/8: the new sweep cases in
`RetentionTests` left an enabled catch-all subscriber and a due pending delivery
in the shared database, and `WebhookTests`' backlog guard caught them. The local
gate run above passed only because of test order. Commit e54dda94 makes the
subscriber disabled and removes it with its deliveries after each case.

```
./mvnw -q test -Dtest='RetentionTests,WebhookTests' -Dsurefire.runOrder=alphabetical
# without the fix: WebhookTests  Tests run: 8, Failures: 1
# with the fix:    RetentionTests Tests run: 9, Failures: 0
#                  WebhookTests   Tests run: 8, Failures: 0
```

The full suite for the final commit is the PR's `Build & gates` run.
