# Tasks: v1-schema-shape-corrections

**Completed, with four exceptions left unticked deliberately.** Recorded rather
than quietly ticked:

- **1.3** — verified by grep that only `SqlArrays` builds an array literal, but no
  standing assertion enforces it. A second hand-rolled builder would pass CI.
- **4.1** — the old-coder rule to prove each test fails first was not followed
  rigorously. The constraints are new, so the cases could not exist before them;
  one case did fail informatively (the `api_scopes` test passed for the wrong
  reason until an expiry was added), which is how that defect was caught.
- **4.8** — no mutation-style check that dropping a `cardinality` guard breaks a
  test. The guards are asserted per column; nothing proves the assertions are
  load-bearing.
- **5.3** — `NULL` and the empty list are still both readable as "no entries" at
  the Java boundary, because `scopeList()` deliberately collapses them and its
  documented semantics depend on that. The database now makes the empty array
  unrepresentable, which is what makes the collapse safe; the task's stronger ask
  — two shapes a caller cannot confuse — was not implemented, and the reasoning is
  in `design.md — Decision 1`.

Scope also grew during implementation: a fifth and sixth column
(`vetting_overrides.blocking_vetters`, `uncovered_findings`) and three published
payloads, the latter after the owner corrected the compatibility reasoning.

No requirements section: this change sets `skip_specs: true` and every requirement
keeps its exact meaning. See `proposal.md — Capabilities` for why, and do not mint
an id to satisfy a validator.

## 1. The array helper

- [x] 1.1 Extract `arrayLiteral` from `VettingChainSettingsRepository:163-174`
      into the existing `persistence` package as a package-visible helper. No new
      package.
- [x] 1.2 Point the original `vetters` call site at the extracted helper — a pure
      move, with the existing vetting-chain tests as the proof it was pure.
- [ ] 1.3 One implementation of the escaping only. Assert there is no second
      `{"` literal builder anywhere in `src/main/java`.

## 2. Schema (`V1__init.sql`, edited in place — never a `V2__…`)

- [x] 2.1 `CREATE TYPE vetting_run_trigger AS ENUM ('ingestion',
      'revet-scheduled', 'revet-manual')` beside the other enum types, and retype
      `vetting_runs.trigger` (`:369`).
- [x] 2.2 `marketplaces.name` (`:42`) gains
      `CHECK (name ~ '^[a-z0-9][a-z0-9_-]*$')`, with a comment saying it is defence
      in depth behind `MarketplaceRegistrationService` and not the primary
      validation — so a later reader does not move it.
- [x] 2.3 `CREATE INDEX` on `access_tokens (principal)`, named in the file's
      existing `idx_<table>_<purpose>` style, with the one-line reason.
- [x] 2.4 Retype all five columns to `TEXT[]`: `access_tokens.scopes`,
      `.push_scopes`, `.api_scopes`, `webhook_subscribers.events`,
      `vetting_overrides.blocking_vetters`.
- [x] 2.5 Add `CHECK (<col> IS NULL OR cardinality(<col>) > 0)` to the four
      nullable ones, and `CHECK (cardinality(events) > 0)` to `events`. One rule
      across all five — see `design.md — Decision 1` for why the empty array is
      never an acceptable value, and say in the comment what `NULL` means on each.
- [x] 2.6 Leave `session_derived_credentials_hold_no_api_scope` (`:293`) and
      `machine_credentials_expire` (`:299`) intact and verify both still mean what
      their comments say now that an empty `api_scopes` is impossible.
- [x] 2.7 Update the four column comments that describe the comma-delimited form
      (`:252`, `:265`, `:269`, `:311`) and the one at `:512`. The comment that
      justified the delimiter — "Names cannot contain the delimiter" — is now
      obsolete and its replacement should point at the `CHECK` from 2.2 instead.

## 3. Read and write paths

- [x] 3.1 `AccessToken:33,63,99` — the three `split(",")` accessors become plain
      list accessors over the array the row already carries.
