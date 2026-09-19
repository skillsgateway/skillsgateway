# Evidence: single-init-migration

Commit: `379d233e` (branch `feat/single-init-migration`, rebased onto
`825bb396`). One fresh run of every gate after the last code edit.

## The central claim: the squash changed nothing

A database migrated with the six-file chain and one migrated with the squashed
`V1__init.sql` produce identical schemas. Both dumps taken with the same
binaries — PostgreSQL 17.11, `pg_dump` from the same container.

```console
$ docker run -d --name sgw-schema-check -e POSTGRES_PASSWORD=pw -e POSTGRES_USER=sgw postgres:17

# baseline: the six-file chain, from main
$ docker exec sgw-schema-check createdb -U sgw baseline
$ for f in V1__init V2__rename_connector_to_vetter V3__namespace_webhook_events \
           V4__access_token_last_used V5__facade_credential_kind V6__vetting_chain_settings; do
    docker exec -i sgw-schema-check psql -U sgw -d baseline -v ON_ERROR_STOP=1 -q \
      < "src/main/resources/db/migration/${f}.sql"
  done
$ docker exec sgw-schema-check pg_dump -U sgw --schema-only --no-owner --no-privileges -d baseline > baseline.sql

# squashed: the single V1, from this branch
$ docker exec sgw-schema-check createdb -U sgw squashed
$ docker exec -i sgw-schema-check psql -U sgw -d squashed -v ON_ERROR_STOP=1 -q \
    < src/main/resources/db/migration/V1__init.sql
$ docker exec sgw-schema-check pg_dump -U sgw --schema-only --no-owner --no-privileges -d squashed > squashed.sql

$ diff baseline.sql squashed.sql
5c5
< \restrict ZCcEylhwQhrTM6Sxyc1tEdeTCcSZrgYR6JqwIZaNKKX3MnMMrOomlpARl4YuuT5
---
> \restrict ww9MTWsbACnEsJ3iM9b1GRxSlM45UGiePOAxPUjIbzd6YhzfXIiCGuYdpCPwDft
1597c1597
< \unrestrict ZCcEylhwQhrTM6Sxyc1tEdeTCcSZrgYR6JqwIZaNKKX3MnMMrOomlpARl4YuuT5
---
> \unrestrict ww9MTWsbACnEsJ3iM9b1GRxSlM45UGiePOAxPUjIbzd6YhzfXIiCGuYdpCPwDft
```

The only difference is `pg_dump`'s own per-dump random nonce on its `\restrict`
guard lines — not schema content. Excluding those two lines:

```console
$ grep -v '^\\\(un\)\?restrict ' baseline.sql > baseline.clean.sql
$ grep -v '^\\\(un\)\?restrict ' squashed.sql > squashed.clean.sql
$ diff baseline.clean.sql squashed.clean.sql && echo EMPTY
EMPTY
$ wc -l < baseline.clean.sql
1596
```

**Empty diff over 1596 lines.** That settles the three things the design named
as the real risks, each confirmed present in the dump:

| Risk | Confirmed |
| --- | --- |
| `ADD COLUMN` appends | `last_used_at` last in `access_tokens`; `credential_kind` last in `fetch_log` |
| `ALTER TYPE ADD VALUE` appends | `not_reached` last in `vetting_verdict_state`, after `disabled` |
| `ALTER … RENAME` renames the object, not the generated name | `vetter_toggles_pkey`, `vetter_toggles_vetter_check`, `vetter_toggles_updated_by_check`, `vetter_toggles_vetter_marketplace_id_key`, `vetter_toggles_marketplace_id_fkey`, `vetter_toggles_id_seq`, `idx_vetter_toggles_lookup` |

## Gates

```console
$ ./mvnw clean verify
[INFO] BUILD SUCCESS
[INFO] Total time:  06:04 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  9 passed (9)
      Tests  45 passed (45)

$ (cd src/main/frontend && pnpm e2e)
  20 passed (1.1m)

$ reqstool status local -p docs/reqstool
242/242 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 32 passed, 0 failed (32 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.38 seconds
```

!!! note "One flake, re-run"

    `pnpm test:stories` failed once before this run with
    `The iframe "…/adoption.stories.tsx" did not become ready within 60000ms` —
    a tester-initialization timeout in a story this change does not touch (the
    diff contains no frontend source). It passed on re-run with all 9 files and
    45 tests. Recorded rather than hidden.

## Deferred, deliberately

Task 6.2 — recreating the live instance's database and confirming a clean start
— is not done and cannot be until this is on `main`: the live runner serves
`origin/main`. The squashed schema is exercised by the full suite meanwhile,
which builds it from scratch in Testcontainers on every run. The runner's
out-of-order flag is already removed and the recreate procedure recorded in
`~/agents/skillsgateway/live/README.md`.
