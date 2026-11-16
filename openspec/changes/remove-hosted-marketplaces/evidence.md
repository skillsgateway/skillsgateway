# Evidence: remove-hosted-marketplaces

One final fresh run of every gate after the last code edit. The tree under test was commit `c029eae4`;
the history was then regrouped into the four commits `76230a11`, `9df4c4b3`, `ed9217e2` and `443a375c`
with an identical tree (`git diff c029eae4 443a375c` is empty). Only the OpenSpec change itself (this
file, the task ticks and the archive step) came after, and the documentation gates were run again
after it (see the end).

`spec approval: not obtained (autonomous run)`. The executable spec is `design.md`; the owner's
decisions arrived as instructions during the run and are folded into it: the purge of every hosted
marketplace (live and removed) with no refusal, the repositories left untouched, the temporary
administrator notice and route, and the removal condition `REMOVE-AFTER: two minor releases after the
release that ships migration V3__remove_hosted_marketplaces`. Confidence is correspondingly lower
than a reviewed spec would give.

## RED: new tests watched failing for the stated reason

`HostedRemovalMigrationTests`, first draft of the test (when the design was to refuse a live hosted
marketplace; that test, `a_live_hosted_marketplace_refuses_the_upgrade...`, was replaced when the owner
chose the purge), before `V3` existed. V1 and V2 were staged and seeded correctly, then the target
version could not be found:

```text
[ERROR] Tests run: 3, Failures: 1, Errors: 2, Skipped: 0, Time elapsed: 5.236 s <<< FAILURE! -- in dev.skillsgateway.server.HostedRemovalMigrationTests
[ERROR]   HostedRemovalMigrationTests.a_live_hosted_marketplace_refuses_the_upgrade_naming_it_and_leaves_the_database_usable_by_the_old_schema:227 
[ERROR]   HostedRemovalMigrationTests.a_database_without_a_hosted_marketplace_upgrades_from_each_earlier_version_keeping_every_row:173 » Flyway No migration with a target version 3 could be found. Ensure target is specif
[ERROR]   HostedRemovalMigrationTests.a_fresh_database_and_an_upgraded_one_have_the_same_schema:286 » Flyway No migration with a target version 3 could be found. Ensure target is specified correctly and the migration exi
[ERROR] Tests run: 3, Failures: 1, Errors: 2, Skipped: 0
```


`RemovedHostingRefusalTests`, before any implementation (each assertion on the removed behaviour):

```text
[ERROR] Tests run: 5, Failures: 5, Errors: 0, Skipped: 0, Time elapsed: 5.622 s <<< FAILURE! -- in dev.skillsgateway.server.RemovedHostingRefusalTests
[ERROR]   RemovedHostingRefusalTests.a_registration_asking_for_the_hosted_origin_or_a_push_policy_is_refused_naming_the_removal:55 Status expected:<400> but was:<201>
[ERROR]   RemovedHostingRefusalTests.a_token_request_naming_push_scopes_is_refused_and_mints_nothing:104 Status expected:<400> but was:<403>
[ERROR]   RemovedHostingRefusalTests.an_estate_entry_declaring_the_hosted_origin_or_a_push_policy_fails_naming_the_removal:143 
[ERROR]   RemovedHostingRefusalTests.the_path_publishers_pushed_to_answers_like_a_path_that_never_existed:166 [none] 
[ERROR]   RemovedHostingRefusalTests.the_upstream_origin_still_registers_and_no_response_carries_the_removed_fields:72 
[ERROR] Tests run: 5, Failures: 5, Errors: 0, Skipped: 0
```


`HostedRemovalMigrationTests` and `FormerHostedRepositoriesTests` against the revised design (the
first draft of `V3` refused a live hosted marketplace, so the purge test failed at the migration; the
route did not exist, so each listing was `404`):

```text
[ERROR]   FormerHostedRepositoriesTests.a_session_without_the_administrative_role_and_a_machine_credential_are_refused:146 Status expected:<403> but was:<404>
[ERROR]   FormerHostedRepositoriesTests.a_symlink_a_stray_file_and_a_misnamed_directory_are_ignored:185->list:106 Status expected:<200> but was:<404>
[ERROR]   FormerHostedRepositoriesTests.an_administrator_is_told_each_repository_its_location_and_last_commit:119->list:106 Status expected:<200> but was:<404>
[ERROR]   FormerHostedRepositoriesTests.the_list_is_empty_when_there_is_nothing_and_ends_when_the_folder_does:193->list:106 Status expected:<200> but was:<404>
[ERROR]   HostedRemovalMigrationTests.a_database_without_a_hosted_marketplace_upgrades_from_each_earlier_version_keeping_every_row:181 [nothing may lack a URL any more] 
[ERROR]   HostedRemovalMigrationTests.every_hosted_marketplace_is_purged_with_its_history_and_nothing_else_is_touched:380->history:291 » DataIntegrityViolation PreparedStatementCallback; SQL [INSERT INTO snapshot_revocat
```

