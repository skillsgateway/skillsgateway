# Tasks: bound-ledger-growth

## 1. Requirements

- [ ] 1.1 Add `GW_RETENTION_0009` (audit ledger read entries are trimmed only
      behind every sink's export position) and `GW_RETENTION_0010`
      (administrative and publication ledger entries are never trimmed) to
      `docs/reqstool/requirements.yml`, from the drafted text in
      `design.md — Requirement text to mint`.
- [ ] 1.2 Re-confirm both ids are free at the moment of writing — against
      `requirements.yml` **and** every in-flight `openspec/changes/`, not just
      the former.
- [ ] 1.3 State in `GW_RETENTION_0010`'s rationale that it generalises
      `GW_RETENTION_0008 — An administrative revocation is never erased by
      retention` rather than inventing a new principle, so the two cannot drift.
- [ ] 1.4 Reference `GW_AUDIT_0004 — Audit export sinks with at-least-once
      delivery` from `GW_RETENTION_0009`: the watermark is that requirement's
      cursor, and the dependency must be visible from the requirement text.
- [ ] 1.5 Bump `GW_AUDIT_0005` and `GW_OBSERVABILITY_0003` revisions and extend
      their descriptions per the spec deltas — the replay bound and the two
      gauges respectively. Do not weaken either statement.
- [ ] 1.6 Add the two matching SVCs for the new requirements.

## 2. The watermark

- [ ] 2.1 A repository query for the lowest `cursor_position` across **enabled**
      `audit_sinks`, returning empty — not zero — when no enabled sink exists.
      Empty and zero must not collapse: zero is a sink that has exported nothing
      and empty is no sink at all, and both mean "trim nothing" for different
      reasons the log line has to tell apart.
- [ ] 2.2 A test that a disabled sink still pins nothing, and a test that a sink
      re-enabled after a trim does not resurrect eligibility for rows already
      gone.
- [ ] 2.3 Name the sink holding the watermark in the pass's result, so the log
      line can say which consumer is the reason nothing moved.

## 3. The trim

- [ ] 3.1 A `FetchLogRepository` method deleting one chunk: ascending `id`,
      `event` in the closed admitted set, `ts` before the cutoff, `id` at or
      below the watermark. Returns rows affected.
- [ ] 3.2 The admitted event set is a named constant in one place, with the
      comment that it is an allowlist so a later event kind is not trimmable
      until deliberately admitted.
- [ ] 3.3 A `RetentionService` pass looping chunks until a chunk returns zero or
      the time budget expires, each chunk its own transaction.
- [ ] 3.4 Chunk size and time budget are **constants**, not `skills-gateway.*`
      leaves. Assert this — `ConfigSurfaceBudgetTests` must move by exactly one.
- [ ] 3.5 Call it from `RetentionScheduler.compact()`'s service method, not from
      the `@Scheduled` wrapper, so the existing tests reach it the way every
      other pass is reached. No new `@Scheduled` component and no new lease key.
- [ ] 3.6 A `PassResult`-shaped return and a log line only when it removed
      something, matching the two existing passes.

## 4. Configuration

- [ ] 4.1 `skills-gateway.retention.ledger-max-age` on the existing `Retention`
      record. Unset, zero or negative switches the trim off — the fail-safe idiom
      `stagingRefMaxAge` documents, with the same reason stated once and not
      restated.
- [ ] 4.2 Bump the `ConfigSurfaceBudgetTests` ratchet by one, with the argument
      pointing at `proposal.md — Impact` rather than repeating it.
- [ ] 4.3 No `ContextBudgetTests` movement: reuse an existing `@SpringBootTest`
      property set.

## 5. The negative tests (old-coder discipline applies)

- [ ] 5.1 Prove each test fails before the code exists, per
      `.claude/skills/old-coder`.
- [ ] 5.2 **No sink at all** → nothing is deleted, however old the entries and
      whatever the age setting.
- [ ] 5.3 **One lagging sink** → the watermark is the laggard's, not the average
      and not the leader's.
- [ ] 5.4 **A disabled sink that is behind** → does not pin the watermark.
- [ ] 5.5 **An administrative entry inside the eligible age and below the
      watermark** → survives. Same for each publication event.
- [ ] 5.6 **`ledger-max-age` unset while retention is enabled** → no-op.
- [ ] 5.7 **`retention.enabled` false with `ledger-max-age` set** → no-op.
- [ ] 5.8 **An entry above the watermark but past the cutoff** → survives; age
      alone is never sufficient.
- [ ] 5.9 The time budget is honoured: a pass with more eligible rows than the
      budget allows removes some, reports the budget hit, and the next pass
      continues. Not a test that sleeps — inject the clock.
- [ ] 5.10 A mutation-style check that removing either conjunct from the
      predicate makes a test fail; a predicate whose halves are untested
      independently is a predicate with one half.

## 6. Observability

- [ ] 6.1 A ledger-depth gauge from the planner estimate, not `count(*)`.
- [ ] 6.2 A gauge for the age of the oldest entry below no enabled sink's
      cursor; defined and not absent when there is no sink, since that is the
      deployment it exists for.
- [ ] 6.3 Fixed-vocabulary tags only, recorded regardless of export, per
      `GW_OBSERVABILITY_0003`.

## 7. Documentation (same PR)

- [ ] 7.1 `guides/snapshot-retention.md` — the ledger trim, and the precondition
      stated in the same paragraph as the setting: no sink means no trim.
- [ ] 7.2 `guides/exporting-the-audit-ledger.md` — register sinks before enabling
      the trim; replay reaches only surviving entries; a new sink does not
      receive trimmed history.
- [ ] 7.3 `concepts/snapshots-and-ledger.md` — the ledger is append-only and now
      also finite, and what bounds it.
- [ ] 7.4 `reference/observability.md` — the two gauges.
- [ ] 7.5 `reference/configuration.md` — the one new leaf.
- [ ] 7.6 `capability-map.md` — the Retention row claims snapshots today; it now
      covers the ledger too.
- [ ] 7.7 Check whether the retention page needs a `docs/diagrams/` update; skip
      it explicitly if not, rather than leaving the question open.

## 8. Gates and close-out

- [ ] 8.1 `./mvnw clean verify` in the **foreground** — the background watchdog
      kills it. `MAVEN_OPTS="-Xmx3g"`, own Bash call.
- [ ] 8.2 `pnpm test:stories`, `pnpm e2e`, `reqstool status local -p
      docs/reqstool` (must end PASS), `openspec validate --all --strict`,
      `mkdocs build --strict` — each its own call.
- [ ] 8.3 `evidence.md` from one final fresh run after the last code edit, with
      the commit SHA.
- [ ] 8.4 Update `docs/analysis/2026-09-08-assessment-progress.md` step 9: the
      partitioning half is answered, and the answer was to reject partitioning.
      This closes the assessment run.
- [ ] 8.5 `openspec archive bound-ledger-growth --yes` as the PR's final commit.
- [ ] 8.6 PR body from `.github/pull_request_template.md`, with an **Evidence**
      section. No closing keyword for an issue — this has none.
