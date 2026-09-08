# Tasks: jdbcclient-improvements

## 1. Requirements (SSOT first)

- [x] 1.1 No new requirement ids: the change introduces no new required
      behaviour. Verified against `docs/reqstool/requirements.yml` and
      `origin/main`.
- [x] 1.2 The new tests attach to the existing SVC_GW_0037 and SVC_GW_0125,
      both of which they genuinely verify a further case of.

## 2. Part 4 — the N+1 in the vetting run read (GW_0037, SVC_GW_0037)

- [x] 2.1 RED: `VettingRunReadTests` asserts that reading a run issues a
      constant number of statements whatever the verdict count, and that
      findings are grouped, ordered and isolated per verdict and per run.
      Observed failing against the unfixed repository.
- [x] 2.2 GREEN: `VettingRepository.verdicts` reads every finding of the run in
      one join query and groups in memory; `findings(long)` is removed.
- [x] 2.3 `@SVCs({"SVC_GW_0037"})` on the new test.

## 3. Part 2 — spike `DataClassRowMapper`, then roll out (GW_0125, SVC_GW_0125)

- [x] 3.1 Spike on `MarketplaceRepository` answering the four questions from
      issue #314; results recorded in `design.md`.
- [x] 3.2 Roll out to the nine repositories whose records map 1:1.
- [x] 3.3 Keep the two composite reads hand-written, built on the same mapper.
- [x] 3.4 `DataClassRowMapperTests` pins the four spike answers as regressions,
      so a Spring or driver upgrade that changes any of them fails here rather
      than in a repository.

## 4. Part 1 — implicit enum casts

- [x] 4.1 Establish, against the real database, what the failure mode of a
      missing cast actually is, and what the implicit cast changes about it.
- [x] 4.2 Decide on the error-class argument alone; record the decision and its
      reasons in `design.md`. **Declined** — the implicit cast fixes the write
      side only, leaves every guarded predicate (including `undecide` and
      `revoke`) failing exactly as before, and moves an invalid label's rejection
      from planning time to row time.

## 5. Part 3 — batching the insert loops

- [x] 5.1 Measure the loops at realistic and pessimistic sizes against the real
      database.
- [x] 5.2 Decide; record the measurement in `design.md`. **Declined** — the
      saving is one round trip per member (3.6 ms at five members, 47 ms at a
      hundred), and every member is a git clone or a connector scan that dwarfs
      it.

## 6. Gauntlet

- [x] 6.0 Manual mutation pass, five plausible bugs, each applied and reverted
      individually. One survived on the first round and the test was strengthened
      rather than the mutant excused; all five are killed on the final source.

## 7. Gates and evidence

- [x] 7.1 `./mvnw clean verify`
- [x] 7.2 `pnpm test:stories`
- [x] 7.3 `pnpm e2e`
- [x] 7.4 `reqstool status local -p docs/reqstool` ends `PASS`
- [x] 7.5 `openspec validate --all --strict`
- [x] 7.6 `mkdocs build --strict`
- [x] 7.7 `evidence.md` written from one final fresh run
