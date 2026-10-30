# Evidence: portal-polish

One fresh run of every AGENTS.md gate after the last code edit, at commit `94786737`
(branch `fix/portal-polish`).

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

## Gates

```text
$ ./mvnw clean verify
[INFO] Tests run: 1184, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  33 passed (33)
[INFO]       Tests  246 passed (246)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:28 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  21 passed (21)
      Tests  111 passed (111)
(the first two attempts hit the known "Failed to fetch dynamically imported module" harness flake
while the e2e suite was running; cleared node_modules/.cache/storybook and .vite, then green)

$ (cd src/main/frontend && pnpm e2e)
  ✓   8 [chromium] › e2e/portal.spec.ts:389:1 › an_unknown_address_opened_cold_shows_page_not_found_inside_the_portal
  ✓   9 [chromium] › e2e/portal.spec.ts:404:1 › a_deep_link_returns_from_sign_in_without_the_marker_and_names_its_tab
  29 passed (1.8m)

$ reqstool status local -p docs/reqstool
                Total Tests: 1110                              Total SVCs: 387
║  Passed tests  │ Failed tests │ Skipped tests ║ ║ SVCs missing tests │ SVCs missing MVRs ║
║ 1209 (108.92%) │  0 (0.00%)   │   0 (0.00%)   ║ ║     0 (0.00%)      │     0 (0.00%)     ║
GW_AUTH_0067 3 SVCs 5/5 · GW_AUTH_0068 2/2 · GW_AUTH_0069 3/3 · GW_AUTH_0070 1/1 · GW_INGEST_0037 4/4
exit 0

$ openspec validate --all --strict
Totals: 30 passed, 0 failed (30 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.55 seconds
```

## Impeccable harden (touched pages)

Text overflow, error, empty and edge states were walked on the review queue, tokens, Review and
Snapshots, audit, adoption, webhooks, the vetting flow and the new not-found page. Fixed in this
change: a long `ingestedBy` principal in the queue and a long token name (the server stores names
without a length limit) are truncated with the whole value on hover; the token-created dialog title
and the snapshot-not-found message wrap anywhere. Nothing dismissed.
