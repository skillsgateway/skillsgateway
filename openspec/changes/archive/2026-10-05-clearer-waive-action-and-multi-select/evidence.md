# Evidence: clearer-waive-action-and-multi-select

One fresh run of every gate, after the last code edit, on commit `3e2ed2ff`.

## `./mvnw clean verify`

```
[INFO] Tests run: 1064, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  09:09 min
```

## `(cd src/main/frontend && pnpm test:stories)`

The first run after `clean verify` failed to load 12 story files ("Failed to
fetch dynamically imported module"), none of them touched by this change; every
test that loaded passed. This is the stale Storybook cache that `mvnw clean`
leaves behind. After `rm -rf node_modules/.cache/storybook node_modules/.vite`,
two consecutive runs:

```
 Test Files  19 passed (19)
      Tests  98 passed (98)
```

## `(cd src/main/frontend && pnpm e2e)`

```
  ✓  18 [chromium] › e2e/portal.spec.ts:776:1 › blocking_groups_of_two_rules_are_waived_together_with_one_justification (1.6s)
  26 passed (1.5m)
```

## `reqstool status local -p docs/reqstool`

Exit 0.

```
skills-gateway │ GW_VETTING_0044 │ 1 │ 6 6 - - -
skills-gateway │ GW_VETTING_0061 │ 1 │ 2 2 - - -
Total Tests: 976                Total SVCs: 336
Passed tests 1074 │ Failed tests 0 │ Skipped tests 0 ║ SVCs missing tests 0 │ SVCs missing MVRs 0
```

## `openspec validate --all --strict`

```
Totals: 31 passed, 0 failed (31 items)
```

## `mkdocs build --strict`

```
INFO    -  Documentation built in 2.83 seconds
```

## Test-first

Before the implementation, `vetting-report.test.tsx` had 5 failing tests and 7
passing: the label test, the group-waive test and the 90-day test, all updated to
the new labels, plus the two new tests (coverage count, and the multi-select test
`SVC_GW_VETTING_0061`). All 12 passed after the implementation.

## Design pass

`impeccable detect` on `vetting-report.tsx` and `name-collision-notice.tsx`
reported nothing. The manual audit and the screenshots led to two fixes before
this run:

- Opening a waive form moves focus to Justification, because the button that
  opened it is removed.
- Rule names wrap between words (`break-words`) instead of mid-word
  (`break-all`) in the waive label and the selected-rules list.
