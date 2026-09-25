# Evidence: governance-navigation

Branch `feat/governance-navigation`. One fresh run of every gate over the tree
committed as `6e83a872`, after the last edit to code, tests, requirements and
documentation. The run was made in the main checkout; the working tree was
identical to that commit.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 780, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  21 passed (21)
[INFO]       Tests  180 passed (180)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:24 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  14 passed (14)
      Tests  67 passed (67)

$ (cd src/main/frontend && pnpm e2e)
  21 passed (1.1m)

$ reqstool status local -p docs/reqstool      # older local install; CI pins 0.12.0
REQUIREMENTS: 274
Tests: 784 passed · 0 failed · 0 skipped · 0 SVCs missing tests · 0 SVCs missing MVRs

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.53 seconds
```

`pnpm test:stories` was run after `rm -rf node_modules/.cache/storybook`. The
first two runs failed to import the vetting story files ("Failed to fetch
dynamically imported module") with every loaded story passing; the third run
passed whole. None of those files is touched by this change; it is the known
Storybook harness flake.

## The change's own tests

- **SVC_GW_AUTH_0048** — `the_sidebar_groups_records_apart_from_configuration_and_nests_the_integrations`
  (vitest) and `webhooks_page_lists_subscribers_and_delivery_attempts` (e2e, which
  opens `/webhooks` cold and lands on `/integrations/webhooks`).
- **SVC_GW_AUDIT_0006** — `audit_page_exports_the_ledger_and_lists_sinks` (e2e): the
  sink is added from Audit sinks, then the ledger is downloaded from Audit log.
- The sink tests moved from `audit.test.tsx` to `audit-sinks.test.tsx` unchanged
  apart from opening the add dialog first; the webhook form tests likewise.

**Shown to fail first.** With the sinks page offering its controls to every
session (`canAdd={true}`), `a_session_without_the_administrative_role_reads_the_sinks_but_is_offered_no_change`
fails; restored, it passes. Both role tests seed the session into the query
cache, so an absent control cannot pass merely because the roles have not loaded.

## Design harness

`/impeccable audit` on the five changed portal files: the detector reports
nothing. One layout defect was found by inspecting the running build at
1440px and fixed before the gates: the header action wrapped beneath a long
page description (the heading block is now `min-w-0 flex-1 basis-80`). The
URL-mode mobile scan was not run — it needs puppeteer, which the project does
not install.
