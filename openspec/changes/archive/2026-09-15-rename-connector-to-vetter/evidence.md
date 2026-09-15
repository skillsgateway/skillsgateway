# Evidence: rename-connector-to-vetter

One fresh run of every gate after the last code edit.

- **Commit:** `7161aa984c5daa2da4fc934a82ce7c0782ac37f8`
- **Branch:** `refactor/rename-connector-to-vetter`
- **Date:** 2026-09-15

## `./mvnw clean verify`

```
[INFO] Tests run: 649, Failures: 0, Errors: 0, Skipped: 9
[INFO] Spotless.Java is keeping 343 files clean - 0 needs changes to be clean
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  06:25 min
```

The new migration runs in every container-backed context:

```
DbMigrate : Migrating schema "public" to version "1 - init"
DbMigrate : Migrating schema "public" to version "2 - rename connector to vetter"
DbMigrate : Successfully applied 2 migrations to schema "public", now at version v2 (execution time 00:00.112s)
```

`OpenApiContractTests` passing is what pins `src/main/frontend/openapi.json` to
the document the gateway now serves.

## `(cd src/main/frontend && pnpm test:stories)`

```
 ✓ |storybook (chromium)| src/components/vetting-flow.stories.tsx (7 tests) 672ms

 Test Files  4 passed (4)
      Tests  13 passed (13)
```

## `(cd src/main/frontend && pnpm e2e)`

```
  ✓   7 [chromium] › e2e/portal.spec.ts:377:1 › vetting_verdicts_are_shown_and_a_blocked_snapshot_cannot_be_approved (1.5s)
  ✓   8 [chromium] › e2e/portal.spec.ts:402:1 › the_vetting_chain_is_drawn_as_a_flow_and_a_node_opens_its_evidence (1.4s)

  14 passed (48.7s)
```

*Environment note.* Another process on this machine held host port 5433 with its
own PostgreSQL, so `compose.e2e.yaml` and `run-e2e.sh` were pointed at 5434 for
the duration of this run and restored immediately after; the working tree is
unchanged and the suite exercised the same stack.

## `reqstool status local -p docs/reqstool`

Run after the two browser gates, as their annotations feed it.

```
INCOMPLETE (0)
220/220 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
✓ change/rename-connector-to-vetter
Totals: 32 passed, 0 failed (32 items)
```

## `mkdocs build --strict`

```
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 1.16 seconds
```

No unresolved-anchor INFO lines remain.

## Leftover-word grep

```
grep -rniw connector src docs openspec/specs docs/reqstool PRODUCT.md DESIGN.md .claude CLAUDE.md \
  --exclude-dir=node_modules --exclude-dir=dist --exclude-dir=test-results --exclude-dir=site --exclude-dir=node \
  | grep -vi external
```

Every surviving hit is one of five deliberate cases, listed in the PR's *Notes
for review*: `V1__init.sql` (applied, not edited), the V2 migration's own rename
statements, `docs/decisions/*.md` (immutable ADRs), `docs/language-decision.md`
row 18, and the narrowed definition itself in `glossary.md`,
`concepts/vetting.md` and the ADR summaries in `reference/decisions.md`.
