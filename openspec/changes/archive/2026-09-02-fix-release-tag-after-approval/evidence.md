# Evidence — fix-release-tag-after-approval

Commit under test: `c360b79b6a2c17e5cf3e1c958a038f918cd803f2`

Two gates were not run locally and are named, with the reason, at the bottom.
Nothing below was re-run to get a better result.

## `./mvnw clean verify`

```
[INFO] Tests run: 387, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 253 files clean - 0 needs changes to be clean, 253 were already clean, 0 were skipped because caching determined they were already clean
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
```

`clean` is deliberate: incremental compilation truncates the generated
annotation files the traceability gate reads.

This run covers the tree at `c360b79` with one exception stated plainly: two
later edits to `docs/manual/guides/releasing.md` — the "what survives it" table
rewritten per job rather than cumulatively, and one sentence about re-dispatch —
landed after it started. No Java source or test reads that file, and
`mkdocs build --strict` below ran after them.

## `SVC_GW_RELEASE_0003` was proved capable of failing

The reordering is the change, so the test that asserts the order had to be shown
to discriminate. With `tag`'s `needs` reverted to `[prepare, checks]`:

```
[ERROR] PackagingTests.releaseWorkflowIsDispatchOnlyPreviewsByDefaultAndGatesBeforePublishing:378
        [the approval must be able to prevent the tag]
[ERROR] Tests run: 8, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Restored, the same 8 tests pass.

## `openspec validate --all --strict`

```
✓ spec/release-packaging
Totals: 31 passed, 0 failed (31 items)
```

## `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 4.36 seconds
```

## `reqstool status local -p docs/reqstool`

```
128/142 complete · 14 incomplete · FAIL
```

**This does not pass locally, and would not for any change in this repository
that skips the browser suites.** All 14 incomplete requirements — `GW_INGEST_0007`,
`GW_AUTH_0005`, `GW_WEBHOOK_0004`, `GW_AUDIT_0006`, `GW_RETENTION_0006`, `GW_VETTING_0005`, `GW_VETTING_0010`, `GW_VETTING_0018`,
`GW_OBSERVABILITY_0004`, `GW_AUTH_0014`, `GW_APPROVAL_0005`, `GW_APPROVAL_0011`, `GW_AUTH_0015`, `GW_AUTH_0025` — carry their
`@SVCs` in `src/main/frontend/e2e/portal.spec.ts`, so their annotations only
exist once the Playwright suite has run. `GW_RELEASE_0003`, the requirement this change
revises, is in the complete set. CI runs the suite and the gate together.

## Gates not run, and why

- `(cd src/main/frontend && pnpm test:stories)` — started and abandoned: it sat
  in `[optimizer] bundling dependencies` for twenty minutes without producing a
  test, on a machine running several builds at once. Not retried.
- `(cd src/main/frontend && pnpm e2e)` — not run, for the same reason.

Neither is a gate this change can move: it touches
`.github/workflows/release.yml`, one Java test, two `docs/reqstool/` entries and
two Markdown files, and no frontend source, story or route. CI runs both on the
pull request, and `reqstool` will report `PASS` there.
