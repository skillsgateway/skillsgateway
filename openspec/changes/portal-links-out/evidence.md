# Evidence — portal-links-out

Commit: `0588b514` (the last code edit; every run below is after it).
Machine: local, Linux, Docker available.

## `./mvnw clean verify`

```
$ MAVEN_OPTS="-Xmx3g" ./mvnw clean verify
[INFO] Spotless.Java is keeping 380 files clean - 0 needs changes to be clean
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  06:00 min
```

## Storybook story tests

```
$ (cd src/main/frontend && pnpm test:stories)
 Test Files  9 passed (9)
      Tests  45 passed (45)
```

The first invocation failed all nine files at import with
`Failed to fetch dynamically imported module: .../@storybook/addon-vitest/dist/vitest-plugin/setup-file-with-project-annotations.js`,
with no test executed. It ran immediately after `mvnw clean`, on a cold
Storybook cache; the plain re-run above is green with no change in between. This
is the known flake in this harness, not a result of this change.

## End-to-end, real browser against the mock IdP

```
$ (cd src/main/frontend && pnpm e2e)
  20 passed (1.2m)
```

## Requirements traceability

```
$ reqstool status local -p docs/reqstool
246/246 complete · 0 incomplete · PASS
```

## OpenSpec

```
$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)
```

## Docs site

```
$ mkdocs build --strict
INFO    -  Documentation built in 1.28 seconds
```

One `INFO` line remains, a stale anchor in `guides/re-vetting.md` that predates
this branch and is fixed in PR #436. It is an `INFO`, so `--strict` passes.

## Component test added

`src/main/frontend/src/components/app-layout.test.tsx` —
`the_sidebar_links_out_to_the_manual_and_the_source`: both links are found by
role and accessible name, each carries `target="_blank"` and a `rel` containing
`noopener`, and each points at the expected address.

## Design harness

```
$ node .claude/skills/impeccable/scripts/detect.mjs src/main/frontend/src/components/app-layout.tsx
(no findings)
```

No new color, token or breakpoint is introduced. Sidebar nav rows are ~32px
tall, under the 44px touch-target guideline — pre-existing and systemic to every
row in the sidebar, not introduced here, and left alone.