```text
[ERROR]   HostedRemovalMigrationTests.a_database_without_a_hosted_marketplace_upgrades_from_each_earlier_version_keeping_every_row:181 [nothing may lack a URL any more] 
[ERROR]   HostedRemovalMigrationTests.every_hosted_marketplace_is_purged_with_its_history_and_nothing_else_is_touched:451 » FlywayMigrate Failed to execute script V3__remove_hosted_marketplaces.sql
```


Portal tests were written after the component. The one that matters, that a non-administrator makes no
request for the list, was proved by a throwaway mutant: forcing the query's `enabled` to `true` failed
exactly `a_session_without_the_administrative_role_makes_no_request_for_the_list`, and restoring it
passed (6 of 6 in `overview.test.tsx`).

## Gates

### `./mvnw clean verify`

```text
[INFO] Tests run: 1351, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  03:39 min
rc=0
```


Spotless, Checkstyle, the CycloneDX SBOM, the UI gates and `OpenApiContractTests` (the committed
`openapi.json` equals the document the gateway serves) are inside this run.

### `pnpm test:stories`

```text
 Test Files  28 passed (28)
      Tests  146 passed (146)
rc=0
```


The very first story run after the Storybook rebuild failed two unrelated files with "Vitest failed to
find the current suite" (Vite re-optimising dependencies); the rerun passed (28 files, 146 tests)
and is the one recorded here.

### `pnpm e2e` (`E2E_GATEWAY_PORT=18083`)

```text
  33 passed (2.1m)
rc=0
```


### `reqstool status local -p docs/reqstool`

```text
415/415 complete · 0 incomplete · PASS
rc=0
```


### `openspec validate --all --strict`

```text
Totals: 31 passed, 0 failed (31 items)
rc=0
```


### `mkdocs build --strict`

```text
INFO    -  Documentation built in 1.12 seconds
rc=0
```


### Stop-rule ratchets

`ConfigSurfaceBudgetTests` and `ContextBudgetTests` both pass and neither number moved: 112 configuration
leaves of 112 (the estate marketplace element is not walked, so keeping the refused `origin` and
`pushPolicy` components costs nothing) and 28 Spring contexts of 28 (every deleted test class shared an
existing context, and the new classes extend `AbstractGatewayTest`). Nothing could be lowered.

## The API contract and the breaking-change gate

`OpenApiContractTests` passes. oasdiff (`tufin/oasdiff:v1.11.7`, the repository's
`.oasdiff-severity-levels.txt`) between the contract this branch forked from and this branch:

```text
rc=1
errors: 18 (all response-optional-property-removed), warnings: 3 (the removed request properties)
```


So the API contract workflow will classify this as breaking. It then requires the title to carry `!` or
`BREAKING CHANGE` (it does) and the `BREAKING CONTRACT` label, which is the owner's act and was not applied.
The compatibility page binds the promise from 1.0.0 and the latest release is 0.8.0.

## Upgrade rehearsal with real artifacts

The packaged jar of the base branch (`feat/portal-pat-scopes`, `0.9.0-5-SNAPSHOT`) against a fresh
PostgreSQL 18, then this branch's jar (`0.9.0-10-SNAPSHOT`) against the same database
(`rehearsal.sh`; local-development authentication so the API could be driven with curl). The new jar
was packaged by the gate run before the final one-line wording fix to the notice's parentheses, which
touches no backend or schema behaviour:

