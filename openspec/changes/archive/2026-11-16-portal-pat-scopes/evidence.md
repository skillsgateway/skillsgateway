# Evidence: portal-pat-scopes

One final fresh run of every gate after the last code edit, on commit `813914255abee67213a66e7a7d0318c3c4d2a9d4`
(`feat(portal): let a user limit a personal access token to chosen marketplaces`; the only
later changes are this file, the task checkboxes and the archive). Run 2026-11-16, in the
change's own worktree. Stacked on `portal-token-homes` (#889) at `7b50303c`, unchanged
throughout.

Tests were written first and watched failing for the right reason:

- `CatalogTests.constituents_audit_manual_rebuild_and_the_reserved_name` failed on
  `the served revision names the catalog…` before `CatalogInfo.name` existed.
- `the_created_cleartext_is_not_left_in_the_mutation_cache` (page) and
  `the_wizards_cleartext_is_not_left_in_the_mutation_cache` (wizard) failed with the
  `create.reset()` removed from each. They also failed with `reset()` alone and no
  `gcTime: 0` on the mutation (the cache keeps a reset mutation until it is collected),
  and pass with both.

## `./mvnw clean verify`

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.385 s -- in dev.skillsgateway.server.DeletedSnapshotDecisionTests
[INFO] Tests run: 1372, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
exit 0
```

## `pnpm test:stories` (in `src/main/frontend`)

```
 Test Files  27 passed (27)
      Tests  144 passed (144)
```

## `pnpm e2e`

```
  ✓  32 [chromium] › e2e/portal.spec.ts:1482:1 › an_admin_sets_the_default_chain_and_clears_a_marketplaces_override (1.4s)
  ✓  33 [chromium] › e2e/portal.spec.ts:1545:1 › a_chain_change_marks_held_evidence_superseded_and_the_reviewer_refreshes_it (2.8s)

  33 passed (2.1m)
normalized classnames in src/main/frontend/test-results/playwright-junit.xml
exit 0
```

## `reqstool status local -p docs/reqstool` (after the clean build and the e2e run)

```
INCOMPLETE (0)
416/416 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
✓ spec/virtual-catalog
✓ change/virtual-catalogs
Totals: 31 passed, 0 failed (31 items)
```

## `mkdocs build --strict`

```
$ NO_MKDOCS_2_WARNING=1 mkdocs build --strict
INFO    -  Documentation built in 1.10 seconds
exit 0
```

`pnpm verify` (typecheck, oxlint, 332 jsdom tests, reqstool tags and merge) also passed on
this commit; the oxlint warnings it prints pre-date this change and are in other files.
