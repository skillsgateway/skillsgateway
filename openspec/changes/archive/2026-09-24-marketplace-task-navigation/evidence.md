# Evidence: marketplace-task-navigation

Branch `feat/marketplace-task-navigation`. One fresh run of every gate at commit
`a71a345a`, after the last edit to code, tests, requirements and documentation.
The run was made in a clean worktree of that commit (see "Where the gates ran").

**Updated after CI.** The first recorded run was at `b579aa61`. CI's Portal e2e
then failed on `admin_registers_ingests_and_approves_a_marketplace_in_the_portal`:
`getByText("Decided by")` is case-insensitive and also matched the card's
"decided by alice" line, which locally had not rendered yet when the assertion
ran. `a71a345a` makes the match exact; this run replaces the earlier one.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 780, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  20 passed (20)
[INFO]       Tests  176 passed (176)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:19 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  13 passed (13)
      Tests  65 passed (65)

$ (cd src/main/frontend && pnpm e2e)          # three consecutive runs
  21 passed (1.5m)
  21 passed (1.2m)
  21 passed (1.0m)

$ reqstool status local -p docs/reqstool      # reqstool 0.11.0 locally; CI pins 0.12.0
In Code:  253 total · 253 verified · 0 annotated, not verified · 0 missing annotation
Tests:    782 passed · 0 failed · 0 skipped · 0 SVCs missing tests · 0 SVCs missing MVRs

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.47 seconds
```

`pnpm test:stories` passed on its first run after
`rm -rf node_modules/.cache/storybook`.

## Where the gates ran

In the main checkout, `./mvnw clean verify` failed three times out of three on
one test, `NameCollisionRaceTests.two_colliding_approvals_racing_admit_exactly_one`
("only 0/1 approvals reached the transition within PT30S"), with the other 779
passing. The same test passes run on its own in that checkout (twice), and the
full suite passes in a fresh worktree both at `main` (`3611b915`) and at this
branch's own commits (`d756e384`, `8f047f56`, `b579aa61`, `a71a345a`). Surefire's default
run order is filesystem order, which differs between the two checkouts, so this
is an order dependence in the test that #480 added, not a behaviour of this
change — which touches no Java beyond four paths added to `SpaController`. It is
reported in the PR rather than fixed here.

## The change's own tests

| Test | SVC | What it shows |
| --- | --- | --- |
| `review-queue.test.tsx` `the_queue_lists_what_awaits_across_marketplaces_and_decides_nothing` | SVC_GW_INGEST_0037 | Held and revoked snapshots across marketplaces, newest first, linked to their review; a marketplace with nothing awaiting is absent; no Approve or Reject |
| `app-layout.test.tsx` `the_sidebar_counts_what_awaits_a_decision_and_opens_a_marketplace_into_its_sections` | SVC_GW_INGEST_0037 | The queue count on every page; inside a marketplace its sections with its own count, and only the section is the current entry |
| `marketplaces.test.tsx` `lists_marketplaces_with_their_awaiting_count_and_no_decision_controls` | SVC_GW_INGEST_0037 | The list is an index: an Awaiting link, no expander, no decision control |
| `marketplace-detail.test.tsx` `the_header_offers_ingest_and_the_client_wizard_on_every_section` | SVC_GW_AUTH_0043 | Ingest, Connect a client and the not-served statement on all four sections; the wizard repeats the held case |
| `marketplace-detail.test.tsx` `a_json_manifest_is_shown_reindented_with_a_duplicated_key_kept` | SVC_GW_INGEST_0032 | Both copies of a duplicated key survive, in order; Raw shows the stored bytes |
| `marketplace-detail.test.tsx` `a_file_named_json_that_does_not_tokenise_is_shown_as_stored` | SVC_GW_INGEST_0032 | Invalid JSON is shown as stored and says so, with no formatting control |
| `json-format.test.ts` `formatting_changes_only_the_whitespace_between_tokens` | — | 500 generated documents (duplicate keys, escapes, number spellings, arbitrary whitespace): formatting changes only inter-token whitespace, and is idempotent |
| e2e `the_client_wizard_is_offered_on_every_section_and_explains_the_held_case` | SVC_GW_AUTH_0043 | Real browser, before and after approval, every section |
| e2e `snapshot_contents_are_explored_on_an_address_that_restores_the_same_file` | SVC_GW_INGEST_0032 | Real marketplace.json shown Formatted by default, Raw one click away |

Tests that exercised decisions on the list — cooling-off, provenance, the waiver
form — moved to the snapshot card rather than being deleted, since the card is
now the only place those controls exist.

`openspec validate --all --strict` counted 31 items before archiving; after
archiving it counts 30.