- [x] 3.2 `TokenRepository` — every write of the three scope columns goes through
      the helper with a `::text[]` cast; every read uses `rs.getArray(...)`.
- [x] 3.3 Preserve `NULL` exactly on the read side: a `NULL` column is not an
      empty list at the Java boundary either, because `scopes` `NULL` means *every*
      marketplace and an empty list must never be able to stand in for it. Model it
      so the two cannot be confused by a caller — not two shapes that both look
      like "no entries".
- [x] 3.4 `WebhookSubscriber:21` and `WebhookService:310` read the array;
      the controller boundary joins to a comma string for the payload and splits
      on the way in. One place each, not scattered.
- [x] 3.5 `'*'` still means every event, as `['*']`.
- [x] 3.6 The `blocking_vetters` write path records an array.
- [x] 3.7 `VettingRunRepository` (or whichever writes `trigger`) writes through
      `:trigger::vetting_run_trigger`; reads stay `String`.

## 4. The negative tests (old-coder discipline — `access_tokens` is the auth boundary)

- [ ] 4.1 Prove each test fails before the change, per `.claude/skills/old-coder`.
- [x] 4.2 **A scope list cannot widen.** A token scoped to one marketplace is
      refused the others, before and after — asserted through the authentication
      path, not by reading the column.
- [x] 4.3 **`NULL` scopes still grants every marketplace**, and an empty array is
      rejected by the database rather than quietly meaning the same thing.
- [x] 4.4 **An empty `api_scopes` array is refused**, and therefore cannot produce
      a credential that counts as a machine credential while holding no scope.
      This is the case `design.md — Decision 1` exists for.
- [x] 4.5 **A name containing the old delimiter is refused** — at the API by
      `MarketplaceRegistrationService`, and at the database by 2.2 for any other
      write path.
- [x] 4.6 A stored value containing a quote or a backslash round-trips intact
      through the helper's escaping.
- [x] 4.7 `push_scopes` `NULL` still means no push, and cannot become "all" by
      any route.
- [ ] 4.8 A mutation-style check: removing the `cardinality` guard from any of the
      five columns makes a test fail.
- [x] 4.9 No existing SVC test is weakened or deleted to make any of this pass.

## 5. Contract

- [x] 5.1 `openapi.json` comes out **byte-identical**. Run the equality test and
      assert no regeneration was needed — if it differs, something leaked from
      storage into a DTO and that is a defect in this change, not a document to
      update.
- [x] 5.2 Confirm no `oasdiff` finding, and therefore no `!` on the PR title.
- [ ] 5.3 `pnpm gen:api-types` should be a no-op. Never
      `pnpm exec openapi-typescript`.

## 6. Documentation (same PR)

- [x] 6.1 Grep `docs/manual/` for the comma-delimited scope form and correct any
      page that describes *storage*. A page describing the `events` **payload**
      stays as it is — that payload has not changed.
- [x] 6.2 State nothing about the `events` payload becoming an array; that is PR C
      and claiming it here would make the docs wrong for a release.

## 7. Gates and close-out

- [x] 7.1 `MAVEN_OPTS="-Xmx3g" ./mvnw clean verify` in the **foreground**, own
      Bash call — the background watchdog kills it. Clean up leaked
      Testcontainers if it dies.
- [x] 7.2 `pnpm test:stories`, `pnpm e2e`, `reqstool status local -p docs/reqstool`
      (must end PASS), `openspec validate --all --strict`,
      `mkdocs build --strict` — each its own call.
- [x] 7.3 `evidence.md` from one final fresh run after the last code edit, with the
      commit SHA.
- [x] 7.4 Update `docs/analysis/2026-09-20-1.0.0-readiness-progress.md` and its
      committed twin in `~/agents/skillsgateway/`: 8a–8d merged, and note that a
      fifth column (`blocking_vetters`) was found and included.
- [x] 7.5 `openspec archive v1-schema-shape-corrections --yes` as the final commit.
- [x] 7.6 PR body from `.github/pull_request_template.md` with an **Evidence**
      section. No closing keyword — these findings have no issue.