```text
### 1. OLD build (skills-gateway-server-0.9.0-5-SNAPSHOT.jar) on a fresh database
flyway versions: 1,2
-- create an upstream marketplace and a token through the API
{"id":1,"name":"upstream-one","url":"file://<work>/up-main","cre
token prefix: sgw_1Wqk...
-- ingest and approve it, then clone through the facade
{"marketplace":"upstream-one","running":{"stage":"queued","startedAt":"2026-11-16T23:34:42.263548Z","interrupted":false}
snapshot 1
{"id":1,"marketplaceId":1,"sha":"87aa6de7947ca06730f8a486e42de8e66ec2b22e","upstreamSha":"87aa6de7947ca06730f8a486e42de8
-- a HOSTED marketplace pushed to through /publish, with a push-scoped token
{"id":2,"name":"hosted-one","url":null,"createdAt":"2026-11-16T23:34:42.593587Z","registeredBy":"dev","origin":"hosted","pushPolicy":"append-only","forge":null,
To http://localhost:18084/publish/hosted-one
 * [new branch]      main -> main
hosted rows before: 1  folder: hosted-one.git
clone of upstream-one on OLD: 87aa6de fixture
-- stop OLD

### 2. NEW build (skills-gateway-server-0.9.0-10-SNAPSHOT.jar) against the same database
flyway versions: 1,2,3
hosted columns left: 0  push_scopes: 0
marketplaces now: upstream-one
clone of upstream-one on NEW with the OLD token: 87aa6de fixture
clone of hosted-one on NEW (expected to fail): fatal: repository 'http://localhost:18084/git/hosted-one/' not found
push to /publish on NEW (expected to fail): fatal: repository 'http://localhost:18084/publish/hosted-one/' not found
hosted folder untouched: yes
-- the administrator notice's route
[{"name":"hosted-one","location":"<work>/data/hosted/hosted-one.git","lastCommitAt":"2026-11-16T23:34:42Z","headSha":"2b04533"}]
-- after deleting the folder
[]
### done
```


## Impeccable audit of the Overview page and the notice

The bundled detector reported nothing on `former-hosted-notice.tsx` and `overview.tsx`. Read by hand:
accessibility (the notice is `role="status"` with an accessible name, the icon is `aria-hidden`, times are
`<time>`), theming (tokens only, both themes shot), responsiveness (names and paths `break-all`, no fixed
width, shot at 390 and 1280), performance (one query, only for an administrator). Two findings were
dismissed on purpose: `role="status"` rather than the `Alert` default `role="alert"`, because the notice
is persistent information and not an interruption; and no dismiss control, because it ends by itself when
the folders do. One cosmetic finding was fixed: a stray space inside the parentheses around the
`git clone` hint.

## Screenshots

Kept, not uploaded, under `.claude/worktrees/screenshots/` of the work tree (git-ignored), fixture data
only, light and dark at 1280 and 390: `before-` and `after-tokens-issuedtokendialog--reach-*` (the token
list's Reach column: the `publishes to` line is gone, and with it the fixture token that carried one),
and `after-overview-formerhostedlist--remaining-*` and `--one-*` (the administrator notice; it has no
before, the page had no such element).

## The suites that carry the new requirements, from the final `clean verify`

```text
HostedRemovalMigrationTests        tests=3 errors=0 failures=0   (SVC_GW_AUTH_0110, real PostgreSQL schemas at V1 and V2)
RemovedHostingRefusalTests         tests=5 errors=0 failures=0   (SVC_GW_AUTH_0111)
FormerHostedRepositoriesTests      tests=4 errors=0 failures=0   (SVC_GW_AUTH_0112)
OverviewPage notice (vitest)       6 of 6 in overview.test.tsx   (SVC_GW_AUTH_0113)
MarketplaceRemovalTests            tests=13 errors=0 failures=0  (name freed, a new upstream registers and ingests under it)
OpenApiContractTests               tests=9  errors=0 failures=0
MachineApiRegistryTests            tests=3  errors=0 failures=0
RoleEnforcementTests               tests=7  errors=0 failures=0
```

What `HostedRemovalMigrationTests` proves, per starting version (V1 and V2): with no hosted marketplace
every row and every surviving column is unchanged, `url` is `NOT NULL` again, the removed columns, types and
constraints are gone, tokens still fetch with the scopes they had (including a token whose only grant was a
push scope, which now fetches every marketplace, as an unscoped token does); with a live and a removed
hosted marketplace carrying snapshots, closures, facts, vetting runs, verdicts and findings, overrides,
reversals, waivers, exemptions, grants, toggles, chain settings, ledger entries by id, older ledger entries
with no id and a sighting, beside an unrelated upstream and an upstream that reused a purged name, V3
succeeds, the surviving rows are byte for byte what they were (queried table by table, the ledger by the
rule in `design.md`), the purged rows are exactly three marketplaces' worth, no column named
`marketplace_id`, `snapshot_id` or `last_ingest_snapshot_id` refers to a purged id, the older entries under
the reused name are left, and the hosted repository folder is byte for byte unchanged; and a fresh database
and one upgraded from either version have the same schema, as does the application's own.

`MarketplaceRemovalTests.removal_frees_the_name_for_a_new_upstream_marketplace_and_fetch_grants_carry_over`
is the operator procedure's last step: after the removal the name is free, a new upstream registers under it
and ingests, its snapshot is held until its own first approval, fetch grants to the name serve it once
approved, and the old record keeps its own snapshots.
