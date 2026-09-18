# Tasks: single-init-migration

## 1. Capture the baseline before changing anything

- [x] 1.1 From `main`, migrate a scratch PostgreSQL database with the six-file
      chain and capture `pg_dump --schema-only --no-owner --no-privileges` to
      `baseline.sql` outside the working tree.
- [x] 1.2 Record the exact `pg_dump` and PostgreSQL versions used, so the second
      dump is taken with the same binaries and the diff means something.

## 2. Squash the schema

- [x] 2.1 Fold `V2__rename_connector_to_vetter.sql` into `V1__init.sql`: rename
      `vetting_verdicts.connector` → `vetter` and its unique constraint;
      `snapshot_vetting_overrides.blocking_connectors` → `blocking_vetters`;
      `connector_toggles` → `vetter_toggles` with its column, primary key, both
      check constraints, unique constraint, foreign key, index and sequence all
      carrying the `vetter_` spelling. Drop the two `UPDATE` statements — there
      are no rows to repair.
- [x] 2.2 Fold `V3__namespace_webhook_events.sql`: nothing survives. Its only
      statement is the subscriber-filter rewrite, which is data repair.
- [x] 2.3 Fold `V4__access_token_last_used.sql`: add `last_used_at TIMESTAMPTZ`
      as the **last** column of `access_tokens`, with no index, carrying the
      comment about its one-minute write bound and why it is not backfilled.
- [x] 2.4 Fold `V5__facade_credential_kind.sql`: declare the
      `fetch_log_credential_kind` enum beside the other enum types and add
      `credential_kind` as the **last** column of `fetch_log`, nullable, no
      default, not a foreign key, carrying the comment explaining each of those
      three choices.
- [x] 2.5 Fold `V6__vetting_chain_settings.sql`: add `not_reached` as the
      **last** value of the `vetting_verdict_state` enum literal, after
      `disabled`; declare `vetting_chain_mode_mode` and the
      `vetting_chain_modes` and `vetting_chain_orders` tables, keeping the
      comments on what `not_reached` means, why the settings are two tables, and
      why `vetters` is an array.
- [x] 2.6 Delete `V2__`, `V3__`, `V4__`, `V5__` and `V6__`.
- [x] 2.7 Review the folded comments against the rule in `design.md` —
      Decisions: keep what explains a column, drop what explains a transition.

## 3. Prove the squash changed nothing

- [x] 3.1 Migrate a second scratch database from the squashed `V1__init.sql` and
      dump it with the same binaries and flags as task 1.1.
- [x] 3.2 `diff` the two dumps. **The diff must be empty.** If it is not, fix
      `V1__init.sql` until it is — a non-empty diff is a real difference, not a
      formatting artefact, and the most likely causes are column order, enum
      value order, and a constraint or index name.
- [x] 3.3 Paste the exact commands and the empty diff into `evidence.md`.

## 4. Narrow the requirement

- [x] 4.1 In `docs/reqstool/requirements.yml`, remove the final sentence of
      `GW_WEBHOOK_0008 — Namespaced lifecycle events` obliging the system to
      rewrite an existing subscriber's stored filter when a vocabulary name
      changes. Leave every other obligation intact and bump `revision`.
- [x] 4.2 In `WebhookTests`, delete the `rewriteStoredFilters` helper, the
      `FILTER_MIGRATION` and `FILTER_MIGRATION_PREDICATE` constants, and the
      block of `SVC_GW_WEBHOOK_0008` that writes pre-namespace subscribers and
      executes the migration over them. Keep the assertions on the namespaced
      vocabulary, the refusal of a legacy spelling, and the namespaced name in
      header and body; keep the `@SVCs` annotation.
- [x] 4.3 Leave a short comment at that test naming the decision that removed
      the block, so the narrowing is legible rather than a gap.
- [x] 4.4 Confirm nothing else in the tree reads a migration file off the
      classpath.

## 5. Record the convention

- [x] 5.1 State it in `CLAUDE.md` with its condition attached: while pre-1.0, a
      schema change edits `V1__init.sql` rather than adding a versioned
      migration, because there is no deployed database to upgrade.
- [x] 5.2 Correct the two places that describe `V1__init.sql` as the whole
      schema — they become true, and should say why rather than by accident.
- [x] 5.3 Update `corpus-aware-vetting`'s design and tasks: its planned
      `V2__snapshot_facts.sql` becomes an edit to `V1__init.sql`, and the
      sentence asserting `V2` is the first migration after `V1` goes.
- [x] 5.4 Note in the local-development guide that a checksum failure after
      pulling this change means "drop and recreate", with the command.

## 6. Local operations

- [x] 6.1 Remove the Flyway out-of-order flag from
      `~/agents/skillsgateway/live/run-latest.sh` and note in that directory's
      `README.md` why it is gone.
- [ ] 6.2 Recreate the live instance's database once and confirm it starts clean
      on the squashed `V1`. **Deferred until this change is on `main`** — the live
      runner serves `origin/main`, so there is nothing to confirm against until
      then. `./mvnw clean verify` exercises the squashed schema meanwhile.

## 7. Gates and archive

- [x] 7.1 `./mvnw clean verify`
- [x] 7.2 `(cd src/main/frontend && pnpm test:stories)`
- [x] 7.3 `(cd src/main/frontend && pnpm e2e)`
- [x] 7.4 `reqstool status local -p docs/reqstool` — must end `PASS`
- [x] 7.5 `openspec validate --all --strict`
- [x] 7.6 `mkdocs build --strict`
- [x] 7.7 Write `evidence.md` from one final fresh run after the last edit,
      including the schema diff from task 3.3 and the commit SHA.
- [ ] 7.8 Open the PR with an **Evidence** section; archive the change as the
      final commit.
