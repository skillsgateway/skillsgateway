# Evidence: portal-polish

One fresh run of every AGENTS.md gate after the last code edit, at commit `bd9878af`
(branch `fix/portal-polish`, after the review fixes; the first run was at `94786737`).

## Fails first

`BrowserPagesTests` was run with the server half of the change removed (`SecurityConfig` at its
previous state, `SignInPagesController`, `ErrorPagesConfig` and `BrandedErrorReportValve` deleted,
`BrowserNotFoundAdvice` unregistered):

```text
FAIL a_request_that_cannot_be_parsed_is_a_minimal_page_to_a_browser_and_plain_text_otherwise  (Tomcat's own "HTTP Status 400" page)
FAIL without_a_session_the_sign_in_pages_say_so_and_claim_no_sign_out                       (the generated login page)
FAIL with_a_session_the_pages_name_the_user_and_sign_out_carries_the_request_forgery_token   (the generated login page)
FAIL a_browser_asking_for_an_unknown_page_gets_the_portal_answered_not_found                 (application/problem+json)
pass every_other_client_and_every_machine_surface_keeps_its_problem_document                 (the regression guard: unchanged behaviour)
```

With the change restored all five pass.

## Review fixes, failing first

Each new or extended test was run against the code before its fix:

```text
FAIL BrowserPagesTests.every_other_client_and_every_machine_surface_keeps_its_problem_document
     (/%61pi/v1/no-such-thing: Content type [text/html;charset=UTF-8] is not compatible with [application/problem+json])
FAIL review-queue.test  ordering_by_marketplace_keeps_each_marketplace_newest_first_both_ways
FAIL app-layout-address.test  an_address_that_does_not_decode_still_names_its_tab  (URIError: URI malformed)
FAIL marketplace-detail.test  an_empty_snapshot_parameter_is_not_reported_missing  ("Snapshot  was not found")
FAIL tokens.test  a_refused_lifetime_is_stated_beside_the_choice_until_another_is_chosen
```

All pass with the fixes.

## Gates

```text
$ ./mvnw clean verify
[INFO] Tests run: 1184, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  33 passed (33)
[INFO]       Tests  250 passed (250)
[INFO] BUILD SUCCESS
[INFO] Total time:  08:28 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  21 passed (21)
      Tests  111 passed (111)

$ (cd src/main/frontend && pnpm e2e)
  ✓   8 [chromium] › e2e/portal.spec.ts:389:1 › an_unknown_address_opened_cold_shows_page_not_found_inside_the_portal
  ✓   9 [chromium] › e2e/portal.spec.ts:404:1 › a_deep_link_returns_from_sign_in_without_the_marker_and_names_its_tab
  29 passed (2.0m)

$ reqstool status local -p docs/reqstool
                Total Tests: 1110                              Total SVCs: 387
║  Passed tests  │ Failed tests │ Skipped tests ║ ║ SVCs missing tests │ SVCs missing MVRs ║
║ 1209 (108.92%) │  0 (0.00%)   │   0 (0.00%)   ║ ║     0 (0.00%)      │     0 (0.00%)     ║
GW_AUTH_0067 3 SVCs 5/5 · GW_AUTH_0068 2/2 · GW_AUTH_0069 3/3 · GW_AUTH_0070 1/1 · GW_INGEST_0037 4/4
exit 0

$ openspec validate --all --strict
Totals: 29 passed, 0 failed (29 items)

$ mkdocs build --strict
INFO    -  Documentation built in 2.00 seconds
```

## Impeccable harden (touched pages)

Text overflow, error, empty and edge states were walked on the review queue, tokens, Review and
Snapshots, audit, adoption, webhooks, the vetting flow and the new not-found page. Fixed in this
change: a long `ingestedBy` principal in the queue and a long token name (the server stores names
without a length limit) are truncated with the whole value on hover; the token-created dialog title
and the snapshot-not-found message wrap anywhere. Nothing dismissed.
