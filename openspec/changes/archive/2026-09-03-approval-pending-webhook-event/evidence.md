# Evidence: approval-pending-webhook-event

One fresh run of every gate after the last code and documentation edit.

- **Commit under test:** `82a7aa1` — `docs(webhooks): document the approval-pending event and its payload`
- **Rebased onto:** `9616fb7` (`origin/main`, includes #249 and #250)
- **Working tree:** clean at the time of the run (`git status --short` printed nothing)

## The tests were red first

The two `WebhookTests` cases and the `EstateReconciliationTests` case were
written and run before any production behaviour existed. The event constant was
present but deliberately not in `WebhookEvent.ALL` and nothing emitted it, so
subscriber registration refused the filter — the right reason to fail:

```
$ ./mvnw -o -q test -Dtest='WebhookTests#a_held_snapshot_announces_itself_only_to_the_subscribers_that_asked+the_approval_pending_payload_summarises_the_run_and_discloses_no_content'
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 14.31 s <<< FAILURE! -- in dev.skillsgateway.server.WebhookTests
[ERROR]   WebhookTests.a_held_snapshot_announces_itself_only_to_the_subscribers_that_asked:180->createSubscriber:137 Status expected:<201> but was:<400>
[ERROR]   WebhookTests.the_approval_pending_payload_summarises_the_run_and_discloses_no_content:225->createSubscriber:137 Status expected:<201> but was:<400>

$ ./mvnw -o -q test -Dtest='EstateReconciliationTests#a_declared_subscriber_may_filter_on_the_approval_pending_event'
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 14.50 s <<< FAILURE! -- in dev.skillsgateway.server.EstateReconciliationTests
[ERROR]   EstateReconciliationTests.a_declared_subscriber_may_filter_on_the_approval_pending_event:391
expected: "created"
```

## Gate 1 — `./mvnw clean verify`

```
[INFO] Tests run: 418, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 261 files clean - 0 needs changes to be clean, 261 were already clean, 0 were skipped because caching determined they were already clean
[INFO] You have 0 Checkstyle violations.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  06:54 min
[INFO] Finished at: 2026-09-03T11:00:36+02:00
```

`OpenApiContractTests` passes with `src/main/frontend/openapi.json` unchanged,
which is the check that the new payload records add nothing to the OpenAPI
document — they are not reachable from any `paths` entry. That is the state
design.md describes and #121 owns; nothing needed regenerating, and
`types.gen.ts` is therefore unchanged too.

## Gate 2 — `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
   Start at  11:01:02
   Duration  8.34s (transform 0ms, setup 4.79s, import 1.88s, tests 2.53s, environment 0ms)

JUNIT report written to .../src/main/frontend/test-results/storybook-junit.xml
```

## Gate 3 — `(cd src/main/frontend && pnpm e2e)`

```
  ✓  12 [chromium] › e2e/portal.spec.ts:581:1 › preview_pane_shows_tree_inert_skill_md_and_diff_vs_served (6.8s)
  ✓  13 [chromium] › e2e/portal.spec.ts:665:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim (617ms)

  13 passed (1.0m)
normalized classnames in .../src/main/frontend/test-results/playwright-junit.xml
```

The first attempt aborted before any test ran, because an unrelated long-running
process on this machine (`expo start --port 8081`) holds the runner's default
gateway port:

```
Web server failed to start. Port 8081 was already in use.
```

Re-run with the runner's own documented override — `E2E_GATEWAY_PORT=8091 pnpm
e2e` — which is the only difference from the plain command; the suite, the
fixtures and the compose infrastructure are untouched.

## Gate 4 — `reqstool status local -p docs/reqstool`

```
  GW_0154             skills-gateway
  GW_0159             skills-gateway
  GW_0160             skills-gateway

INCOMPLETE (0)
149/149 complete · 0 incomplete · PASS
```

## Gate 5 — `openspec validate --all --strict`

```
✓ spec/upstream-sync
✓ spec/vetting-waivers
✓ spec/virtual-catalog
Totals: 31 passed, 0 failed (31 items)
```

## Gate 6 — `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 8.54 seconds
```
